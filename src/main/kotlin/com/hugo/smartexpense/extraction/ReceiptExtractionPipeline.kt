package com.hugo.smartexpense.extraction

class ReceiptExtractionPipeline(
    private val modelClient: ReceiptModelClient,
    private val parser: ReceiptExtractionParser = ReceiptExtractionParser(),
    private val ocrClient: OcrClient? = null,
) {
    fun reviewTypedExpense(draft: TypedExpenseDraft): ReceiptExtractionPipelineResult {
        val errors = draft.validationErrors().values.toList()
        if (errors.isNotEmpty()) return ReceiptExtractionPipelineResult.Failed(errors, emptyList())
        val parsed = parser.parse(modelClient.reviewTypedExpense(draft), requireReviewMetadata = true)
        val attempts = listOf(ReceiptExtractionAttempt(ReceiptExtractionMode.TYPED_TEXT, parsed))
        if (parsed is ParseResult.Invalid) return ReceiptExtractionPipelineResult.Failed(parsed.errors, attempts)
        parsed as ParseResult.Valid
        if (parsed.values.size != 1) return ReceiptExtractionPipelineResult.Failed(
            listOf("Typed input must produce exactly one expense."), attempts)
        val result = parsed.value
        if (result.issues.isNotEmpty() || result.extractionStatus == ExtractionStatus.FAILED)
            return ReceiptExtractionPipelineResult.Failed(
                result.issues.ifEmpty { listOf("Typed expense contains unresolved facts; correct the draft or enter validated results manually.") }, attempts)
        return ReceiptExtractionPipelineResult.Success(listOf(result.copy(
            notes = draft.notes,
            extractionStatus = if (result.suggestedCorrections.isNotEmpty()) ExtractionStatus.LOW_CONFIDENCE else result.extractionStatus,
        )), ReceiptExtractionMode.TYPED_TEXT, attempts)
    }

    fun extract(receiptImage: ReceiptImage): ReceiptExtractionPipelineResult {
        val attempts = mutableListOf<ReceiptExtractionAttempt>()

        if (modelClient.supportsDirectImageInput()) {
            val rawOutput = modelClient.extractFromReceiptImage(receiptImage)
            val parseResult = parser.parse(rawOutput)
            attempts += ReceiptExtractionAttempt(ReceiptExtractionMode.DIRECT_IMAGE, parseResult)
            if (parseResult is ParseResult.Valid) {
                return ReceiptExtractionPipelineResult.Success(parseResult.values, ReceiptExtractionMode.DIRECT_IMAGE, attempts)
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
                parseResult.values,
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
        val results: List<ReceiptExtractionResult>,
        val mode: ReceiptExtractionMode,
        val attempts: List<ReceiptExtractionAttempt>,
    ) : ReceiptExtractionPipelineResult() {
        val result: ReceiptExtractionResult get() = results.first()
    }

    data class Failed(
        val errors: List<String>,
        val attempts: List<ReceiptExtractionAttempt>,
    ) : ReceiptExtractionPipelineResult()
}
