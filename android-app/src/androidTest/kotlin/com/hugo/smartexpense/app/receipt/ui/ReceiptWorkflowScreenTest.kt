package com.hugo.smartexpense.app.receipt.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hugo.smartexpense.app.ReceiptReviewState
import com.hugo.smartexpense.app.graphauth.GraphAuthenticationUiState
import com.hugo.smartexpense.app.graphauth.OneDriveRecoveryAction
import com.hugo.smartexpense.app.graphauth.toOneDrivePresentation
import com.hugo.smartexpense.app.modelprofile.ui.ModelProfilesUiState
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
}
