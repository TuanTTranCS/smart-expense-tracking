package com.hugo.smartexpense.app.receipt.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hugo.smartexpense.app.graphauth.*
import com.hugo.smartexpense.app.modelprofile.ui.ModelProfilesUiState
import com.hugo.smartexpense.extraction.ReceiptImage
import com.hugo.smartexpense.app.ReceiptReviewState
import com.hugo.smartexpense.app.ReceiptSourceType
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals

@RunWith(AndroidJUnit4::class)
class BatchWorkflowScreenTest {
    @get:Rule val compose=createComposeRule()
    @Test fun noImageExtractAndConfirmActionsFollowTheItemLifecycle() {
        var item by mutableStateOf(BatchItemState(itemId="typed-actions",noImage=true,status=BatchItemStatus.READY))
        var running by mutableStateOf(false)
        var exporting by mutableStateOf(false)
        var extractionCalls=0
        var exportCalls=0
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            BatchInputGroup(item,1,running,exporting,true,false,{},{},{},
                {_,_,draft->item=item.copy(draft=draft)},
                {id,confirmed->assertEquals("typed-actions",id);assertEquals(false,confirmed);extractionCalls++;running=true},
                {_,_,_->},{id,tx->assertEquals("typed-actions",id);assertEquals("tx",tx);exportCalls++;exporting=true},
                {},{null},{null})
        } } }
        compose.onNodeWithText("Extract").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Confirm and export").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Notes (optional)").performScrollTo().performTextInput("Paid 65 Canadian dollars at Shop on Oct 7 2026")
        compose.onNodeWithText("Extract").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Extract").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1,extractionCalls);assertEquals(0,exportCalls)
            running=false
            item=item.copy(status=BatchItemStatus.SUCCEEDED,transactions=listOf(BatchTransaction("tx",0,
                ReceiptReviewState(receiptDate="2026-10-07",merchantName="Shop",totalAmount="65",sourceType=ReceiptSourceType.TYPED,manualEntryRequired=false))))
        }
        compose.onNodeWithText("Retry extraction").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Confirm and export").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Exporting").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1,exportCalls) }
    }
    @Test fun exportAllRequiresEveryRemainingItemAndConfirmsTheListExplicitly() {
        val review=ReceiptReviewState(receiptDate="2026-10-07",merchantName="Shop",totalAmount="65",
            manualEntryRequired=false,sourceType=ReceiptSourceType.TYPED)
        val ready=BatchItemState(itemId="ready",noImage=true,status=BatchItemStatus.SUCCEEDED,expanded=false,
            transactions=listOf(BatchTransaction("tx",0,review)))
        var state by mutableStateOf(ReceiptWorkflowUiState(items=listOf(ready,BatchItemState(itemId="unfinished",expanded=false))))
        var exports=0
        compose.setContent { MaterialTheme { ReceiptWorkflowScreen(ModelProfilesUiState(),state,
            GraphAuthenticationUiState(),OneDrivePresentation("OneDrive ready","Test",true,OneDriveRecoveryAction.NONE),
            {},{},{},{},{},{},{},{_,_->},{},onExportAll={exports++;state=state.copy(exporting=true)}) } }
        compose.onNodeWithContentDescription("Export all reviewed transactions").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { state=state.copy(items=listOf(ready)) }
        compose.onNodeWithContentDescription("Export all reviewed transactions").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Export all reviewed transactions").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1,exports) }
    }
    @Test fun viewerAndThumbnailRemoveTheSameItemAndFullImageLoadsOnlyOnOpen() {
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        }
        bitmap.recycle()
        val image = ReceiptImage("preview.png", bytes, "image/png")
        val original = BatchItemState(itemId = "image-stable", image = image, status = BatchItemStatus.READY)
        var item by mutableStateOf<BatchItemState?>(original)
        val removed = mutableListOf<String>()
        val fullLoads = AtomicInteger()
        compose.setContent { MaterialTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                item?.let { current -> BatchInputGroup(
                    current, 1, false, false, true, false,
                    {}, { removed.add(it); item = null }, {}, { _, _, _ -> }, { _, _ -> },
                    { _, _, _ -> }, { _, _ -> }, {}, { image }, { fullLoads.incrementAndGet(); image },
                ) }
            }
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Review selected receipt image").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, fullLoads.get())
        compose.onNodeWithContentDescription("Review selected receipt image").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Selected receipt image").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, fullLoads.get())
        compose.onNodeWithText("Remove").performClick()
        compose.onNodeWithContentDescription("Selected receipt image").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("image-stable"), removed); item = original }
        compose.onNodeWithContentDescription("Remove image 1").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("image-stable", "image-stable"), removed) }
        assertEquals(1, fullLoads.get())
    }
    @Test fun emptyCheckboxCreatesTypedExpenseWithCadDefaultWithoutPicker() {
        var item by mutableStateOf<BatchItemState?>(null)
        val graph=GraphAuthenticationUiState()
        compose.setContent { MaterialTheme { ReceiptWorkflowScreen(
            ModelProfilesUiState(),ReceiptWorkflowUiState(items=listOfNotNull(item)),graph,graph.toOneDrivePresentation(),
            {},{},{},{},{},{error("Typed creation must not open picker")},{},{_,_->},{},
            onAddTypedItem={item=BatchItemState(itemId="typed",noImage=true,status=BatchItemStatus.READY)},
        ) } }
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("Merchant name").performScrollTo().assertExists()
        compose.onNodeWithText("CAD").performScrollTo().assertExists()
    }
    @Test fun sourceSwitchPreservesDraftAndCollapseDoesNotResetEdits() {
        var item by mutableStateOf(BatchItemState(itemId="source",noImage=true,status=BatchItemStatus.READY))
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { BatchInputGroup(item,1,false,false,true,false,
            {},{}, {item=item.copy(expanded=!item.expanded)}, {_,typed,draft->item=item.copy(noImage=typed,draft=draft)}, {_,_->}, {_,_,_->},{_,_->},{},{null},{null}) } } }
        compose.onNodeWithText("Merchant name").performTextInput("Saved merchant")
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("Choose images").assertExists()
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("Saved merchant").assertExists()
        compose.onNodeWithText("Collapse Typed expense 1 / ready / 0 transactions").performClick()
        compose.onNodeWithText("Saved merchant").assertDoesNotExist()
        compose.onNodeWithText("Expand Typed expense 1 / ready / 0 transactions").performClick()
        compose.onNodeWithText("Saved merchant").assertExists()
    }
    @Test fun removalUsesStableItemIdAndRetryRequiresReplacementConfirmation() {
        var removed:String?=null;var retried:Pair<String,Boolean>?=null
        val item=BatchItemState(itemId="stable-id",noImage=true,status=BatchItemStatus.FAILED,edited=true,draft=ManualExpenseDraft("Shop","10.00","CAD","2026-10-05"))
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { BatchInputGroup(item,2,false,false,true,false,
            {},{removed=it},{},{_,_,_->},{id,confirmed->retried=id to confirmed},{_,_,_->},{_,_->},{},{null},{null}) } } }
        compose.onNodeWithText("Retry extraction").performScrollTo().performClick()
        compose.onNodeWithText("Replace review edits?").assertExists()
        compose.onNodeWithText("Keep edits").performClick();assertEquals(null,retried)
        compose.onNodeWithText("Retry extraction").performScrollTo().performClick()
        compose.onNodeWithText("Replace and retry").performClick();assertEquals("stable-id" to true,retried)
        compose.onNodeWithContentDescription("Remove item 2").performScrollTo().performClick();assertEquals("stable-id",removed)
    }

    @Test fun everySourceFieldAcceptsWordsExpressionsAndPunctuationUnchanged() {
        var item by mutableStateOf(BatchItemState(itemId="free-text",noImage=true,status=BatchItemStatus.READY))
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            BatchInputGroup(item,1,false,false,true,false,{},{},{},
                {_,_,draft->item=item.copy(draft=draft)}, {_,_->}, {_,_,_->}, {_,_->},{},{null},{null})
        } } }
        val values=listOf("Merchant name" to "Walmrat, Oct 7 2026", "Final total paid/payable (including tax/tip)" to "15+50, which is 65 in total",
            "Currency" to "Canadian dollars", "Date" to "Oct 7 2026", "Notes (optional)" to "Lunch + tip, paid in CAD!")
        values.forEach { (label,value)->compose.onNodeWithText(label).performScrollTo().performTextReplacement(value) }
        compose.runOnIdle { assertEquals(ManualExpenseDraft(values[0].second,values[1].second,values[2].second,values[3].second,values[4].second),item.draft) }
        compose.onNodeWithText("Choose date").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals("Oct 7 2026",item.draft.date) }
    }

    @Test fun notesOnlySourceAndNormalizedSuggestionRemainVisibleUntilExplicitConfirmation() = verifyWholeExpenseReview(inNotes=true)
    @Test fun merchantOnlySourceAndNormalizedSuggestionRemainVisibleUntilExplicitConfirmation() = verifyWholeExpenseReview(inNotes=false)

    private fun verifyWholeExpenseReview(inNotes:Boolean) {
        val original="Walmrat, Oct 7 2026, 15+50 Canadian dollars"
        val draft=if(inNotes) ManualExpenseDraft(currency="",notes=original) else ManualExpenseDraft(merchant=original,currency="")
        val review=ReceiptReviewState(receiptDate="2026-10-07",merchantName="Walmart",totalAmount="65",currency="CAD",notes=draft.notes,
            manualEntryRequired=false,sourceType=ReceiptSourceType.TYPED,extractionStatus="low_confidence",message="Suggested merchant: Walmrat → Walmart. Confirm the normalized expense before export.")
        val item=BatchItemState(itemId="notes-only",noImage=true,status=BatchItemStatus.SUCCEEDED,draft=draft,transactions=listOf(BatchTransaction("tx",0,review)))
        var exports=0
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            BatchInputGroup(item,1,false,false,true,false,{},{},{},{_,_,_->},{_,_->},{_,_,_->},{id,tx->assertEquals("notes-only",id);assertEquals("tx",tx);exports++},{},{null},{null})
        } } }
        compose.onNodeWithText("Merchant name").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString(draft.merchant)))
        compose.onNodeWithText("Final total paid/payable (including tax/tip)").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("")))
        compose.onNodeWithText("Date").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("")))
        compose.onNodeWithText(review.message).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Walmart").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("65").performScrollTo().assertIsDisplayed()
        assertEquals(0,exports)
        compose.onNodeWithText("Confirm and export").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1,exports);assertEquals(draft,item.draft);assertEquals(draft.notes,item.transactions.single().review.notes) }
    }

    @Test fun invalidManualResultCannotConfirmOrExport() {
        val review=ReceiptReviewState(totalAmount="15+50",receiptDate="Oct 7 2026",currency="Canadian dollars",sourceType=ReceiptSourceType.TYPED)
        val item=BatchItemState(itemId="manual",noImage=true,status=BatchItemStatus.SUCCEEDED,transactions=listOf(BatchTransaction("tx",0,review)))
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            BatchInputGroup(item,1,false,false,true,false,{},{},{},{_,_,_->},{_,_->},{_,_,_->},{_,_->error("Invalid result exported")},{},{null},{null})
        } } }
        compose.onNodeWithText("Confirm and export").performScrollTo().assertIsNotEnabled()
    }

    @Test fun optionalDatePickerSelectionDoesNotRestrictLaterFreeText() {
        var item by mutableStateOf(BatchItemState(itemId="date-picker",noImage=true,status=BatchItemStatus.READY))
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            BatchInputGroup(item,1,false,false,true,false,{},{},{},{_,_,draft->item=item.copy(draft=draft)}, {_,_->},{_,_,_->},{_,_->},{},{null},{null})
        } } }
        compose.onNodeWithText("Choose date").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Switch to text input mode").performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("10072026")
        compose.onNodeWithText("Use date").performClick()
        compose.runOnIdle { assertEquals("2026-10-07",item.draft.date) }
        compose.onNodeWithText("Date").performScrollTo().performTextReplacement("Oct 7 2026, paid after lunch")
        compose.runOnIdle { assertEquals("Oct 7 2026, paid after lunch",item.draft.date) }
    }
}
