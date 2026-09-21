package com.hugo.smartexpense.app

import com.hugo.smartexpense.extraction.ExtractionStatus
import com.hugo.smartexpense.extraction.ParseResult
import com.hugo.smartexpense.extraction.ReceiptExtractionAttempt
import com.hugo.smartexpense.extraction.ReceiptExtractionMode
import com.hugo.smartexpense.extraction.ReceiptExtractionPipelineResult
import com.hugo.smartexpense.extraction.ReceiptExtractionResult
import com.hugo.smartexpense.extraction.RemoteProviderException
import java.io.IOException
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReceiptExtractionControllerTest {
    private val loader = ReceiptImageLoader(
        FakeSource(byteArrayOf(9, 8, 7)),
    )

    @Test
    fun mapsTwoTransactionsToSeparateReviewStates() {
        val first = ReceiptExtractionResult(LocalDate.parse("2026-09-15"), "Shop A", BigDecimal("12.34"),
            "CAD", ExtractionStatus.CONFIRMED, BigDecimal("0.9"), null, "raw")
        val second = first.copy(merchantName = "Shop B", totalAmount = BigDecimal("56.78"))
        val controller = ReceiptExtractionController(loader) {
            ReceiptExtractionPipelineResult.Success(listOf(first, second), ReceiptExtractionMode.DIRECT_IMAGE,
                listOf(ReceiptExtractionAttempt(ReceiptExtractionMode.DIRECT_IMAGE, ParseResult.Valid(listOf(first, second)))))
        }
        val reviews = controller.extractAll("content://receipts/two")
        assertEquals(listOf("Shop A", "Shop B"), reviews.map { it.merchantName })
        assertEquals(listOf("12.34", "56.78"), reviews.map { it.totalAmount })
    }

    @Test
    fun returnsTheSameLoadedImageToPreviewAndExtractorBeforeExtraction() {
        val loadedImage = com.hugo.smartexpense.extraction.ReceiptImage("receipt.jpg", byteArrayOf(3, 2, 1), "image/jpeg")
        val events = mutableListOf<String>()
        var callbackImage: com.hugo.smartexpense.extraction.ReceiptImage? = null
        var extractorImage: com.hugo.smartexpense.extraction.ReceiptImage? = null
        val controller = ReceiptExtractionController(ReceiptImageLoader(FakeSource(loadedImage.bytes))) { image ->
            extractorImage = image
            events += "extract"
            ReceiptExtractionPipelineResult.Failed(emptyList(), emptyList())
        }

        val result = controller.extractAllWithImage("content://receipts/one") { image ->
            callbackImage = image
            events += "loaded"
        }

        assertSame(result.image, callbackImage)
        assertSame(callbackImage, extractorImage)
        assertEquals(listOf("loaded", "extract"), events)
    }

    @Test
    fun passesTheProcessedOrOriginalImageToBothPreviewAndExtractor() {
        val oversized = ByteArray(RECEIPT_IMAGE_SIZE_THRESHOLD_BYTES + 1) { 1 }
        val reduced = byteArrayOf(8, 7, 6)
        listOf(
            ImageLoadCase(oversized, true, reduced, true),
            ImageLoadCase(byteArrayOf(1, 2), true, byteArrayOf(1, 2), false),
            ImageLoadCase(oversized, false, oversized, false),
        ).forEach { case ->
            var reductions = 0
            var callbackImage: com.hugo.smartexpense.extraction.ReceiptImage? = null
            var extractorImage: com.hugo.smartexpense.extraction.ReceiptImage? = null
            val controller = ReceiptExtractionController(
                imageLoader = ReceiptImageLoader(
                    source = FakeSource(case.sourceBytes),
                    sizeReducer = ReceiptImageSizeReducer { _, _ -> reductions += 1; ReducedReceiptImage(reduced, "image/jpeg") },
                ),
                extractor = ReceiptExtractor { image ->
                    extractorImage = image
                    ReceiptExtractionPipelineResult.Failed(emptyList(), emptyList())
                },
            )

            val result = controller.extractAllWithImage("content://receipts/test", case.reduceEnabled) {
                callbackImage = it
            }

            assertSame(result.image, callbackImage)
            assertSame(callbackImage, extractorImage)
            assertEquals(case.expectedBytes.toList(), result.image?.bytes?.toList())
            assertEquals(if (case.shouldReduce) 1 else 0, reductions)
            if (case.shouldReduce) assertTrue(requireNotNull(result.image).bytes.size < RECEIPT_IMAGE_SIZE_THRESHOLD_BYTES)
        }
    }

    @Test
    fun keepsLoadedImageWhenExtractionFailsAndHasNoImageWhenLoadingFails() {
        val controller = ReceiptExtractionController(ReceiptImageLoader(FakeSource(byteArrayOf(1)))) {
            throw IllegalStateException("Extractor unavailable")
        }
        assertEquals(byteArrayOf(1).toList(), controller.extractAllWithImage("content://receipts/one").image?.bytes?.toList())

        val failingLoader = ReceiptImageLoader(object : ReceiptImageContentSource {
            override fun displayName(uri: String): String? = "receipt.jpg"
            override fun mimeType(uri: String): String? = "image/jpeg"
            override fun readBytes(uri: String): ByteArray = error("Cannot load")
        })
        assertNull(ReceiptExtractionController(failingLoader) { error("not reached") }
            .extractAllWithImage("content://receipts/failing").image)
    }

    @Test
    fun mapsSuccessfulDirectImageExtractionToEditableReviewState() {
        val rawOutput = """{"receiptDate":"2026-06-26"}"""
        val extracted = ReceiptExtractionResult(
            receiptDate = LocalDate.parse("2026-06-26"),
            merchantName = "Example Shop",
            totalAmount = BigDecimal("42.35"),
            currency = "CAD",
            extractionStatus = ExtractionStatus.CONFIRMED,
            confidence = BigDecimal("0.95"),
            merchantLocation = "Vancouver",
            rawModelOutput = rawOutput,
        )
        val controller = ReceiptExtractionController(loader) {
            ReceiptExtractionPipelineResult.Success(
                results = listOf(extracted),
                mode = ReceiptExtractionMode.DIRECT_IMAGE,
                attempts = listOf(ReceiptExtractionAttempt(ReceiptExtractionMode.DIRECT_IMAGE, ParseResult.Valid(listOf(extracted)))),
            )
        }

        val state = controller.extract("content://receipts/success")

        assertFalse(state.manualEntryRequired)
        assertEquals("2026-06-26", state.receiptDate)
        assertEquals("Example Shop", state.merchantName)
        assertEquals("42.35", state.totalAmount)
        assertEquals("0.95", state.confidence)
        assertEquals(rawOutput, state.rawModelOutput)
    }

    @Test
    fun malformedModelOutputKeepsRawOutputAndOffersManualEntry() {
        val invalid = ParseResult.Invalid(
            errors = listOf("merchantName is required."),
            rawOutput = "{malformed}",
        )
        val controller = ReceiptExtractionController(loader) {
            ReceiptExtractionPipelineResult.Failed(
                errors = invalid.errors,
                attempts = listOf(ReceiptExtractionAttempt(ReceiptExtractionMode.DIRECT_IMAGE, invalid)),
            )
        }

        val state = controller.extract("content://receipts/malformed")

        assertTrue(state.manualEntryRequired)
        assertTrue(state.message.contains("merchantName is required."))
        assertEquals("{malformed}", state.rawModelOutput)
    }

    @Test
    fun remoteFailureOffersARecoveryPathAndManualEntry() {
        val controller = ReceiptExtractionController(loader) {
            throw RemoteProviderException.RateLimited("LM Studio", "LM Studio rate limited the request.")
        }

        val state = controller.extract("content://receipts/rate-limited")

        assertTrue(state.manualEntryRequired)
        assertTrue(state.message.contains("rate limited"))
        assertTrue(state.message.contains("manually"))
        assertEquals("LM Studio rate limited the request.", state.rawModelOutput)
    }

    @Test
    fun remoteHttpFailureKeepsRawProviderErrorForDebugView() {
        val body = """{"error":{"message":"invalid JSON request","code":"bad_request"}}"""
        val controller = ReceiptExtractionController(loader) {
            throw RemoteProviderException.UnexpectedResponse("LM Studio", "invalid JSON request", body)
        }

        val state = controller.extract("content://receipts/bad-request")
        assertTrue(state.manualEntryRequired)
        assertEquals(body, state.rawModelOutput)
    }

    @Test
    fun transportFailureKeepsItsCauseForDebugView() {
        val controller = ReceiptExtractionController(loader) {
            throw RemoteProviderException.NetworkUnavailable(
                "OpenRouter", "Network is unavailable for OpenRouter.", IOException("connection refused"),
            )
        }

        val state = controller.extract("content://receipts/network-error")
        assertTrue(state.manualEntryRequired)
        assertEquals("connection refused", state.rawModelOutput)
    }

    @Test
    fun anyEditedReceiptFieldMarksReviewManualAndKeepsItManualAfterReverting() {
        val original = ReceiptReviewState(
            receiptDate = "2026-06-26", merchantName = "Shop", totalAmount = "42.35",
            currency = "CAD", merchantLocation = "Vancouver", extractionStatus = "low_confidence",
        )
        val edits = listOf(
            original.copy(receiptDate = "2026-06-27"),
            original.copy(merchantName = "Other shop"),
            original.copy(totalAmount = "43.35"),
            original.copy(currency = "USD"),
            original.copy(merchantLocation = "Burnaby"),
        )

        assertEquals("low_confidence", original.withUserEdits(original).extractionStatus)
        edits.forEach { edit ->
            val changed = original.withUserEdits(edit)
            assertEquals("manual", changed.extractionStatus)
            assertEquals("manual", changed.withUserEdits(original).confirmedForExport().extractionStatus)
        }
        assertEquals("confirmed", original.confirmedForExport().extractionStatus)
        assertEquals("manual", original.copy(extractionStatus = "confirmed")
            .withUserEdits(original.copy(merchantName = "Other shop")).extractionStatus)
    }
}

private data class ImageLoadCase(
    val sourceBytes: ByteArray,
    val reduceEnabled: Boolean,
    val expectedBytes: ByteArray,
    val shouldReduce: Boolean,
)

private class FakeSource(private val bytes: ByteArray) : ReceiptImageContentSource {
    override fun displayName(uri: String): String = "receipt.jpg"
    override fun mimeType(uri: String): String = "image/jpeg"
    override fun readBytes(uri: String): ByteArray = bytes
}
