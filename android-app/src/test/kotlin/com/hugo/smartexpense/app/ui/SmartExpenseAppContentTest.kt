package com.hugo.smartexpense.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.hugo.smartexpense.app.ReceiptReviewState
import com.hugo.smartexpense.app.graphauth.GraphAuthenticationUiState
import com.hugo.smartexpense.app.graphauth.OneDrivePresentation
import com.hugo.smartexpense.app.graphauth.OneDriveRecoveryAction
import com.hugo.smartexpense.app.modelprofile.ui.ModelProfilesUiState
import com.hugo.smartexpense.app.receipt.ui.ReceiptWorkflowScreen
import com.hugo.smartexpense.app.receipt.ui.ReceiptWorkflowUiState
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
class SmartExpenseAppContentTest {
    @get:Rule val compose = createComposeRule()
    private var exports = 0

    @Test fun exportButtonScrollsFullyAboveGestureNavigation() {
        showWorkflow { WindowInsets(top = 24.dp, bottom = 24.dp) }
        assertExportAboveBottomInset(24)
    }

    @Test fun exportButtonScrollsFullyAboveThreeButtonNavigation() {
        showWorkflow { WindowInsets(top = 24.dp, bottom = 48.dp) }
        assertExportAboveBottomInset(48)
    }

    @Test fun viewportRespondsWhenKeyboardInsetChanges() {
        val insets = mutableStateOf(WindowInsets(top = 24.dp, bottom = 24.dp))
        showWorkflow { insets.value }
        assertExportAboveBottomInset(24)
        compose.runOnIdle { insets.value = WindowInsets(top = 24.dp, bottom = 160.dp) }
        assertExportAboveBottomInset(160)
        compose.runOnIdle { insets.value = WindowInsets(top = 24.dp, bottom = 24.dp) }
        assertExportAboveBottomInset(24)
    }

    @Test fun sideInsetsAreConsumedOnceBeforeScrollableContent() {
        showWorkflow(repeatInsets = true) { WindowInsets(left = 32.dp, top = 24.dp, right = 48.dp) }
        val button = compose.onNode(hasText("Choose receipt") and hasClickAction())
        button.performScrollTo().assertIsDisplayed()
        val bounds = button.getUnclippedBoundsInRoot()
        val window = compose.onNodeWithTag("window").getUnclippedBoundsInRoot()
        // The workflow has its own 24 dp design margin. The child repeats the
        // same insets to verify the shared container has already consumed them.
        assertEquals(window.left + 32.dp + 24.dp, bounds.left)
        assertTrue(bounds.right <= window.right - 48.dp - 24.dp)
    }

    private fun showWorkflow(repeatInsets: Boolean = false, insets: () -> WindowInsets) {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().testTag("window")) {
                    SmartExpenseAppContent(contentWindowInsets = insets()) {
                        Box(Modifier.fillMaxSize().then(
                            if (repeatInsets) Modifier.windowInsetsPadding(insets()) else Modifier,
                        )) {
                            ReceiptWorkflowScreen(
                                profileState = ModelProfilesUiState(),
                                workflowState = ReceiptWorkflowUiState(reviews = listOf(
                                    ReceiptReviewState(
                                        merchantName = "Corner Store",
                                        rawModelOutput = "Long extraction response\n".repeat(30),
                                    ),
                                )),
                                graphState = GraphAuthenticationUiState(busy = false),
                                oneDrive = OneDrivePresentation("Ready", "Ready", true, OneDriveRecoveryAction.NONE),
                                onOpenSettings = {}, onSelectLocal = {}, onSelectRemote = {},
                                onVerifyProvider = {}, onOneDriveRecovery = {}, onChooseReceipt = {},
                                onRetryExtraction = {}, onReviewChange = { _, _ -> },
                                onExport = { exports++ }, debugOutputEnabled = true,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun assertExportAboveBottomInset(bottomDp: Int) {
        val button = compose.onNode(hasText("Confirm and export") and hasClickAction())
        button.performScrollTo().assertIsDisplayed()
        val bounds = button.getUnclippedBoundsInRoot()
        val window = compose.onNodeWithTag("window").getUnclippedBoundsInRoot()
        assertTrue(bounds.bottom <= window.bottom - bottomDp.dp,
            "The complete export button must remain above the system UI")
        assertTrue(bounds.top >= window.top + 24.dp)
        val previousExports = exports
        button.performClick()
        compose.runOnIdle { assertEquals(previousExports + 1, exports) }
    }
}
