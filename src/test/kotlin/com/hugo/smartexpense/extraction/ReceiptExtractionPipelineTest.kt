package com.hugo.smartexpense.extraction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReceiptExtractionPipelineTest {
    private val sampleImage = ReceiptImage(
        sourceName = "receipt.jpg",
        bytes = byteArrayOf(1, 2, 3),
        mimeType = "image/jpeg",
    )

    @Test
    fun usesDirectImageExtractionWhenModelSupportsIt() {
        val modelClient = FakeReceiptModelClient(
            supportsDirectImageInput = true,
            imageResponse = validResponse(merchantName = "Direct Image Shop"),
            textResponse = validResponse(merchantName = "OCR Shop"),
        )

        val result = ReceiptExtractionPipeline(modelClient = modelClient).extract(sampleImage)

        val success = assertIs<ReceiptExtractionPipelineResult.Success>(result)
        assertEquals(ReceiptExtractionMode.DIRECT_IMAGE, success.mode)
        assertEquals("Direct Image Shop", success.result.merchantName)
        assertEquals(1, modelClient.imageCalls)
        assertEquals(0, modelClient.textCalls)
    }

    @Test
    fun fallsBackToOcrWhenDirectImageOutputIsInvalid() {
        val modelClient = FakeReceiptModelClient(
            supportsDirectImageInput = true,
            imageResponse = "not json",
            textResponse = validResponse(merchantName = "OCR Recovery Shop"),
        )
        val ocrClient = FakeOcrClient("ocr receipt text")

        val result = ReceiptExtractionPipeline(
            modelClient = modelClient,
            ocrClient = ocrClient,
        ).extract(sampleImage)

        val success = assertIs<ReceiptExtractionPipelineResult.Success>(result)
        assertEquals(ReceiptExtractionMode.OCR_TEXT, success.mode)
        assertEquals("OCR Recovery Shop", success.result.merchantName)
        assertEquals(2, success.attempts.size)
        assertEquals(1, modelClient.imageCalls)
        assertEquals(1, modelClient.textCalls)
        assertEquals(1, ocrClient.calls)
    }

    @Test
    fun failsWhenNoValidPathProducesStructuredOutput() {
        val modelClient = FakeReceiptModelClient(
            supportsDirectImageInput = false,
            imageResponse = validResponse(),
            textResponse = """
                {
                  "merchantName": "",
                  "totalAmount": -1,
                  "currency": "cad",
                  "extractionStatus": "unknown"
                }
            """.trimIndent(),
        )
        val ocrClient = FakeOcrClient("ocr receipt text")

        val result = ReceiptExtractionPipeline(
            modelClient = modelClient,
            ocrClient = ocrClient,
        ).extract(sampleImage)

        val failed = assertIs<ReceiptExtractionPipelineResult.Failed>(result)
        assertTrue(failed.errors.any { it.contains("receiptDate") })
        assertTrue(failed.errors.any { it.contains("merchantName") })
        assertEquals(1, modelClient.textCalls)
        assertEquals(1, ocrClient.calls)
    }

    @Test
    fun failsWithoutFallbackWhenDirectImageIsUnavailable() {
        val modelClient = FakeReceiptModelClient(
            supportsDirectImageInput = false,
            imageResponse = validResponse(),
            textResponse = validResponse(),
        )

        val result = ReceiptExtractionPipeline(modelClient = modelClient).extract(sampleImage)

        val failed = assertIs<ReceiptExtractionPipelineResult.Failed>(result)
        assertTrue(failed.errors.any { it.contains("OCR fallback is not configured") })
    }

    private fun validResponse(merchantName: String = "Example Shop"): String = """
        {
          "receiptDate": "2026-06-26",
          "merchantName": "$merchantName",
          "totalAmount": 42.35,
          "currency": "CAD",
          "extractionStatus": "confirmed",
          "confidence": 0.94,
          "merchantLocation": "Vancouver BC"
        }
    """.trimIndent()
}

private class FakeReceiptModelClient(
    private val supportsDirectImageInput: Boolean,
    private val imageResponse: String,
    private val textResponse: String,
) : ReceiptModelClient {
    var imageCalls: Int = 0
        private set

    var textCalls: Int = 0
        private set

    override fun supportsDirectImageInput(): Boolean = supportsDirectImageInput

    override fun extractFromReceiptImage(receiptImage: ReceiptImage, prompt: String): String {
        imageCalls += 1
        return imageResponse
    }

    override fun extractFromReceiptText(receiptText: String, prompt: String): String {
        textCalls += 1
        return textResponse
    }
}

private class FakeOcrClient(
    private val extractedText: String,
) : OcrClient {
    var calls: Int = 0
        private set

    override fun extractText(receiptImage: ReceiptImage): String {
        calls += 1
        return extractedText
    }
}
