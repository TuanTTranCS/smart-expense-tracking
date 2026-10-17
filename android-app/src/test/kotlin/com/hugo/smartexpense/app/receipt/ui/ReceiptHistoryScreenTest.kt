package com.hugo.smartexpense.app.receipt.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.hugo.smartexpense.app.receiptexport.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReceiptHistoryScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun historyShowsOfflineLegacyDateAndLocalDeleteUndo() {
        val record = sampleRecord().copy(status = ReceiptExportStatus.EXPORTED)
        val state = mutableStateOf(ReceiptWorkflowUiState(history = listOf(record)))
        var deletes = 0; var undos = 0
        compose.setContent { MaterialTheme { ReceiptHistoryScreen(state.value, {}, {}, {}, {}, {
            deletes++; state.value = state.value.copy(history = emptyList(), historyUndoId = it)
        }, { undos++; state.value = state.value.copy(history = listOf(record), historyUndoId = null) }, {}, {}, {}) } }
        compose.onNodeWithText("Created: ${record.createdAt} (legacy export time unavailable)").performScrollTo().assertExists()
        compose.onNodeWithText("Processing: not checked").performScrollTo().assertExists()
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        compose.onNodeWithText("No exported expenses yet.").assertExists()
        compose.onNodeWithText("Undo delete").performClick()
        compose.runOnIdle { assertEquals(1, deletes); assertEquals(1, undos) }
    }
    @Test fun busyPublicationDisablesDeleteAndValidationBlocksReexport() {
        val record = sampleRecord().copy(status = ReceiptExportStatus.EXPORTED)
        val state = mutableStateOf(ReceiptWorkflowUiState(history = listOf(record), historyBusy = true))
        compose.setContent { MaterialTheme { ReceiptHistoryScreen(state.value, {}, {}, {}, {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("Delete").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(historyBusy = false, historyEditingId = record.expenseId,
            historyDraft = ReceiptCorrectionDraft.from(record).review().copy(receiptDate = "2026-02-30")) }
        compose.onNodeWithText("Re-export").performScrollTo().assertIsNotEnabled()
    }
    @Test fun publicationRequiresExplicitConfirmation() {
        val record = sampleRecord().copy(status = ReceiptExportStatus.EXPORTED)
        val draft = ReceiptCorrectionDraft.from(record).review()
        var publications = 0
        compose.setContent { MaterialTheme { ReceiptHistoryScreen(ReceiptWorkflowUiState(history = listOf(record),
            historyEditingId = record.expenseId, historyDraft = draft), {}, {}, {}, {}, {}, {}, {}, {}, { publications++ }) } }
        compose.onNodeWithText("Re-export").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, publications) }
        compose.onNodeWithText("Confirm re-export").performClick()
        compose.runOnIdle { assertEquals(1, publications) }
    }
}
