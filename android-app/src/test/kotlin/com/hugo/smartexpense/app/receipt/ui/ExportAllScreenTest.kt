package com.hugo.smartexpense.app.receipt.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.hugo.smartexpense.app.ReceiptReviewState
import com.hugo.smartexpense.app.ReceiptSourceType
import com.hugo.smartexpense.app.graphauth.*
import com.hugo.smartexpense.app.modelprofile.ui.ModelProfilesUiState
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
class ExportAllScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun readyItem() = BatchItemState(itemId="ready", noImage=true, status=BatchItemStatus.SUCCEEDED,
        transactions=listOf(BatchTransaction("tx",0,ReceiptReviewState(receiptDate="2026-10-07",
            merchantName="Shop",totalAmount="65",sourceType=ReceiptSourceType.TYPED,manualEntryRequired=false))))

    @Test fun buttonRequiresEveryRemainingItemAndDispatchesOnceWhileBusy() {
        val state=mutableStateOf(ReceiptWorkflowUiState(items=listOf(readyItem(),BatchItemState(itemId="unfinished"))))
        var exports=0
        show(state,ready=true) { exports++;state.value=state.value.copy(exporting=true) }
        compose.onNodeWithText("Export All").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Item 2 needs extraction or manual review.").performScrollTo().assertExists()
        compose.runOnIdle { state.value=state.value.copy(items=listOf(readyItem())) }
        compose.onNodeWithText("Export All").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Export all reviewed transactions").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1,exports) }
    }

    @Test fun oneDriveReadinessIsRequiredEvenWhenAllResultsAreValid() {
        val state=mutableStateOf(ReceiptWorkflowUiState(items=listOf(readyItem())))
        show(state,ready=false) { error("Disconnected OneDrive must not export") }
        compose.onNodeWithText("Export All").performScrollTo().assertIsNotEnabled()
    }

    private fun show(state:androidx.compose.runtime.MutableState<ReceiptWorkflowUiState>, ready:Boolean,onExport:()->Unit) {
        compose.setContent { MaterialTheme {
            ReceiptWorkflowScreen(ModelProfilesUiState(),state.value,GraphAuthenticationUiState(),
                OneDrivePresentation("OneDrive","Test readiness",ready,OneDriveRecoveryAction.NONE),
                {},{},{},{},{},{},{},{_,_->},{},onExportAll=onExport)
        } }
    }
}
