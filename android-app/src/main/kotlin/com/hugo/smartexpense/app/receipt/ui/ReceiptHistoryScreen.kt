package com.hugo.smartexpense.app.receipt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hugo.smartexpense.app.ReceiptReviewState

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
) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(top = 24.dp)) {
                Text("History", style = MaterialTheme.typography.headlineMedium)
                Row { TextButton(onClick = onExpenses) { Text("Expenses") }; TextButton(onClick = onSettings) { Text("Settings") } }
                Text("Published expenses are saved here offline. Delete only removes local History.")
                state.historyMessage?.let { Text(it) }
                if (state.historyUndoId != null) TextButton(onClick = onUndo, enabled = !state.historyBusy) { Text("Undo delete") }
            }
        }
        if (state.history.isEmpty()) item { Text("No exported expenses yet.") }
        items(state.history, key = { it.expenseId }) { record ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(record.merchantName, style = MaterialTheme.typography.titleLarge)
                    Text("${record.receiptDate}  |  ${record.currency} ${record.totalAmount}")
                    Text("Source: ${record.sourceType.name.lowercase()}")
                    Text(record.completedAt?.let { "Exported: $it" } ?: "Created: ${record.createdAt} (legacy export time unavailable)")
                    Text("Published to OneDrive" + if (record.lastCorrectionOperationId != null) "  |  Correction published; see processing status below" else "")
                    val observed = state.historyObservations[record.expenseId]
                    Text("Processing: ${(observed?.status ?: record.lastRemoteStatus)?.name?.lowercase()?.replace('_', ' ') ?: "not checked"}")
                    val checkedAt = observed?.checkedAt ?: record.lastRemoteCheckedAt
                    checkedAt?.let { Text("Checked: $it") }
                    (observed?.message ?: record.lastRemoteMessage)?.let { Text(it) }
                    Row {
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
                        Row {
                            TextButton(onClick = onCancel, enabled = !state.historyBusy) { Text("Cancel") }
                            Button(onClick = { confirm = true }, enabled = !state.historyBusy && draft.expenseValidationErrors().isEmpty()) { Text(if (state.historyRetryOnly) "Retry re-export" else "Re-export") }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Confirm correction") },
        text = { Text("Publish these corrected fields to OneDrive for the existing expense? Excel processing is checked separately.") },
        confirmButton = { TextButton(onClick = { confirm = false; onPublish() }) { Text("Confirm re-export") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}
