package com.hugo.smartexpense.app.receipt.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hugo.smartexpense.app.ReceiptReviewState
import com.hugo.smartexpense.app.receiptexport.ReceiptExportFailure
import com.hugo.smartexpense.app.receiptexport.ReceiptExportRecord
import com.hugo.smartexpense.app.receiptexport.ReceiptExportStatus
import com.hugo.smartexpense.app.settings.data.AppSettingsRepository
import com.hugo.smartexpense.extraction.ModelProfile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReceiptWorkflowUiState(
    val extracting: Boolean = false,
    val exporting: Boolean = false,
    val review: ReceiptReviewState? = null,
)

class ReceiptWorkflowViewModel(
    private val settingsRepository: AppSettingsRepository,
    private val extractReceipt: suspend (
        uri: String,
        reduceOversizedImages: Boolean,
        remoteProfileSnapshot: ModelProfile?,
    ) -> ReceiptReviewState,
    private val startExport: suspend (ReceiptReviewState) -> ReceiptExportRecord,
    private val retryExport: suspend (String) -> ReceiptExportRecord,
    private val operationDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(ReceiptWorkflowUiState())
    val uiState: StateFlow<ReceiptWorkflowUiState> = mutableUiState.asStateFlow()

    fun importReceipt(uri: String, remoteProfileSnapshot: ModelProfile?) {
        if (uri.isBlank() || mutableUiState.value.extracting || mutableUiState.value.exporting) return
        val reduceSnapshot = settingsRepository.settings.value.reduceOversizedImages
        mutableUiState.value = mutableUiState.value.copy(extracting = true, review = null)
        viewModelScope.launch {
            val result = runCatching {
                withContext(operationDispatcher) { extractReceipt(uri, reduceSnapshot, remoteProfileSnapshot) }
            }.getOrElse { ReceiptReviewState.manual(it.message ?: "The receipt could not be processed.") }
            mutableUiState.value = mutableUiState.value.copy(
                extracting = false,
                review = result.copy(sourceImageUri = uri),
            )
        }
    }

    fun updateReview(review: ReceiptReviewState) {
        if (!mutableUiState.value.exporting) mutableUiState.value = mutableUiState.value.copy(review = review)
    }

    fun export() {
        val reviewSnapshot = mutableUiState.value.review ?: return
        if (mutableUiState.value.exporting || reviewSnapshot.exportComplete) return
        mutableUiState.value = mutableUiState.value.copy(exporting = true)
        viewModelScope.launch {
            val result = runCatching {
                withContext(operationDispatcher) {
                    reviewSnapshot.exportExpenseId?.let { retryExport(it) } ?: startExport(reviewSnapshot)
                }
            }
            mutableUiState.value = mutableUiState.value.copy(
                exporting = false,
                review = result.fold(
                    onSuccess = { reviewSnapshot.withExportResult(it) },
                    onFailure = { reviewSnapshot.copy(message = it.message ?: "The receipt could not be exported.") },
                ),
            )
        }
    }

    private fun ReceiptReviewState.withExportResult(record: ReceiptExportRecord): ReceiptReviewState = copy(
        exportExpenseId = record.expenseId,
        exportComplete = record.status == ReceiptExportStatus.EXPORTED,
        message = if (record.status == ReceiptExportStatus.EXPORTED) {
            "Receipt image and handoff JSON exported to OneDrive."
        } else when (record.failure) {
            ReceiptExportFailure.RECONNECT_REQUIRED -> "Your Microsoft session needs attention. Reconnect, then retry this export."
            ReceiptExportFailure.PERMISSION_DENIED -> "OneDrive access was denied. Reconnect and approve file access, then retry."
            ReceiptExportFailure.RATE_LIMITED -> record.retryAfterSeconds?.let { "Microsoft Graph is busy. Retry in $it seconds." }
                ?: "Microsoft Graph is busy. Wait, then retry."
            ReceiptExportFailure.NETWORK_UNAVAILABLE -> "OneDrive could not be reached. Check the network, then retry."
            ReceiptExportFailure.INSUFFICIENT_STORAGE -> "The receipt could not be stored. Free device or OneDrive space, then retry."
            ReceiptExportFailure.PATH_CONFLICT -> "A OneDrive item conflicts with the export path. Check the expense folders, then retry."
            ReceiptExportFailure.INVALID_RECEIPT -> "Check the receipt fields and image, then retry."
            ReceiptExportFailure.SERVICE_FAILURE, null -> "Microsoft Graph could not complete the export. Try again later."
        },
    )

    class Factory(private val create: () -> ReceiptWorkflowViewModel) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }
}
