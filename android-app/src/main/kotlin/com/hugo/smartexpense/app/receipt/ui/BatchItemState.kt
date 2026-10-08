package com.hugo.smartexpense.app.receipt.ui

import com.hugo.smartexpense.app.ReceiptReviewState
import com.hugo.smartexpense.extraction.ReceiptImage
import java.util.UUID

const val MAX_BATCH_ITEMS = 20

data class ManualExpenseDraft(
    val merchant: String = "", val amount: String = "", val currency: String = "CAD",
    val date: String = "", val notes: String = "",
) {
    fun errors(): List<String> = com.hugo.smartexpense.extraction.TypedExpenseDraft(merchant, amount, currency, date, notes).validationErrors().values.toList()
}
enum class BatchItemStatus { EMPTY, PREPARING, READY, QUEUED, PROCESSING, SUCCEEDED, FAILED, CANCELLED }
data class BatchTransaction(val id: String = UUID.randomUUID().toString(), val revision: Long, val review: ReceiptReviewState)
data class BatchItemState(
    val itemId: String = UUID.randomUUID().toString(), val sourceRevision: Long = 0,
    val sourceUri: String? = null, val preparedUri: String? = null, val mimeType: String = "image/jpeg",
    val image: ReceiptImage? = null, val reduceOversizedImages: Boolean = true, val noImage: Boolean = false, val draft: ManualExpenseDraft = ManualExpenseDraft(),
    val status: BatchItemStatus = BatchItemStatus.EMPTY, val transactions: List<BatchTransaction> = emptyList(),
    val expanded: Boolean = true, val error: String? = null, val rawFailureOutput: String = "", val edited: Boolean = false,
) {
    val immutable get() = transactions.any { it.review.exportExpenseId != null }
    val eligible get() = status in listOf(BatchItemStatus.READY, BatchItemStatus.CANCELLED) && (noImage || preparedUri != null || image != null)
}
interface PreparedBatchStore {
    suspend fun load(): List<BatchItemState>
    suspend fun save(items: List<BatchItemState>)
    suspend fun stage(itemId: String, image: ReceiptImage): String
    suspend fun read(uri: String, mimeType: String): ReceiptImage
    suspend fun release(uri: String) {}
    suspend fun protect(uri: String) {}
    suspend fun unprotect(uri: String) {}
    suspend fun thumbnail(uri: String, mimeType: String): ReceiptImage = read(uri, mimeType)
}

class BatchExtractionFailure(message: String, val rawOutput: String = "") : IllegalStateException(message)
