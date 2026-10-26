package com.hugo.smartexpense.app.receipt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hugo.smartexpense.app.ReceiptReviewState
import com.hugo.smartexpense.app.ReceiptSourceType
import com.hugo.smartexpense.app.receiptexport.ReceiptExportRecord
import com.hugo.smartexpense.app.receiptexport.ReceiptHistoryImageResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReceiptHistoryScreen(
    state: ReceiptWorkflowUiState,
    onExpenses: () -> Unit,
    onSettings: () -> Unit,
    onRefresh: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onUndo: () -> Unit,
    onDraftChange: (ReceiptReviewState) -> Unit,
    onCancel: () -> Unit,
    onPublish: () -> Unit,
    onLoadImage: suspend (ReceiptExportRecord, Boolean, Boolean) -> ReceiptHistoryImageResult = { _, _, _ ->
        ReceiptHistoryImageResult.Unavailable("Receipt image is unavailable.")
    },
) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val thumbnailRevisions = remember(onLoadImage) { mutableStateMapOf<List<String?>, Int>() }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(top = 24.dp)) {
                Text("History", style = MaterialTheme.typography.headlineMedium)
                FlowRow { TextButton(onClick = { selectedId = null; onExpenses() }) { Text("Expenses") }; TextButton(onClick = { selectedId = null; onSettings() }) { Text("Settings") } }
                Text("Published expenses are saved here offline. Delete only removes local History.")
                state.historyMessage?.let { Text(it) }
                if (state.historyUndoId != null) TextButton(onClick = onUndo, enabled = !state.historyBusy) { Text("Undo delete") }
            }
        }
        if (state.history.isEmpty()) item { Text("No exported expenses yet.") }
        items(state.history, key = { it.expenseId }) { record ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.widthIn(min = 128.dp, max = 280.dp)) {
                            Text(record.merchantName, style = MaterialTheme.typography.titleLarge)
                            Text("${record.receiptDate}  |  ${record.currency} ${record.totalAmount}")
                        }
                        if (record.sourceType == ReceiptSourceType.TYPED) {
                            Text("No receipt image")
                        } else {
                            key(record.historyImageIdentity(), onLoadImage, thumbnailRevisions[record.historyImageIdentity()]) {
                                Box(Modifier.width(112.dp)) {
                                    HistoryReceiptThumbnail(record, onLoadImage) { selectedId = record.expenseId }
                                }
                            }
                        }
                    }
                    Text("Source: ${record.sourceType.name.lowercase()}")
                    Text(record.completedAt?.let { "Exported: $it" } ?: "Created: ${record.createdAt} (legacy export time unavailable)")
                    Text("Published to OneDrive" + if (record.lastCorrectionOperationId != null) "  |  Correction published; see processing status below" else "")
                    val observed = state.historyObservations[record.expenseId]
                    Text("Processing: ${(observed?.status ?: record.lastRemoteStatus)?.name?.lowercase()?.replace('_', ' ') ?: "not checked"}")
                    val checkedAt = observed?.checkedAt ?: record.lastRemoteCheckedAt
                    checkedAt?.let { Text("Checked: $it") }
                    (observed?.message ?: record.lastRemoteMessage)?.let { Text(it) }
                    FlowRow {
                        TextButton(onClick = { onRefresh(record.expenseId) }, enabled = !state.historyBusy) { Text("Refresh") }
                        TextButton(onClick = { onEdit(record.expenseId) }, enabled = !state.historyBusy && !state.exporting) { Text("Edit") }
                        TextButton(onClick = { onDelete(record.expenseId) }, enabled = !state.historyBusy && !state.exporting && state.historyEditingId != record.expenseId) { Text("Delete") }
                    }
                    if (state.historyEditingId == record.expenseId) state.historyDraft?.let { draft ->
                        Text("Edit exported expense", style = MaterialTheme.typography.titleMedium)
                        val editable = !state.historyBusy && !state.historyRetryOnly
                        ReviewField("Receipt date (yyyy-MM-dd)", draft.receiptDate, editable) { onDraftChange(draft.copy(receiptDate = it)) }
                        ReviewField("Merchant", draft.merchantName, editable) { onDraftChange(draft.copy(merchantName = it)) }
                        ReviewField("Total amount", draft.totalAmount, editable) { onDraftChange(draft.copy(totalAmount = it)) }
                        ReviewField("Currency", draft.currency, editable) { onDraftChange(draft.copy(currency = it.uppercase())) }
                        ReviewField("Merchant location", draft.merchantLocation, editable) { onDraftChange(draft.copy(merchantLocation = it)) }
                        ReviewField("Notes", draft.notes, editable) { onDraftChange(draft.copy(notes = it)) }
                        draft.expenseValidationErrors().forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                        FlowRow {
                            TextButton(onClick = onCancel, enabled = !state.historyBusy) { Text("Cancel") }
                            Button(onClick = { confirm = true }, enabled = !state.historyBusy && draft.expenseValidationErrors().isEmpty()) { Text(if (state.historyRetryOnly) "Retry re-export" else "Re-export") }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    state.history.firstOrNull { it.expenseId == selectedId && it.sourceType != ReceiptSourceType.TYPED }?.let { record ->
        // A replacement image/account/loader must never display the previous bitmap, even for one frame.
        key(record.historyImageIdentity(), onLoadImage) {
            HistoryReceiptViewer(record, onLoadImage, onAvailable = {
                val identity = record.historyImageIdentity()
                thumbnailRevisions[identity] = (thumbnailRevisions[identity] ?: 0) + 1
            }) { selectedId = null }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Confirm correction") },
        text = { Text("Publish these corrected fields to OneDrive for the existing expense? Excel processing is checked separately.") },
        confirmButton = { TextButton(onClick = { confirm = false; onPublish() }) { Text("Confirm re-export") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}

private fun ReceiptExportRecord.historyImageIdentity() =
    listOf(expenseId, normalizedImageLocalPath, receiptImageRelativePath, exportAccountId, exportDriveId)

private sealed interface HistoryImageDisplay {
    data object Loading : HistoryImageDisplay
    data class Available(val bitmap: ImageBitmap) : HistoryImageDisplay
    data class Unavailable(val message: String, val retryable: Boolean) : HistoryImageDisplay
}

@Composable
private fun historyImageDisplay(
    record: ReceiptExportRecord,
    load: suspend (ReceiptExportRecord, Boolean, Boolean) -> ReceiptHistoryImageResult,
    thumbnail: Boolean,
    attempt: Int = 0,
): State<HistoryImageDisplay> = produceState<HistoryImageDisplay>(HistoryImageDisplay.Loading, attempt) {
    value = HistoryImageDisplay.Loading
    value = try {
        when (val result = load(record, !thumbnail, thumbnail)) {
            is ReceiptHistoryImageResult.Available -> withContext(Dispatchers.Default) {
                decodeReceiptPreview(result.bytes, if (thumbnail) 256 else 4096)?.asImageBitmap()
                    ?.let(HistoryImageDisplay::Available)
                    ?: HistoryImageDisplay.Unavailable("Receipt image could not be decoded. Retry to recover the exported copy.", true)
            }
            is ReceiptHistoryImageResult.Unavailable -> HistoryImageDisplay.Unavailable(result.message, result.retryable)
            ReceiptHistoryImageResult.NoImage -> HistoryImageDisplay.Unavailable("No receipt image", false)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        HistoryImageDisplay.Unavailable("Receipt image is unavailable. Retry when connected.", true)
    }
}

@Composable
private fun HistoryReceiptThumbnail(
    record: ReceiptExportRecord,
    load: suspend (ReceiptExportRecord, Boolean, Boolean) -> ReceiptHistoryImageResult,
    onOpen: () -> Unit,
) {
    val display by historyImageDisplay(record, load, thumbnail = true)
    Surface(
        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).fillMaxWidth()
            .clickable(onClick = onOpen)
            .semantics { contentDescription = "View receipt for ${record.merchantName}, ${record.receiptDate}, ${record.currency} ${record.totalAmount}, ${record.expenseId}" },
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(8.dp)) {
            when (val current = display) {
                is HistoryImageDisplay.Available -> Image(current.bitmap, null,
                    Modifier.fillMaxWidth().height(96.dp).testTag("history-receipt-thumbnail:${record.expenseId}"), contentScale = ContentScale.Fit)
                HistoryImageDisplay.Loading -> Text("Loading receipt thumbnail")
                is HistoryImageDisplay.Unavailable -> Text("Receipt unavailable")
            }
            Text("View receipt", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun HistoryReceiptViewer(
    record: ReceiptExportRecord,
    load: suspend (ReceiptExportRecord, Boolean, Boolean) -> ReceiptHistoryImageResult,
    onAvailable: () -> Unit,
    onClose: () -> Unit,
) {
    var attempt by remember { mutableIntStateOf(0) }
    val display by historyImageDisplay(record, load, thumbnail = false, attempt)
    LaunchedEffect(display) {
        // A full-image open may have recovered the private cache. Refresh this card locally only.
        if (display is HistoryImageDisplay.Available) onAvailable()
    }
    SelectedReceiptImageViewer(
        image = (display as? HistoryImageDisplay.Available)?.bitmap,
        label = "Exported receipt copy",
        unavailableContent = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (val current = display) {
                    HistoryImageDisplay.Loading -> Text("Loading exported receipt copy")
                    is HistoryImageDisplay.Unavailable -> {
                        Text(current.message)
                        if (current.retryable) TextButton(onClick = { attempt++ }) { Text("Retry receipt image") }
                    }
                    is HistoryImageDisplay.Available -> Unit
                }
            }
        },
        onClose = onClose,
    )
}
