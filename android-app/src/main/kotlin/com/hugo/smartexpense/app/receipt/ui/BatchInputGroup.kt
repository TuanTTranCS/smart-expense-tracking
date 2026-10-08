package com.hugo.smartexpense.app.receipt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.hugo.smartexpense.app.ReceiptReviewState
import com.hugo.smartexpense.extraction.ReceiptImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BatchInputGroup(
    item: BatchItemState, ordinal: Int, running: Boolean, exporting: Boolean, canExport: Boolean, debug: Boolean,
    onChooseImages: () -> Unit, onRemove: (String) -> Unit, onExpand: (String) -> Unit,
    onSource: (String, Boolean, ManualExpenseDraft) -> Unit, onRetry: (String, Boolean) -> Unit,
    onReview: (String, String, ReceiptReviewState) -> Unit, onExport: (String, String) -> Unit,
    onManual: (String) -> Unit,
    loadThumbnail: suspend (BatchItemState) -> ReceiptImage?,
    loadImage: suspend (BatchItemState) -> ReceiptImage?,
    extractionAvailable: Boolean = true,
) {
    var confirmRetry by remember(item.itemId) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { onExpand(item.itemId) }, modifier = Modifier.weight(1f)) { Text("${if (item.expanded) "Collapse" else "Expand"} ${if (item.noImage) "Typed expense" else "Image"} $ordinal / ${item.status.name.lowercase()} / ${item.transactions.size} transactions") }
                TextButton(onClick = { onRemove(item.itemId) }, enabled = !exporting, modifier = Modifier.semantics { contentDescription = "Remove ${if (item.noImage) "item" else "image"} $ordinal" }) { Text("×") }
            }
            if (item.expanded) {
                val editable = !running && !exporting && !item.immutable
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(item.noImage, { onSource(item.itemId, it, item.draft) }, enabled = editable)
                    Text("No receipt image")
                }
                if (item.noImage) {
                    Text("Describe the expense in any field. All fields are optional; enter some expense text before extraction.", style = MaterialTheme.typography.bodySmall)
                    ReviewField("Merchant name", item.draft.merchant, editable) { onSource(item.itemId, true, item.draft.copy(merchant = it)) }
                    ReviewField("Final total paid/payable (including tax/tip)", item.draft.amount, editable) { onSource(item.itemId, true, item.draft.copy(amount = it)) }
                    ReviewField("Currency", item.draft.currency, editable) { onSource(item.itemId, true, item.draft.copy(currency = it)) }
                    ReviewField("Date", item.draft.date, editable) { onSource(item.itemId, true, item.draft.copy(date = it)) }
                    var dateOpen by remember { mutableStateOf(false) }
                    TextButton({ dateOpen = true }, enabled = editable) { Text("Choose date") }
                    if (dateOpen) {
                        val dateState = rememberDatePickerState()
                        DatePickerDialog(onDismissRequest = { dateOpen = false }, confirmButton = { TextButton({ dateState.selectedDateMillis?.let { millis -> onSource(item.itemId, true, item.draft.copy(date = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString())) }; dateOpen = false }) { Text("Use date") } }, dismissButton = { TextButton({ dateOpen = false }) { Text("Cancel") } }) { DatePicker(dateState) }
                    }
                    ReviewField("Notes (optional)", item.draft.notes, editable) { onSource(item.itemId, true, item.draft.copy(notes = it)) }
                } else {
                    if (item.preparedUri == null && item.image == null) OutlinedButton(onChooseImages, enabled = editable) { Text("Choose images") }
                    val image by produceState<ReceiptImage?>(null, item.preparedUri, item.image) { value = withContext(Dispatchers.IO) { runCatching { loadThumbnail(item) }.getOrNull() } }
                    SelectedReceiptImageReview(image, if (exporting) null else { { onRemove(item.itemId) } }, { loadImage(item) })
                }
                if (item.status == BatchItemStatus.FAILED && (item.noImage || item.preparedUri != null || item.image != null)) OutlinedButton({ onManual(item.itemId) }, enabled = editable) { Text("Enter manually") }
                if (debug && item.rawFailureOutput.isNotBlank()) DebugOutputField("Raw extraction error", item.rawFailureOutput)
                item.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (item.immutable) Text("Exported or export-started fields are immutable. Add a new item to replace this expense.")
                val hasSource = item.noImage || item.preparedUri != null || item.image != null
                val hasUnexportedResults = item.transactions.isEmpty() || item.transactions.any { it.review.exportExpenseId == null }
                val retry = item.transactions.isNotEmpty() || item.status in listOf(BatchItemStatus.FAILED, BatchItemStatus.SUCCEEDED, BatchItemStatus.CANCELLED, BatchItemStatus.QUEUED, BatchItemStatus.PROCESSING)
                val imageRetryAvailable = item.status in listOf(BatchItemStatus.FAILED, BatchItemStatus.SUCCEEDED, BatchItemStatus.CANCELLED)
                if (hasSource && hasUnexportedResults && (item.noImage || imageRetryAvailable)) {
                    OutlinedButton(
                        onClick = { if (item.edited) confirmRetry = true else onRetry(item.itemId, false) },
                        enabled = extractionAvailable && !running && !exporting && item.status != BatchItemStatus.PREPARING && (!item.noImage || item.draft.errors().isEmpty()),
                        modifier = Modifier.semantics { contentDescription = "${if (retry) "Retry extraction" else "Extract"} for ${if (item.noImage) "typed expense" else "image"} $ordinal" },
                    ) { Text(if (retry) "Retry extraction" else "Extract") }
                }
                if (item.noImage && item.transactions.isEmpty()) {
                    Button(onClick = {}, enabled = false) { Text("Confirm and export") }
                    Text("Extract and review this expense before confirming and exporting.", style = MaterialTheme.typography.bodySmall)
                }
                item.transactions.forEach { tx -> key(tx.id) {
                    if (tx.revision != item.sourceRevision) Text("Source changed. Reprocess before exporting.")
                    if (debug && tx.review.rawModelOutput.isNotBlank()) DebugOutputField("Raw extraction response", tx.review.rawModelOutput)
                    ReceiptReviewEditor(tx.review, exporting, canExport && !running && item.status == BatchItemStatus.SUCCEEDED && tx.revision == item.sourceRevision,
                        { onReview(item.itemId, tx.id, it) }, { onExport(item.itemId, tx.id) }, debug)
                } }
            }
        }
    }
    if (confirmRetry) AlertDialog(onDismissRequest = { confirmRetry = false }, title = { Text("Replace review edits?") }, text = { Text("A successful retry replaces your unexported edits. A failed retry keeps them.") }, confirmButton = { TextButton({ confirmRetry = false; onRetry(item.itemId, true) }) { Text("Replace and retry") } }, dismissButton = { TextButton({ confirmRetry = false }) { Text("Keep edits") } })
}
