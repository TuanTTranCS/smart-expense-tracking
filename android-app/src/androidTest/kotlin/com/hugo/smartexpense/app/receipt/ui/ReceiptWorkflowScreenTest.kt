package com.hugo.smartexpense.app.receipt.ui

import android.util.Base64
import android.view.KeyEvent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hugo.smartexpense.app.ReceiptReviewState
import com.hugo.smartexpense.app.graphauth.GraphAuthenticationUiState
import com.hugo.smartexpense.app.graphauth.OneDriveRecoveryAction
import com.hugo.smartexpense.app.graphauth.toOneDrivePresentation
import com.hugo.smartexpense.app.modelprofile.ui.ModelProfilesUiState
import com.hugo.smartexpense.extraction.ReceiptImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceiptWorkflowScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun mainContainsWorkflowButNotSettingsControls() {
        val graph = GraphAuthenticationUiState(busy = false)
        compose.setContent {
            MaterialTheme {
                ReceiptWorkflowScreen(
                    profileState = ModelProfilesUiState(),
                    workflowState = ReceiptWorkflowUiState(),
                    graphState = graph,
                    oneDrive = graph.toOneDrivePresentation(),
                    onOpenSettings = {}, onSelectLocal = {}, onSelectRemote = {}, onVerifyProvider = {},
                    onOneDriveRecovery = { _: OneDriveRecoveryAction -> }, onChooseReceipt = {},
                    onReviewChange = { _, _ -> }, onExport = {},
                )
            }
        }

        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("Provider for next receipt").assertIsDisplayed()
        compose.onNodeWithText("Verify profile").assertIsDisplayed()
        compose.onNodeWithText("Choose receipt").assertIsDisplayed()
        compose.onNodeWithContentDescription("Review selected receipt image").assertDoesNotExist()
        compose.onNodeWithText("Remote providers disabled (private local fallback)").assertDoesNotExist()
        compose.onNodeWithText("Receipt image preprocessing").assertDoesNotExist()
        compose.onNodeWithText("Disconnect").assertDoesNotExist()
    }

    @Test fun debugOutputsAppearOnlyWhenEnabledAndRemainSeparatePerReceipt() {
        val graph = GraphAuthenticationUiState(busy = false)
        var debugEnabled by mutableStateOf(true)
        val raw = """{"receipts":[{"merchantName":"First"},{"merchantName":"Second"}]}"""
        val reviews = listOf(
            ReceiptReviewState(rawModelOutput = raw, exportJsonPreview = """{"expenseId":"first"}"""),
            ReceiptReviewState(rawModelOutput = raw, exportJsonPreview = """{"expenseId":"second"}"""),
        )
        compose.setContent {
            MaterialTheme {
                ReceiptWorkflowScreen(
                    profileState = ModelProfilesUiState(),
                    workflowState = ReceiptWorkflowUiState(reviews = reviews),
                    graphState = graph,
                    oneDrive = graph.toOneDrivePresentation(),
                    onOpenSettings = {}, onSelectLocal = {}, onSelectRemote = {}, onVerifyProvider = {},
                    onOneDriveRecovery = { _: OneDriveRecoveryAction -> }, onChooseReceipt = {},
                    onReviewChange = { _, _ -> }, onExport = {},
                    debugOutputEnabled = debugEnabled,
                )
            }
        }

        compose.onNodeWithContentDescription("Raw extraction response").assertExists()
        compose.onAllNodesWithContentDescription("Handoff JSON").assertCountEquals(2)
        compose.runOnUiThread { debugEnabled = false }
        compose.onNodeWithContentDescription("Raw extraction response").assertDoesNotExist()
        compose.onAllNodesWithContentDescription("Handoff JSON").assertCountEquals(0)
    }

    @Test fun selectedImagePreviewOpensAndCloseAndBackReturnToWorkflow() {
        val graph = GraphAuthenticationUiState(busy = false)
        compose.setContent {
            MaterialTheme {
                ReceiptWorkflowScreen(
                    profileState = ModelProfilesUiState(),
                    workflowState = ReceiptWorkflowUiState(selectedImage = receiptImage("first")),
                    graphState = graph,
                    oneDrive = graph.toOneDrivePresentation(),
                    onOpenSettings = {}, onSelectLocal = {}, onSelectRemote = {}, onVerifyProvider = {},
                    onOneDriveRecovery = { _: OneDriveRecoveryAction -> }, onChooseReceipt = {},
                    onReviewChange = { _, _ -> }, onExport = {},
                )
            }
        }

        compose.waitUntil { compose.onAllNodesWithContentDescription("Review selected receipt image").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Review selected receipt image").performClick()
        compose.onNodeWithContentDescription("Selected receipt image").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close selected receipt image").performClick()
        compose.onNodeWithContentDescription("Selected receipt image").assertDoesNotExist()

        compose.onNodeWithContentDescription("Review selected receipt image").performClick()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithContentDescription("Selected receipt image").assertDoesNotExist()
        compose.onNodeWithContentDescription("Review selected receipt image").assertIsDisplayed()
    }

    @Test fun replacementDismissesViewerAndRetainsOnlyCurrentPreview() {
        val graph = GraphAuthenticationUiState(busy = false)
        var workflowState by mutableStateOf(ReceiptWorkflowUiState(selectedImage = receiptImage("first")))
        compose.setContent {
            MaterialTheme {
                ReceiptWorkflowScreen(
                    profileState = ModelProfilesUiState(), workflowState = workflowState,
                    graphState = graph, oneDrive = graph.toOneDrivePresentation(),
                    onOpenSettings = {}, onSelectLocal = {}, onSelectRemote = {}, onVerifyProvider = {},
                    onOneDriveRecovery = { _: OneDriveRecoveryAction -> }, onChooseReceipt = {},
                    onReviewChange = { _, _ -> }, onExport = {},
                )
            }
        }

        compose.waitUntil { compose.onAllNodesWithContentDescription("Review selected receipt image").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Review selected receipt image").performClick()
        compose.onNodeWithContentDescription("Selected receipt image").assertIsDisplayed()
        compose.runOnUiThread { workflowState = workflowState.copy(selectedImage = receiptImage("replacement")) }
        compose.onNodeWithContentDescription("Selected receipt image").assertDoesNotExist()
        compose.waitUntil { compose.onAllNodesWithContentDescription("Review selected receipt image").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun undecodableSelectedImageShowsActionableReviewMessage() {
        val graph = GraphAuthenticationUiState(busy = false)
        compose.setContent {
            MaterialTheme {
                ReceiptWorkflowScreen(
                    profileState = ModelProfilesUiState(),
                    workflowState = ReceiptWorkflowUiState(
                        selectedImage = ReceiptImage("broken", byteArrayOf(1, 2, 3), "image/png"),
                    ),
                    graphState = graph, oneDrive = graph.toOneDrivePresentation(),
                    onOpenSettings = {}, onSelectLocal = {}, onSelectRemote = {}, onVerifyProvider = {},
                    onOneDriveRecovery = { _: OneDriveRecoveryAction -> }, onChooseReceipt = {},
                    onReviewChange = { _, _ -> }, onExport = {},
                )
            }
        }

        val unavailable = "Selected image preview is unavailable. Choose the receipt again to review it."
        compose.waitUntil { compose.onAllNodesWithText(unavailable).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(unavailable)
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("Review selected receipt image").assertDoesNotExist()
    }

    private fun receiptImage(name: String): ReceiptImage = ReceiptImage(
        sourceName = name,
        bytes = Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLx0QAAAABJRU5ErkJggg==",
            Base64.DEFAULT,
        ),
        mimeType = "image/png",
    )
}
