package com.hugo.smartexpense.extraction

class ReceiptExtractionPipeline(
    private val modelClient: ReceiptModelClient,
    private val parser: ReceiptExtractionParser = ReceiptExtractionParser(),
    private val ocrClient: OcrClient? = null,
) {
    fun extract(receiptImage: ReceiptImage): ReceiptExtractionPipelineResult {
        val attempts = mutableListOf<ReceiptExtractionAttempt>()

        if (modelClient.supportsDirectImageInput()) {
            val rawOutput = modelClient.extractFromReceiptImage(receiptImage)
            val parseResult = parser.parse(rawOutput)
            attempts += ReceiptExtractionAttempt(ReceiptExtractionMode.DIRECT_IMAGE, parseResult)
            if (parseResult is ParseResult.Valid) {
                return ReceiptExtractionPipelineResult.Success(parseResult.value, ReceiptExtractionMode.DIRECT_IMAGE, attempts)
            }
        }

        val availableOcrClient = ocrClient
            ?: return ReceiptExtractionPipelineResult.Failed(
                errors = attempts.flatMap { it.errors() }.ifEmpty {
                    listOf("Direct image extraction is unavailable and OCR fallback is not configured.")
                },
                attempts = attempts,
            )

        val receiptText = availableOcrClient.extractText(receiptImage)
        val rawOutput = modelClient.extractFromReceiptText(receiptText)
        val parseResult = parser.parse(rawOutput)
        attempts += ReceiptExtractionAttempt(ReceiptExtractionMode.OCR_TEXT, parseResult)

        return when (parseResult) {
            is ParseResult.Valid -> ReceiptExtractionPipelineResult.Success(
                parseResult.value,
                ReceiptExtractionMode.OCR_TEXT,
                attempts,
            )
            is ParseResult.Invalid -> ReceiptExtractionPipelineResult.Failed(parseResult.errors, attempts)
        }
    }
}

data class ReceiptExtractionAttempt(
    val mode: ReceiptExtractionMode,
    val parseResult: ParseResult,
) {
    fun errors(): List<String> = when (parseResult) {
        is ParseResult.Valid -> emptyList()
        is ParseResult.Invalid -> parseResult.errors
    }
}

sealed class ReceiptExtractionPipelineResult {
    data class Success(
        val result: ReceiptExtractionResult,
        val mode: ReceiptExtractionMode,
        val attempts: List<ReceiptExtractionAttempt>,
    ) : ReceiptExtractionPipelineResult()

    data class Failed(
        val errors: List<String>,
        val attempts: List<ReceiptExtractionAttempt>,
    ) : ReceiptExtractionPipelineResult()
}
