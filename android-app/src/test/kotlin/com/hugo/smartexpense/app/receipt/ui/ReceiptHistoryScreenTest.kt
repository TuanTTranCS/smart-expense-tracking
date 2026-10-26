package com.hugo.smartexpense.app.receipt.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.hugo.smartexpense.app.ReceiptSourceType
import com.hugo.smartexpense.app.ui.SmartExpenseTheme
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.awaitCancellation
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
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReceiptHistoryScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun description(record: ReceiptExportRecord) = "View receipt for ${record.merchantName}, ${record.receiptDate}, ${record.currency} ${record.totalAmount}, ${record.expenseId}"
    private fun imageBytes(): ByteArray {
        val image = Bitmap.createBitmap(80, 800, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { output ->
            image.compress(Bitmap.CompressFormat.PNG, 100, output)
            image.recycle()
            output.toByteArray()
        }
    }
    private fun awaitViewerImage() = compose.waitUntil(5_000) {
        compose.onAllNodesWithContentDescription("Exported receipt copy").fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun typedHistoryDoesNotInvokeImageLoader() {
        var reads = 0
        compose.setContent { MaterialTheme { ReceiptHistoryScreen(
            ReceiptWorkflowUiState(history = listOf(sampleTypedRecord())), {}, {}, {}, {}, {}, {}, {}, {}, {},
            onLoadImage = { _, _, _ -> reads++; ReceiptHistoryImageResult.NoImage },
        ) } }
        compose.onNodeWithText("No receipt image").performScrollTo().assertExists()
        compose.onNodeWithText("View receipt").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, reads) }
    }

    @Test fun placeholderOnlyDownloadsOnOpenAndRetryPreservesExpense() {
        val record = sampleRecord()
        val calls = mutableListOf<Pair<Boolean, Boolean>>()
        var localThumbnailReads = 0
        var opens = 0
        var workflowActions = 0
        compose.setContent { MaterialTheme { ReceiptHistoryScreen(
            ReceiptWorkflowUiState(history = listOf(record)), { workflowActions++ }, { workflowActions++ },
            { workflowActions++ }, { workflowActions++ }, { workflowActions++ }, { workflowActions++ },
            { workflowActions++ }, { workflowActions++ }, { workflowActions++ },
            onLoadImage = { _, download, thumbnail ->
                calls += download to thumbnail
                if (thumbnail && !download) localThumbnailReads++
                if (!thumbnail) opens++
                if (opens >= 2) ReceiptHistoryImageResult.Available(imageBytes())
                else ReceiptHistoryImageResult.Unavailable("Offline: reconnect to recover this receipt.")
            },
        ) } }
        compose.onNodeWithContentDescription(description(record)).performScrollTo().assertHasClickAction()
        compose.runOnIdle { assertTrue(calls.isNotEmpty()); assertTrue(calls.all { it == (false to true) }) }
        compose.onNodeWithContentDescription(description(record)).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Retry receipt image").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Offline: reconnect to recover this receipt.").assertIsDisplayed()
        compose.onNodeWithText("Remove").assertDoesNotExist()
        compose.onNodeWithText("Retry receipt image").performClick()
        awaitViewerImage()
        compose.onNodeWithContentDescription("Zoom out").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Zoom in").performClick()
        compose.onNodeWithContentDescription("Zoom out").assertIsEnabled()
        compose.onNodeWithContentDescription("Close receipt image").performClick()
        compose.waitUntil(5_000) { localThumbnailReads >= 2 && compose.onAllNodesWithTag("history-receipt-thumbnail:${record.expenseId}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Receipt unavailable").assertDoesNotExist()
        compose.onNodeWithText(record.merchantName).assertExists()
        compose.runOnIdle {
            assertEquals(2, opens)
            assertTrue(calls.filter { it.second }.all { !it.first })
        }
        compose.onNodeWithContentDescription(description(record)).performClick()
        awaitViewerImage()
        compose.onNodeWithContentDescription("Zoom out").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(3, opens); assertEquals(0, workflowActions) }
    }

    @Test fun closingLoadingViewerCancelsRequestAndAccountChangeDropsOldImage() {
        val record = sampleRecord()
        val state = mutableStateOf(ReceiptWorkflowUiState(history = listOf(record)))
        var cancelled = 0
        var started = 0
        val loader: suspend (ReceiptExportRecord, Boolean, Boolean) -> ReceiptHistoryImageResult = { _, download, _ ->
            if (download) { started++; try { awaitCancellation() } finally { cancelled++ } }
            ReceiptHistoryImageResult.Unavailable("Not cached")
        }
        compose.setContent { MaterialTheme { ReceiptHistoryScreen(
            state.value, {}, {}, {}, {}, {}, {}, {}, {}, {},
            onLoadImage = loader,
        ) } }
        compose.onNodeWithContentDescription(description(record)).performScrollTo().performClick()
        compose.waitForIdle()
        try {
            compose.waitUntil(5_000) { started == 1 }
        } catch (failure: Throwable) {
            throw AssertionError("Initial viewer request: started=$started cancelled=$cancelled", failure)
        }
        compose.onNodeWithText("Loading exported receipt copy").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close receipt image").performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { cancelled == 1 }
        compose.onNodeWithContentDescription(description(record)).performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { started == 2 }
        compose.runOnIdle { state.value = state.value.copy(history = listOf(record.copy(exportAccountId = "replacement-account"))) }
        compose.waitForIdle()
        compose.waitUntil(5_000) { cancelled == 2 && started == 3 }
        compose.onNodeWithContentDescription("Exported receipt copy").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(history = emptyList()) }
        compose.waitForIdle()
        compose.waitUntil(5_000) { cancelled == 3 }
    }

    @Test fun permanentImageFailureOffersCloseWithoutAutomaticRetry() {
        val record = sampleRecord()
        var downloads = 0
        compose.setContent { MaterialTheme { ReceiptHistoryScreen(
            ReceiptWorkflowUiState(history = listOf(record)), {}, {}, {}, {}, {}, {}, {}, {}, {},
            onLoadImage = { _, download, _ ->
                if (download) downloads++
                ReceiptHistoryImageResult.Unavailable("Reconnect the original OneDrive account.", retryable = false)
            },
        ) } }
        compose.onNodeWithContentDescription(description(record)).performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Reconnect the original OneDrive account.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Retry receipt image").assertDoesNotExist()
        compose.onNodeWithContentDescription("Zoom in").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Close receipt image").performClick()
        compose.onNodeWithText(record.merchantName).assertExists()
        compose.runOnIdle { assertEquals(1, downloads) }
    }

    @Test @Config(qualifiers = "w280dp-h640dp")
    fun largeFontHistoryViewerFitsAndRetainsCorrectionDraft() {
        val record = sampleRecord()
        val draft = ReceiptCorrectionDraft.from(record).review().copy(merchantName = "Unpublished correction")
        val darkTheme = mutableStateOf(false)
        val bytes = imageBytes()
        val loader: suspend (ReceiptExportRecord, Boolean, Boolean) -> ReceiptHistoryImageResult = { _, _, _ ->
            ReceiptHistoryImageResult.Available(bytes)
        }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                SmartExpenseTheme(darkTheme = darkTheme.value, dynamicColor = false) { ReceiptHistoryScreen(
                    ReceiptWorkflowUiState(history = listOf(record), historyEditingId = record.expenseId, historyDraft = draft),
                    {}, {}, {}, {}, {}, {}, {}, {}, {},
                    onLoadImage = loader,
                ) }
            }
        }
        for (dark in listOf(false, true)) {
            compose.runOnIdle { darkTheme.value = dark }
            if (dark) compose.onNodeWithText("Unpublished correction").assertIsDisplayed()
            val thumbnail = compose.onNodeWithContentDescription(description(record)).performScrollTo().assertIsDisplayed()
            val bounds = thumbnail.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.width >= 48 && bounds.height >= 48)
            thumbnail.performClick()
            awaitViewerImage()
            val image = compose.onNodeWithContentDescription("Exported receipt copy").fetchSemanticsNode().boundsInRoot
            val close = compose.onNodeWithContentDescription("Close receipt image").assertIsDisplayed()
            assertTrue(image.height > 0 && image.top >= close.fetchSemanticsNode().boundsInRoot.bottom)
            close.performClick()
            compose.onNodeWithText("Unpublished correction").performScrollTo().assertIsDisplayed()
        }
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
