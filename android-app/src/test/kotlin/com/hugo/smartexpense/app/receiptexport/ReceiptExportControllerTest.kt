package com.hugo.smartexpense.app.receiptexport

import com.hugo.smartexpense.app.ReceiptReviewState
import com.hugo.smartexpense.app.ReceiptSourceType
import kotlinx.coroutines.CancellationException
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReceiptExportControllerTest {
    @Test
    fun separateTransactionsGetSeparateJsonFilesAndStatuses() = runTest {
        val records = mutableMapOf<String, ReceiptExportRecord>()
        val published = mutableListOf<ReceiptExportRecord>()
        val ids = listOf("aaaaaaaa-1111-4111-8111-111111111111", "bbbbbbbb-2222-4222-8222-222222222222").iterator()
        val controller = ReceiptExportController(
            repository = object : ReceiptExportRepository {
                override suspend fun save(record: ReceiptExportRecord) { records[record.expenseId] = record }
                override suspend fun get(expenseId: String) = records[expenseId]
            },
            imagePreparer = ReceiptExportImagePreparer { _, name -> PreparedReceiptImage(name, 3) },
            imageReader = ReceiptExportImageReader { byteArrayOf(1, 2, 3) },
            publisher = ReceiptExportPublisher { record, _ -> published += record },
            sourceDeviceId = { "device-1" },
            sourceDeviceName = { "Hugo's Pixel" },
            now = { ZonedDateTime.parse("2026-09-16T14:05:07-07:00") },
            newExpenseId = { ids.next() },
        )
        val first = controller.start(validReview())
        val second = controller.start(validReview().copy(merchantName = "Other shop", extractionStatus = "manual"))
        assertEquals(2, published.size)
        assertEquals(ReceiptExportStatus.EXPORTED, first.status)
        assertEquals(ReceiptExportStatus.EXPORTED, second.status)
        assertTrue(first.jsonRelativePath != second.jsonRelativePath)
        assertTrue(first.receiptImageRelativePath != second.receiptImageRelativePath)
        assertEquals(listOf("confirmed", "manual"), published.map { it.extractionStatus })
        assertTrue(published.all { it.toVersion2Json().contains("\"sourceDeviceName\":\"Hugo's Pixel\"") })
    }

    @Test
    fun persistsStableIdentityAndReusesImageAndPathsOnRetry() = runTest {
        val repository = FakeRepository()
        var prepareCalls = 0
        var publishCalls = 0
        val published = mutableListOf<ReceiptExportRecord>()
        val controller = ReceiptExportController(
            repository = repository,
            imagePreparer = ReceiptExportImagePreparer { _, _ ->
                prepareCalls++
                PreparedReceiptImage("local.jpg", 3)
            },
            imageReader = ReceiptExportImageReader { byteArrayOf(1, 2, 3) },
            publisher = ReceiptExportPublisher { record, _ ->
                published += record
                if (publishCalls++ == 0) throw ReceiptExportException(ReceiptExportFailure.NETWORK_UNAVAILABLE)
            },
            sourceDeviceId = { "device-1" },
            sourceDeviceName = { "Hugo's Pixel" },
            now = { ZonedDateTime.of(2026, 9, 6, 14, 5, 7, 0, ZoneId.of("America/Vancouver")) },
            newExpenseId = { "018f6b3e-1111-2222-3333-444444444444" },
        )

        val failed = controller.start(validReview())
        val succeeded = controller.retry(failed.expenseId)

        assertEquals(ReceiptExportStatus.FAILED, failed.status)
        assertEquals(ReceiptExportStatus.EXPORTED, succeeded.status)
        assertEquals(1, prepareCalls)
        assertEquals(2, published.size)
        assertEquals(published[0].expenseId, published[1].expenseId)
        assertEquals(published[0].receiptImageRelativePath, published[1].receiptImageRelativePath)
        assertEquals(published[0].jsonRelativePath, published[1].jsonRelativePath)
        assertEquals("Documents/2_Others/Expenses_finance/receipt_images/2026-09/20260906_140507_receipt_018f6b3e.jpg", succeeded.receiptImageRelativePath)
        assertEquals("Documents/2_Others/Expenses_finance/logs/expense_20260906_140507_018f6b3e.json", succeeded.jsonRelativePath)
        assertEquals("Hugo's Pixel", succeeded.sourceDeviceName)
        assertEquals(published[0].sourceDeviceName, published[1].sourceDeviceName)
    }

    @Test
    fun version2JsonContainsRequiredImageCorrelation() {
        val record = sampleRecord()
        val json = record.toVersion2Json()

        assertTrue(json.contains("\"schemaVersion\":2"))
        assertTrue(json.contains("\"receiptImageRelativePath\":\"${record.receiptImageRelativePath}\""))
        assertTrue(json.contains("\"merchantName\":\"Shop \\\"A\\\"\""))
        assertTrue(json.contains("\"sourceDeviceName\":\"Android device\""))
    }

    @Test
    fun savesIdentityBeforePreparingImage() = runTest {
        val repository = FakeRepository()
        val controller = ReceiptExportController(
            repository = repository,
            imagePreparer = ReceiptExportImagePreparer { _, _ ->
                assertNotNull(repository.value)
                throw java.io.IOException("disk full")
            },
            imageReader = ReceiptExportImageReader { error("unused") },
            publisher = ReceiptExportPublisher { _, _ -> error("unused") },
            sourceDeviceId = { "device" },
            now = { ZonedDateTime.parse("2026-09-06T14:05:07-07:00") },
            newExpenseId = { "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee" },
        )

        val result = controller.start(validReview())

        assertEquals(ReceiptExportFailure.INSUFFICIENT_STORAGE, result.failure)
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", repository.value?.expenseId)
    }

    @Test
    fun lowConfidenceReviewIsConfirmedInSavedJsonAndRemainsSoOnRetry() = runTest {
        val repository = FakeRepository()
        var publishes = 0
        val controller = ReceiptExportController(
            repository = repository,
            imagePreparer = ReceiptExportImagePreparer { _, _ -> PreparedReceiptImage("local.jpg", 3) },
            imageReader = ReceiptExportImageReader { byteArrayOf(1, 2, 3) },
            publisher = ReceiptExportPublisher { _, _ ->
                if (publishes++ == 0) throw ReceiptExportException(ReceiptExportFailure.NETWORK_UNAVAILABLE)
            },
            sourceDeviceId = { "device" },
            now = { ZonedDateTime.parse("2026-09-06T14:05:07-07:00") },
            newExpenseId = { "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee" },
        )

        val failed = controller.start(validReview().copy(extractionStatus = "low_confidence"))
        val succeeded = controller.retry(failed.expenseId)

        assertEquals("confirmed", failed.extractionStatus)
        assertEquals("confirmed", succeeded.extractionStatus)
        assertTrue(succeeded.toVersion2Json().contains("\"extractionStatus\":\"confirmed\""))
    }

    @Test
    fun typedExportsNeverTouchImagesAndRetryTheImmutableReviewedPayload() = runTest {
        val repository = FakeRepository()
        val published = mutableListOf<ReceiptExportRecord>()
        val controller = ReceiptExportController(
            repository, ReceiptExportImagePreparer { _, _ -> error("Typed export prepared an image") },
            ReceiptExportImageReader { error("Typed export read an image") },
            ReceiptExportPublisher { record, bytes ->
                assertNull(bytes)
                published += record
                if (published.size == 1) throw ReceiptExportException(ReceiptExportFailure.NETWORK_UNAVAILABLE)
            }, { "device" },
            now = { ZonedDateTime.parse("2026-09-06T14:05:07-07:00") },
            newExpenseId = { "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee" },
        )
        val notes = "  Original \"notes\"\nwith tab\tand space  "
        val failed = controller.start(validReview().copy(sourceType = ReceiptSourceType.TYPED,
            sourceImageUri = "content://detached-image", notes = notes))
        val retried = controller.retry(failed.expenseId)
        assertEquals(3, retried.schemaVersion)
        assertEquals("manual", retried.extractionStatus)
        assertEquals(notes, retried.notes)
        assertNull(retried.sourceImageUri)
        assertNull(retried.receiptImageRelativePath)
        assertEquals(published[0], published[1])
        val json = retried.toHandoffJson()
        assertTrue(json.contains("\"inputSource\":\"typed\""))
        assertTrue(json.contains("\"hasReceiptImage\":false"))
        assertTrue(json.contains("\"receiptImageRelativePath\":null"))
        assertEquals(notes, org.json.JSONObject(json).getString("notes"))
        assertFailsWith<IllegalArgumentException> { retried.toVersion2Json() }
    }

    @Test
    fun imageProvenanceWithoutAnImageIsRejectedRatherThanDowngraded() = runTest {
        val controller = ReceiptExportController(FakeRepository(),
            ReceiptExportImagePreparer { _, _ -> error("unused") },
            ReceiptExportImageReader { error("unused") },
            ReceiptExportPublisher { _, _ -> error("unused") }, { "device" })
        assertFailsWith<IllegalArgumentException> { controller.start(validReview().copy(sourceImageUri = "")) }
        assertFailsWith<IllegalArgumentException> { sampleRecord().copy(schemaVersion = 3).toHandoffJson() }
    }

    @Test
    fun freeTextExtractionExportsNormalizedVersion3WithExactOriginalNotes() = runTest {
        val notes="  Walmrat, Oct 7 2026, 15+50 Canadian dollars\n"
        val draft=com.hugo.smartexpense.extraction.TypedExpenseDraft(currency="",notes=notes)
        val client=object:com.hugo.smartexpense.extraction.ReceiptModelClient {
            override fun supportsDirectImageInput()=false
            override fun extractFromReceiptImage(receiptImage:com.hugo.smartexpense.extraction.ReceiptImage,prompt:String):String=error("No image")
            override fun extractFromReceiptText(receiptText:String,prompt:String):String {
                assertEquals(notes,org.json.JSONObject(receiptText).getString("notes"))
                return """{"receipts":[{"merchantName":"Walmart","receiptDate":"2026-10-07","totalAmount":65,"currency":"CAD","extractionStatus":"low_confidence","confidence":0.9,"merchantLocation":null,"issues":[],"suggestedCorrections":["Walmrat -> Walmart"]}]}"""
            }
        }
        val normalized=(com.hugo.smartexpense.extraction.ReceiptExtractionPipeline(client).reviewTypedExpense(draft) as com.hugo.smartexpense.extraction.ReceiptExtractionPipelineResult.Success).result
        val repository=FakeRepository()
        val controller=ReceiptExportController(repository,
            ReceiptExportImagePreparer { _,_->error("No image preparation") }, ReceiptExportImageReader { error("No image read") },
            ReceiptExportPublisher { _,bytes->assertNull(bytes) }, { "device" })
        val record=controller.start(ReceiptReviewState(merchantName=normalized.merchantName,receiptDate=normalized.receiptDate.toString(),totalAmount=normalized.totalAmount.toPlainString(),currency=normalized.currency,notes=normalized.notes,sourceType=ReceiptSourceType.TYPED,manualEntryRequired=false,extractionStatus="low_confidence"))
        val json=org.json.JSONObject(record.toHandoffJson())
        assertEquals(3,json.getInt("schemaVersion"));assertEquals("manual",json.getString("extractionStatus"))
        assertEquals("Walmart",json.getString("merchantName"));assertEquals(65,json.getInt("totalAmount"))
        assertEquals("2026-10-07",json.getString("receiptDate"));assertEquals("CAD",json.getString("currency"))
        assertEquals(notes,json.getString("notes"));assertEquals(notes,draft.notes)
    }

    @Test
    fun invalidStructuredTypedValuesNeverPersistOrPublish() = runTest {
        val repository=FakeRepository()
        val controller=ReceiptExportController(repository,
            ReceiptExportImagePreparer { _,_->error("unused") }, ReceiptExportImageReader { error("unused") },
            ReceiptExportPublisher { _,_->error("Invalid review was published") }, { "device" })
        val typed=validReview().copy(sourceType=ReceiptSourceType.TYPED)
        listOf(typed.copy(totalAmount="15+50"),typed.copy(totalAmount="-1"),typed.copy(totalAmount="NaN"),
            typed.copy(receiptDate="Oct 7 2026"),typed.copy(receiptDate="2026-02-30"),typed.copy(merchantName=""),typed.copy(currency="Canadian dollars")).forEach { invalid->
            assertFailsWith<IllegalArgumentException> { controller.start(invalid) }
            assertNull(repository.value)
        }
    }

    @Test
    fun exportCancellationPropagatesAndRetainsRetryIdentity() = runTest {
        val repository = FakeRepository()
        val controller = ReceiptExportController(repository,
            ReceiptExportImagePreparer { _, _ -> PreparedReceiptImage("local.jpg", 3) },
            ReceiptExportImageReader { byteArrayOf(1, 2, 3) },
            ReceiptExportPublisher { _, _ -> throw CancellationException("cancelled") }, { "device" })
        var visibleIdentity: String? = null
        assertFailsWith<CancellationException> {
            controller.start(validReview()) { saved -> visibleIdentity = saved.expenseId }
        }
        assertEquals(repository.value!!.expenseId, visibleIdentity)
        assertEquals(ReceiptExportStatus.READY, repository.value!!.status)
        assertNull(repository.value!!.failure)
    }

    @Test
    fun restartingWithStableTransactionIdentityResumesSavedPayloadInsteadOfCreatingAnotherExport() = runTest {
        val repository = FakeRepository()
        var publishCount = 0
        val controller = ReceiptExportController(repository,
            ReceiptExportImagePreparer { _, _ -> PreparedReceiptImage("local.jpg", 3) },
            ReceiptExportImageReader { byteArrayOf(1, 2, 3) },
            ReceiptExportPublisher { _, _ -> if (publishCount++ == 0) throw CancellationException() },
            { "device" }, newExpenseId = { error("Stable transaction must supply identity") })
        val identity = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        assertFailsWith<CancellationException> { controller.startWithIdentity(validReview(), identity) }
        val saved = repository.value!!
        val resumed = controller.startWithIdentity(validReview().copy(merchantName = "Later edit"), identity)
        assertEquals(identity, resumed.expenseId)
        assertEquals(saved.merchantName, resumed.merchantName)
        assertEquals(saved.createdAt, resumed.createdAt)
        assertEquals(saved.jsonRelativePath, resumed.jsonRelativePath)
        assertEquals(ReceiptExportStatus.EXPORTED, resumed.status)
    }

    private fun validReview() = ReceiptReviewState(
        receiptDate = "2026-09-05",
        merchantName = "Shop A",
        totalAmount = "12.34",
        currency = "CAD",
        extractionStatus = "confirmed",
        sourceImageUri = "content://receipt/1",
    )

    private class FakeRepository : ReceiptExportRepository {
        var value: ReceiptExportRecord? = null
        override suspend fun save(record: ReceiptExportRecord) { value = record }
        override suspend fun get(expenseId: String): ReceiptExportRecord? = value?.takeIf { it.expenseId == expenseId }
    }
}

internal fun sampleRecord() = ReceiptExportRecord(
    expenseId = "018f6b3e-1111-2222-3333-444444444444",
    createdAt = "2026-09-06T14:05:07-07:00",
    sourceDeviceId = "device-1",
    receiptDate = "2026-09-05",
    merchantName = "Shop \"A\"",
    totalAmount = "12.34",
    currency = "CAD",
    extractionStatus = "confirmed",
    merchantLocation = null,
    originalImageFileName = null,
    sourceImageUri = "content://receipt/1",
    receiptImageRelativePath = "Documents/2_Others/Expenses_finance/receipt_images/2026-09/20260906_140507_receipt_018f6b3e.jpg",
    jsonRelativePath = "Documents/2_Others/Expenses_finance/logs/expense_20260906_140507_018f6b3e.json",
    temporaryJsonRelativePath = "Documents/2_Others/Expenses_finance/logs/expense_20260906_140507_018f6b3e.json.uploading",
    normalizedImageLocalPath = "local.jpg",
    status = ReceiptExportStatus.READY,
)
