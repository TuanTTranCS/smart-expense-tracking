package com.hugo.smartexpense.app

import com.hugo.smartexpense.extraction.ExtractionStatus
import com.hugo.smartexpense.extraction.ParseResult
import com.hugo.smartexpense.extraction.ReceiptExtractionAttempt
import com.hugo.smartexpense.extraction.ReceiptExtractionMode
import com.hugo.smartexpense.extraction.ReceiptExtractionPipelineResult
import com.hugo.smartexpense.extraction.ReceiptExtractionResult
import com.hugo.smartexpense.extraction.RemoteProviderException
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReceiptExtractionControllerTest {
    private val loader = ReceiptImageLoader(
        FakeSource(byteArrayOf(9, 8, 7)),
    )

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
                result = extracted,
                mode = ReceiptExtractionMode.DIRECT_IMAGE,
                attempts = listOf(ReceiptExtractionAttempt(ReceiptExtractionMode.DIRECT_IMAGE, ParseResult.Valid(extracted))),
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
    }
}

private class FakeSource(private val bytes: ByteArray) : ReceiptImageContentSource {
    override fun displayName(uri: String): String = "receipt.jpg"
    override fun mimeType(uri: String): String = "image/jpeg"
    override fun readBytes(uri: String): ByteArray = bytes
}
