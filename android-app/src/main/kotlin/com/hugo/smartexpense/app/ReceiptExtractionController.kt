package com.hugo.smartexpense.app

import com.hugo.smartexpense.extraction.ReceiptExtractionPipeline
import com.hugo.smartexpense.extraction.ReceiptExtractionPipelineResult
import com.hugo.smartexpense.extraction.ReceiptImage
import com.hugo.smartexpense.extraction.RemoteProviderException
import kotlinx.coroutines.CancellationException

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
    data class OperationResult(
        val image: ReceiptImage?,
        val reviews: List<ReceiptReviewState>,
    )

    fun extract(uri: String, reduceImageIfOversized: Boolean = true): ReceiptReviewState =
        extractAll(uri, reduceImageIfOversized).first()

    fun extractAll(uri: String, reduceImageIfOversized: Boolean = true): List<ReceiptReviewState> =
        extractAllWithImage(uri, reduceImageIfOversized).reviews

    fun extractAllWithImage(
        uri: String,
        reduceImageIfOversized: Boolean = true,
        onImageLoaded: (ReceiptImage) -> Unit = {},
    ): OperationResult {
        var loadedImage: ReceiptImage? = null
        return try {
            val image = imageLoader.load(uri, reduceImageIfOversized)
            loadedImage = image
            onImageLoaded(image)
            extractLoadedImage(image)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            operationFailure(loadedImage, error)
        }
    }

    fun extractLoadedImage(image: ReceiptImage): OperationResult = try {
        OperationResult(image, when (val result = extractor.extract(image)) {
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
        })
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        operationFailure(image, error)
    }

    private fun operationFailure(image: ReceiptImage?, error: Exception): OperationResult =
        if (error is RemoteProviderException) {
            OperationResult(image, listOf(ReceiptReviewState.manual(
            error.message ?: "The remote provider failed.",
            rawModelOutput = error.rawResponseBody?.takeIf(String::isNotBlank)
                ?: error.cause?.message?.takeIf(String::isNotBlank)
                ?: error.message.orEmpty(),
            )))
        } else {
            OperationResult(image, listOf(ReceiptReviewState.manual(
            error.message ?: "The receipt image could not be processed.",
            rawModelOutput = error.message.orEmpty(),
            )))
        }
}

private fun com.hugo.smartexpense.extraction.ParseResult.rawOutput(): String = when (this) {
    is com.hugo.smartexpense.extraction.ParseResult.Valid -> value.rawModelOutput
    is com.hugo.smartexpense.extraction.ParseResult.Invalid -> rawOutput
}

enum class ReceiptSourceType { IMAGE, TYPED }

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
    val sourceType: ReceiptSourceType = ReceiptSourceType.IMAGE,
    val notes: String = "",
) {
    /** Validates structured review values, never the free-text source draft. */
    fun expenseValidationErrors(): List<String> = buildList {
        if (merchantName.isBlank()) add("Merchant is required.")
        if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(receiptDate.trim()) ||
            runCatching { java.time.LocalDate.parse(receiptDate.trim()) }.isFailure) {
            add("Enter a real receipt date using yyyy-MM-dd.")
        }
        if (totalAmount.trim().toBigDecimalOrNull()?.let { it >= java.math.BigDecimal.ZERO } != true) {
            add("Enter a finite, non-negative numeric total.")
        }
        if (!Regex("[A-Z]{3}").matches(currency.trim())) add("Enter an uppercase three-letter currency code.")
    }

    fun confirmedForExport(): ReceiptReviewState =
        if (extractionStatus == "low_confidence") copy(extractionStatus = "confirmed") else this

    fun withUserEdits(edited: ReceiptReviewState): ReceiptReviewState {
        val fieldsChanged = receiptDate != edited.receiptDate ||
            merchantName != edited.merchantName ||
            totalAmount != edited.totalAmount ||
            currency != edited.currency ||
            merchantLocation != edited.merchantLocation ||
            notes != edited.notes
        return edited.copy(
            extractionStatus = if (fieldsChanged) "manual" else extractionStatus,
        )
    }

    companion object {
        fun manual(message: String, rawModelOutput: String = "") = ReceiptReviewState(
            message = "$message Enter the receipt details manually, retry extraction for the selected image, or choose another image.",
            rawModelOutput = rawModelOutput,
            manualEntryRequired = true,
        )
    }
}
