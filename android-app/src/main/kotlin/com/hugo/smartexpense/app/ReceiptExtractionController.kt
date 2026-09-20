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
    fun extract(uri: String, reduceImageIfOversized: Boolean = true): ReceiptReviewState =
        extractAll(uri, reduceImageIfOversized).first()

    fun extractAll(uri: String, reduceImageIfOversized: Boolean = true): List<ReceiptReviewState> = try {
        when (val result = extractor.extract(imageLoader.load(uri, reduceImageIfOversized))) {
            is ReceiptExtractionPipelineResult.Success -> result.results.map { extracted -> ReceiptReviewState(
                receiptDate = extracted.receiptDate.toString(),
                merchantName = extracted.merchantName,
                totalAmount = extracted.totalAmount.toPlainString(),
                currency = extracted.currency,
                extractionStatus = extracted.extractionStatus.wireName(),
                confidence = extracted.confidence?.toPlainString().orEmpty(),
                merchantLocation = extracted.merchantLocation.orEmpty(),
                rawModelOutput = extracted.rawModelOutput,
                message = "Receipt extracted using ${result.mode.name.lowercase().replace('_', ' ')}. Review and correct every field before export.",
                manualEntryRequired = false,
            ) }

            is ReceiptExtractionPipelineResult.Failed -> listOf(ReceiptReviewState.manual(
                result.errors.joinToString(separator = " ").ifBlank { "The receipt output was invalid." },
                rawModelOutput = result.attempts.lastOrNull()?.parseResult?.rawOutput().orEmpty(),
            ))
        }
    } catch (error: RemoteProviderException) {
        listOf(ReceiptReviewState.manual(
            error.message ?: "The remote provider failed.",
            rawModelOutput = error.rawResponseBody?.takeIf(String::isNotBlank)
                ?: error.cause?.message?.takeIf(String::isNotBlank)
                ?: error.message.orEmpty(),
        ))
    } catch (error: Exception) {
        listOf(ReceiptReviewState.manual(
            error.message ?: "The receipt image could not be processed.",
            rawModelOutput = error.message.orEmpty(),
        ))
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
    val exportJsonPreview: String? = null,
) {
    fun confirmedForExport(): ReceiptReviewState =
        if (extractionStatus == "low_confidence") copy(extractionStatus = "confirmed") else this

    fun withUserEdits(edited: ReceiptReviewState): ReceiptReviewState {
        val fieldsChanged = receiptDate != edited.receiptDate ||
            merchantName != edited.merchantName ||
            totalAmount != edited.totalAmount ||
            currency != edited.currency ||
            merchantLocation != edited.merchantLocation
        return edited.copy(
            extractionStatus = if (fieldsChanged) "manual" else extractionStatus,
        )
    }

    companion object {
        fun manual(message: String, rawModelOutput: String = "") = ReceiptReviewState(
            message = "$message Enter the receipt details manually, or choose another image and retry.",
            rawModelOutput = rawModelOutput,
            manualEntryRequired = true,
        )
    }
}
