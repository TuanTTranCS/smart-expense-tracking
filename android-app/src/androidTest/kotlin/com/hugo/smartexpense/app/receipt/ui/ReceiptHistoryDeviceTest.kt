package com.hugo.smartexpense.app.receipt.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.hugo.smartexpense.app.receiptexport.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hugo.smartexpense.app.ReceiptSourceType
import org.junit.Assert.assertEquals
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import androidx.test.espresso.Espresso.pressBack

@RunWith(AndroidJUnit4::class)
class ReceiptHistoryDeviceTest {
    private fun sampleRecord() = ReceiptExportRecord(
        expenseId = "018f6b3e-1111-2222-3333-444444444444", createdAt = "2026-10-16T10:00:00Z",
        sourceDeviceId = "device", receiptDate = "2026-10-16", merchantName = "Shop", totalAmount = "12.00",
        currency = "CAD", extractionStatus = "manual", merchantLocation = null, originalImageFileName = null,
        sourceImageUri = null, receiptImageRelativePath = null, jsonRelativePath = "expense.json",
        temporaryJsonRelativePath = "expense.json.uploading", normalizedImageLocalPath = null,
        status = ReceiptExportStatus.EXPORTED, schemaVersion = 3, sourceType = ReceiptSourceType.TYPED)
    @get:Rule val compose = createComposeRule()
    @Test fun typedExpenseSkipsImageOperations() {
        var calls = 0
        compose.setContent { MaterialTheme { ReceiptHistoryScreen(
            ReceiptWorkflowUiState(history = listOf(sampleRecord())), {}, {}, {}, {}, {}, {}, {}, {}, {},
            onLoadImage = { _, _, _ -> calls++; ReceiptHistoryImageResult.NoImage },
        ) } }
        compose.onNodeWithText("No receipt image").performScrollTo().assertExists()
        compose.runOnIdle { assertEquals(0, calls) }
    }

    @Test fun historyPlaceholderRetryZoomBackAndReopenReset() {
        val record = sampleRecord().copy(sourceType = ReceiptSourceType.IMAGE, schemaVersion = 2,
            receiptImageRelativePath = "receipts/exported.jpg")
        val image = Bitmap.createBitmap(80, 800, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use { output ->
            image.compress(Bitmap.CompressFormat.PNG, 100, output); image.recycle(); output.toByteArray()
        }
        var downloads = 0
        compose.setContent { MaterialTheme { ReceiptHistoryScreen(
            ReceiptWorkflowUiState(history = listOf(record)), {}, {}, {}, {}, {}, {}, {}, {}, {},
            onLoadImage = { _, download, thumbnail ->
                if (download) downloads++
                if (thumbnail || downloads == 1) ReceiptHistoryImageResult.Unavailable("Receipt is not cached. Reconnect and retry.")
                else ReceiptHistoryImageResult.Available(bytes)
            },
        ) } }
        val description = "View receipt for ${record.merchantName}, ${record.receiptDate}, ${record.currency} ${record.totalAmount}, ${record.expenseId}"
        compose.onNodeWithContentDescription(description).performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Retry receipt image").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Retry receipt image").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Exported receipt copy").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Remove").assertDoesNotExist()
        compose.onNodeWithContentDescription("Zoom out").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Zoom in").performClick()
        compose.onNodeWithContentDescription("Zoom out").assertIsEnabled()
        pressBack()
        compose.onNodeWithContentDescription("Exported receipt copy").assertDoesNotExist()
        compose.onNodeWithContentDescription(description).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Exported receipt copy").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Zoom out").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Close receipt image").performClick()
        compose.runOnIdle { assertEquals(3, downloads) }
    }
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
