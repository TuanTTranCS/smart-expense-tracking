package com.hugo.smartexpense.app.receiptexport

import com.hugo.smartexpense.app.ReceiptReviewState
import kotlinx.coroutines.test.runTest
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReceiptExportControllerTest {
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
    }

    @Test
    fun version2JsonContainsRequiredImageCorrelation() {
        val record = sampleRecord()
        val json = record.toVersion2Json()

        assertTrue(json.contains("\"schemaVersion\":2"))
        assertTrue(json.contains("\"receiptImageRelativePath\":\"${record.receiptImageRelativePath}\""))
        assertTrue(json.contains("\"merchantName\":\"Shop \\\"A\\\"\""))
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
