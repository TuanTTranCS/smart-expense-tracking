package com.hugo.smartexpense.app.receipt.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.hugo.smartexpense.app.ReceiptReviewState
import com.hugo.smartexpense.app.ReceiptSourceType
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
class NoImageActionsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val item = mutableStateOf(BatchItemState(itemId = "typed", noImage = true, status = BatchItemStatus.READY))
    private val running = mutableStateOf(false)
    private val exporting = mutableStateOf(false)
    private val oneDriveReady = mutableStateOf(true)
    private val extractionAvailable = mutableStateOf(true)
    private val extractions = mutableListOf<Pair<String, Boolean>>()
    private val exports = mutableListOf<Pair<String, String>>()
    private val draft = ManualExpenseDraft(notes = "Paid 65 Canadian dollars at Shop on Oct 7 2026")
    private val review = ReceiptReviewState(receiptDate = "2026-10-07", merchantName = "Shop", totalAmount = "65",
        sourceType = ReceiptSourceType.TYPED, manualEntryRequired = false)

    private fun show() {
        compose.setContent { MaterialTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                BatchInputGroup(item.value, 1, running.value, exporting.value, oneDriveReady.value, false,
                    {}, {}, {}, { _, _, source -> item.value = item.value.copy(draft = source) },
                    { id, confirmed -> extractions += id to confirmed; running.value = true },
                    { _, _, _ -> }, { id, tx -> exports += id to tx; exporting.value = true }, {}, { null }, { null },
                    extractionAvailable = extractionAvailable.value)
            }
        } }
    }

    @Test fun initialExtractRequiresExpenseTextAndTargetsOnlyThisItem() {
        show()
        compose.onNodeWithText("Extract").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Confirm and export").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Notes (optional)").performScrollTo().performTextInput(draft.notes)
        compose.onNodeWithText("Extract").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Extract for typed expense 1").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf("typed" to false), extractions); assertEquals(emptyList(), exports) }
    }

    @Test fun failedAttemptOffersRetryAndKeepsExportDisabled() {
        item.value = item.value.copy(status = BatchItemStatus.FAILED, draft = draft, error = "Provider unavailable")
        show()
        compose.onNodeWithText("Extract").assertDoesNotExist()
        compose.onNodeWithText("Confirm and export").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Retry extraction").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("typed" to false), extractions) }
    }

    @Test fun retryProtectsReviewEditsWithExplicitReplacementConfirmation() {
        item.value = item.value.copy(status = BatchItemStatus.SUCCEEDED, draft = draft, edited = true,
            transactions = listOf(BatchTransaction("tx", 0, review)))
        show()
        compose.onNodeWithText("Retry extraction").performScrollTo().performClick()
        compose.onNodeWithText("Keep edits").performClick()
        compose.runOnIdle { assertEquals(emptyList(), extractions) }
        compose.onNodeWithText("Retry extraction").performScrollTo().performClick()
        compose.onNodeWithText("Replace and retry").performClick()
        compose.runOnIdle { assertEquals(listOf("typed" to true), extractions) }
    }

    @Test fun extractionBlocksDuringProviderWorkPreparationExtractionAndExport() {
        item.value = item.value.copy(draft = draft)
        show()
        compose.runOnIdle { extractionAvailable.value = false }
        compose.onNodeWithText("Extract").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { extractionAvailable.value = true; running.value = true }
        compose.onNodeWithText("Extract").assertIsNotEnabled()
        compose.runOnIdle { running.value = false; exporting.value = true }
        compose.onNodeWithText("Extract").assertIsNotEnabled()
        compose.runOnIdle { exporting.value = false; item.value = item.value.copy(status = BatchItemStatus.PREPARING) }
        compose.onNodeWithText("Extract").assertIsNotEnabled()
        compose.runOnIdle { item.value = item.value.copy(status = BatchItemStatus.READY) }
        compose.onNodeWithText("Extract").assertIsEnabled()
    }

    @Test fun confirmExportRequiresCurrentValidResultAndOneDriveThenDispatchesStableIdentityOnce() {
        item.value = item.value.copy(status = BatchItemStatus.SUCCEEDED, draft = draft,
            transactions = listOf(BatchTransaction("tx", 0, review)))
        oneDriveReady.value = false
        show()
        compose.onNodeWithText("Confirm and export").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { oneDriveReady.value = true; item.value = item.value.copy(sourceRevision = 1, status = BatchItemStatus.READY) }
        compose.onNodeWithText("Confirm and export").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Retry extraction").performScrollTo().assertIsEnabled()
        compose.runOnIdle { item.value = item.value.copy(sourceRevision = 0, status = BatchItemStatus.SUCCEEDED,
            transactions = listOf(BatchTransaction("tx", 0, review.copy(totalAmount = "15+50")))) }
        compose.onNodeWithText("Confirm and export").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { item.value = item.value.copy(transactions = listOf(BatchTransaction("tx", 0, review))) }
        compose.onNodeWithText("Confirm and export").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Exporting").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf("typed" to "tx"), exports) }
    }

    @Test fun persistedExportRetainsExportRetryAndPreventsExtraction() {
        item.value = item.value.copy(status = BatchItemStatus.SUCCEEDED, draft = draft,
            transactions = listOf(BatchTransaction("tx", 0, review.copy(exportExpenseId = "saved"))))
        show()
        compose.onNodeWithText("Retry extraction").assertDoesNotExist()
        compose.onNodeWithText("Retry export").performScrollTo().assertIsEnabled()
        compose.runOnIdle { item.value = item.value.copy(transactions = listOf(BatchTransaction("tx", 0,
            review.copy(exportExpenseId = "saved", exportComplete = true)))) }
        compose.onNodeWithText("Exported").performScrollTo().assertIsNotEnabled()
    }
}
