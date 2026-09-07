package com.hugo.smartexpense.app

import com.hugo.smartexpense.extraction.ReceiptExtractionPipeline
import com.hugo.smartexpense.extraction.ReceiptExtractionPipelineResult
import com.hugo.smartexpense.extraction.ReceiptImage
import com.hugo.smartexpense.extraction.RemoteProviderException

fun interface ReceiptExtractor {
    fun extract(receiptImage: ReceiptImage): ReceiptExtractionPipelineResult
}

class PipelineReceiptExtractor(
    private val pipeline: ReceiptExtractionPipeline,
) : ReceiptExtractor {
    override fun extract(receiptImage: ReceiptImage): ReceiptExtractionPipelineResult = pipeline.extract(receiptImage)
}

class ReceiptExtractionController(
    private val imageLoader: ReceiptImageLoader,
    private val extractor: ReceiptExtractor,
) {
    fun extract(uri: String, reduceImageIfOversized: Boolean = true): ReceiptReviewState = try {
        when (val result = extractor.extract(imageLoader.load(uri, reduceImageIfOversized))) {
            is ReceiptExtractionPipelineResult.Success -> ReceiptReviewState(
                receiptDate = result.result.receiptDate.toString(),
                merchantName = result.result.merchantName,
                totalAmount = result.result.totalAmount.toPlainString(),
                currency = result.result.currency,
                extractionStatus = result.result.extractionStatus.wireName(),
                confidence = result.result.confidence?.toPlainString().orEmpty(),
                merchantLocation = result.result.merchantLocation.orEmpty(),
                rawModelOutput = result.result.rawModelOutput,
                message = "Receipt extracted using ${result.mode.name.lowercase().replace('_', ' ')}. Review and correct every field before export.",
                manualEntryRequired = false,
            )

            is ReceiptExtractionPipelineResult.Failed -> ReceiptReviewState.manual(
                result.errors.joinToString(separator = " ").ifBlank { "The receipt output was invalid." },
                rawModelOutput = result.attempts.lastOrNull()?.parseResult?.rawOutput().orEmpty(),
            )
        }
    } catch (error: RemoteProviderException) {
        ReceiptReviewState.manual(error.message ?: "The remote provider failed.")
    } catch (error: Exception) {
        ReceiptReviewState.manual(error.message ?: "The receipt image could not be processed.")
    }
}

private fun com.hugo.smartexpense.extraction.ParseResult.rawOutput(): String = when (this) {
    is com.hugo.smartexpense.extraction.ParseResult.Valid -> value.rawModelOutput
    is com.hugo.smartexpense.extraction.ParseResult.Invalid -> rawOutput
}

data class ReceiptReviewState(
    val receiptDate: String = "",
    val merchantName: String = "",
    val totalAmount: String = "",
    val currency: String = "CAD",
    val extractionStatus: String = "manual",
    val confidence: String = "",
    val merchantLocation: String = "",
    val rawModelOutput: String = "",
    val message: String = "",
    val manualEntryRequired: Boolean = true,
    val sourceImageUri: String = "",
    val exportExpenseId: String? = null,
    val exportComplete: Boolean = false,
) {
    companion object {
        fun manual(message: String, rawModelOutput: String = "") = ReceiptReviewState(
            message = "$message Enter the receipt details manually, or choose another image and retry.",
            rawModelOutput = rawModelOutput,
            manualEntryRequired = true,
        )
    }
}
