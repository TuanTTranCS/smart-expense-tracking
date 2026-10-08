package com.hugo.smartexpense.app.receipt.ui

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import androidx.test.espresso.Espresso
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
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

    @Test fun mainShowsEligibleTailscaleControlAndHidesItAfterSelectingLocal() {
        val profile = com.hugo.smartexpense.extraction.ModelProfile(
            "tailnet", "Tailnet", "https://example.test/v1", "model",
            com.hugo.smartexpense.extraction.RemoteInputMode.DIRECT_IMAGE,
            com.hugo.smartexpense.extraction.RemoteStructuredOutputFormat.JSON_SCHEMA,
            "alias", 1, 1, true,
        )
        var state by mutableStateOf(ModelProfilesUiState(profiles = listOf(profile),
            selectorState = com.hugo.smartexpense.extraction.ModelProfileSelectorState(profile.id, true)))
        val graph = GraphAuthenticationUiState(busy = false)
        compose.setContent {
            MaterialTheme {
                ReceiptWorkflowScreen(
                    profileState = state, workflowState = ReceiptWorkflowUiState(), graphState = graph,
                    oneDrive = graph.toOneDrivePresentation(), onOpenSettings = {}, onSelectLocal = {},
                    onSelectRemote = {}, onVerifyProvider = {}, onOneDriveRecovery = {}, onChooseReceipt = {},
                    onRetryExtraction = {}, onReviewChange = { _, _ -> }, onExport = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Request Tailscale for Tailnet").assertExists()
        compose.runOnUiThread { state = state.copy(selectorState = state.selectorState.copy(selectedRemoteProfileId = null)) }
        compose.onNodeWithContentDescription("Request Tailscale for Tailnet").assertDoesNotExist()
    }

    @Test fun retryActionRequiresImageAndDisablesDuringExtractionExportAndProfileWork() {
        val graph = GraphAuthenticationUiState(busy = false)
        var workflow by mutableStateOf(ReceiptWorkflowUiState())
        var profiles by mutableStateOf(ModelProfilesUiState())
        var retries = 0
        compose.setContent {
            MaterialTheme {
                ReceiptWorkflowScreen(
                    profileState = profiles, workflowState = workflow, graphState = graph,
                    oneDrive = graph.toOneDrivePresentation(), onOpenSettings = {}, onSelectLocal = {},
                    onSelectRemote = {}, onVerifyProvider = {}, onOneDriveRecovery = {}, onChooseReceipt = {},
                    onRetryExtraction = { retries++ }, onReviewChange = { _, _ -> }, onExport = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Retry extraction for selected image").assertDoesNotExist()
        compose.runOnUiThread {
            workflow = workflow.copy(selectedImage = receiptImage("retry"), selectedImageUri = "content://receipt")
        }
        compose.onNodeWithContentDescription("Retry extraction for selected image").assertIsEnabled().performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, retries) }
        compose.runOnUiThread { workflow = workflow.copy(extracting = true) }
        compose.onNodeWithContentDescription("Retry extraction for selected image").assertIsNotEnabled()
        compose.runOnUiThread { workflow = workflow.copy(extracting = false, exporting = true) }
        compose.onNodeWithContentDescription("Retry extraction for selected image").assertIsNotEnabled()
        compose.runOnUiThread { workflow = workflow.copy(exporting = false); profiles = profiles.copy(busy = true) }
        compose.onNodeWithContentDescription("Retry extraction for selected image").assertIsNotEnabled()
        compose.runOnUiThread { profiles = profiles.copy(busy = false) }
        compose.onNodeWithContentDescription("Retry extraction for selected image").assertIsEnabled()
    }

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
                    onRetryExtraction = {}, onReviewChange = { _, _ -> }, onExport = {},
                )
            }
        }

        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("Provider for next receipt").assertIsDisplayed()
        compose.onNodeWithText("Verify profile").assertIsDisplayed()
        compose.onNodeWithText("Choose images").assertIsDisplayed()
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
                    onRetryExtraction = {}, onReviewChange = { _, _ -> }, onExport = {},
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

    @Test fun selectedImagePreviewZoomControlsCloseAndBackReturnToWorkflow() {
        val graph = GraphAuthenticationUiState(busy = false)
        compose.setContent {
            MaterialTheme {
                ReceiptWorkflowScreen(
                    profileState = ModelProfilesUiState(),
                    workflowState = ReceiptWorkflowUiState(
                        selectedImage = receiptImage("first"),
                        reviews = listOf(ReceiptReviewState(merchantName = "Corner Store")),
                    ),
                    graphState = graph,
                    oneDrive = graph.toOneDrivePresentation(),
                    onOpenSettings = {}, onSelectLocal = {}, onSelectRemote = {}, onVerifyProvider = {},
                    onOneDriveRecovery = { _: OneDriveRecoveryAction -> }, onChooseReceipt = {},
                    onRetryExtraction = {}, onReviewChange = { _, _ -> }, onExport = {},
                )
            }
        }

        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithContentDescription("Review selected receipt image").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Review selected receipt image").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Selected receipt image").assertIsDisplayed()
        compose.onNodeWithContentDescription("Zoom in").assertIsEnabled()
        compose.onNodeWithContentDescription("Zoom out").assertIsNotEnabled()
        repeat(4) { compose.onNodeWithContentDescription("Zoom in").performClick() }
        compose.onNodeWithContentDescription("Zoom in").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Zoom out").assertIsEnabled()
        compose.onNodeWithContentDescription("Selected receipt image").performTouchInput {
            swipeLeft()
        }
        compose.onNodeWithContentDescription("Selected receipt image").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close selected receipt image").performClick()
        compose.onNodeWithContentDescription("Selected receipt image").assertDoesNotExist()
        compose.onNodeWithText("Corner Store").performScrollTo().assertIsDisplayed()

        compose.onNodeWithContentDescription("Review selected receipt image").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Zoom out").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Zoom in").performClick()
        Espresso.pressBack()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("Selected receipt image").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithContentDescription("Selected receipt image").assertDoesNotExist()
        compose.onNodeWithContentDescription("Review selected receipt image").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Corner Store").performScrollTo().assertIsDisplayed()
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
                    onRetryExtraction = {}, onReviewChange = { _, _ -> }, onExport = {},
                )
            }
        }

        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithContentDescription("Review selected receipt image").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Review selected receipt image").performClick()
        compose.onNodeWithContentDescription("Selected receipt image").assertIsDisplayed()
        compose.onNodeWithContentDescription("Zoom in").performClick()
        compose.runOnUiThread { workflowState = workflowState.copy(selectedImage = receiptImage("replacement")) }
        compose.onNodeWithContentDescription("Selected receipt image").assertDoesNotExist()
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithContentDescription("Review selected receipt image").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Review selected receipt image").performClick()
        compose.onNodeWithContentDescription("Zoom out").assertIsNotEnabled()
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
                    onRetryExtraction = {}, onReviewChange = { _, _ -> }, onExport = {},
                )
            }
        }

        val unavailable = "Selected image preview is unavailable. Choose the receipt again to review it."
        compose.waitUntil { compose.onAllNodesWithText(unavailable).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(unavailable)
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("Review selected receipt image").assertDoesNotExist()
    }

    private fun receiptImage(name: String): ReceiptImage {
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(android.graphics.Color.WHITE)
            val bytes = ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
            ReceiptImage(name, bytes, "image/png")
        } finally {
            bitmap.recycle()
        }
    }
}
