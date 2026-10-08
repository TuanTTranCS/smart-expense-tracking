package com.hugo.smartexpense.app.receipt.ui

import com.hugo.smartexpense.app.ReceiptReviewState
import com.hugo.smartexpense.app.ReceiptSourceType
import com.hugo.smartexpense.app.receiptexport.sampleRecord
import com.hugo.smartexpense.app.receiptexport.ReceiptExportRecord
import com.hugo.smartexpense.app.receiptexport.ReceiptExportStatus
import com.hugo.smartexpense.app.settings.data.*
import com.hugo.smartexpense.extraction.ReceiptImage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Before
import org.junit.After
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class BatchWorkflowViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun teardown() = Dispatchers.resetMain()
    private val image = ReceiptImage("receipt.jpg", byteArrayOf(1,2,3), "image/jpeg")
    private fun review() = ReceiptReviewState(receiptDate="2026-10-05",merchantName="Shop",totalAmount="10.00",manualEntryRequired=false)
    private class Settings : AppSettingsRepository {
        override val settings = MutableStateFlow(AppSettings())
        override fun setReduceOversizedImages(enabled: Boolean) { settings.value=settings.value.copy(reduceOversizedImages=enabled) }
        override fun setDebugOutputEnabled(enabled: Boolean) { settings.value=settings.value.copy(debugOutputEnabled=enabled) }
        override fun setDeviceNameOverride(name: String) {}
    }
    private fun vm(extract: suspend () -> List<ReceiptReviewState> = { listOf(review()) }, typed: suspend (ManualExpenseDraft) -> List<ReceiptReviewState> = { listOf(review()) }, store: PreparedBatchStore? = null,
        batch: (suspend (List<Pair<String,ReceiptReviewState>>, (ReceiptExportRecord)->Unit)->List<ReceiptExportRecord>)? = null) = ReceiptWorkflowViewModel(
        Settings(), { _,_,_,_,_ -> extract() }, { sampleRecord() }, { sampleRecord() }, dispatcher,
        prepareImage = { _,_ -> image }, reviewTyped = { d,_ -> typed(d) }, batchStore=store, exportReviewedBatch=batch,
    )
    @Test fun pickerPreparationDoesNotInferAndRunsOnlyOnExtractAll() = runTest(dispatcher) {
        var calls=0; val model=vm(extract={calls++; listOf(review())})
        model.prepareImages(listOf("content://a","content://b")); advanceUntilIdle()
        assertEquals(0,calls); assertEquals(2,model.uiState.value.items.size)
        assertTrue(model.uiState.value.items.all { it.status == BatchItemStatus.READY })
        model.extractAll(null); model.extractAll(null); advanceUntilIdle()
        assertEquals(2,calls); assertTrue(model.uiState.value.items.all { it.status == BatchItemStatus.SUCCEEDED })
        model.extractAll(null); advanceUntilIdle(); assertEquals(2,calls)
    }
    @Test fun itemExtractAndRetryUseOnlyTheTargetDraftAndNeverAutomaticallyExport() = runTest(dispatcher) {
        val drafts = mutableListOf<ManualExpenseDraft>()
        val release = CompletableDeferred<Unit>()
        var fail = false
        val model = vm(typed = { draft ->
            drafts += draft
            release.await()
            if (fail) error("Provider unavailable")
            listOf(review())
        })
        model.addTypedItem(); model.addTypedItem()
        val target = model.uiState.value.items.last()
        model.retryItem(target.itemId, null); advanceUntilIdle()
        assertTrue(drafts.isEmpty())
        val draft = ManualExpenseDraft(notes = "Shop, Oct 5 2026, ten Canadian dollars")
        model.updateSource(target.itemId, true, draft)
        model.retryItem(target.itemId, null); model.retryItem(target.itemId, null); runCurrent()
        assertEquals(listOf(draft), drafts)
        assertTrue(model.uiState.value.extracting)
        release.complete(Unit); advanceUntilIdle()
        val result = model.uiState.value.items.last()
        assertEquals(BatchItemStatus.SUCCEEDED, result.status)
        assertTrue(model.uiState.value.items.first().transactions.isEmpty())
        val tx = result.transactions.single()
        model.updateTransaction(target.itemId, tx.id, tx.review.copy(merchantName = "Edited"))
        fail = true
        model.retryItem(target.itemId, null); advanceUntilIdle()
        assertEquals(1, drafts.size)
        model.retryItem(target.itemId, null, true); advanceUntilIdle()
        val failed = model.uiState.value.items.last()
        assertEquals(BatchItemStatus.FAILED, failed.status)
        assertEquals("Edited", failed.transactions.single().review.merchantName)
        assertEquals(draft, failed.draft)
        model.exportTransaction(target.itemId, tx.id); advanceUntilIdle()
        assertNull(model.uiState.value.items.last().transactions.single().review.exportExpenseId)
        fail = false
        model.retryItem(target.itemId, null, true); advanceUntilIdle()
        assertEquals(3, drafts.size)
        assertEquals(BatchItemStatus.SUCCEEDED, model.uiState.value.items.last().status)
        assertNull(model.uiState.value.items.last().transactions.single().review.exportExpenseId)
    }
    @Test fun duplicatesAndCapacityAreReportedWithoutReplacingExistingItems() = runTest(dispatcher) {
        val model=vm(); model.prepareImages(listOf("content://a")); advanceUntilIdle(); val id=model.uiState.value.items.first().itemId
        model.prepareImages(listOf("content://a","content://a")); advanceUntilIdle(); assertEquals(1,model.uiState.value.items.size)
        assertContains(model.uiState.value.batchMessage.orEmpty(),"Duplicate")
        model.prepareImages((1..25).map { "content://$it" }); advanceUntilIdle()
        assertEquals(20,model.uiState.value.items.size); assertEquals(id,model.uiState.value.items.first().itemId)
        assertContains(model.uiState.value.batchMessage.orEmpty(),"6 additions rejected")
    }
    @Test fun combinedDraftValidationBlocksOnlyEmptyInputAndSourceSwitchPreservesRawText() = runTest(dispatcher) {
        var calls=0; val model=vm(typed={calls++;listOf(review())}); model.addItem(); val id=model.uiState.value.items.first().itemId
        model.updateSource(id,true,ManualExpenseDraft()); model.extractAll(null); advanceUntilIdle(); assertEquals(0,calls)
        assertNotNull(model.uiState.value.items.first().error)
        val draft=ManualExpenseDraft("", "15+50, which is 65 in total", "Canadian dollars", "Oct 7 2026", "  Walmrat\noriginal notes  ")
        model.updateSource(id,true,draft)
        model.updateSource(id,false,draft); assertEquals(draft,model.uiState.value.items.first().draft)
        model.updateSource(id,true,draft); model.extractAll(null); advanceUntilIdle(); assertEquals(1,calls)
        assertEquals(ReceiptSourceType.TYPED,model.uiState.value.items.first().transactions.first().review.sourceType)
        assertEquals("",model.uiState.value.items.first().transactions.first().review.sourceImageUri)
    }
    @Test fun removalAndUndoRetainStableIdentityAndReviewFields() = runTest(dispatcher) {
        val model=vm();model.prepareImages(listOf("content://a","content://b"));advanceUntilIdle();model.extractAll(null);advanceUntilIdle()
        val before=model.uiState.value.items.first();model.removeItem(before.itemId);model.undoRemove()
        assertEquals(before,model.uiState.value.items.first());advanceUntilIdle()
    }
    @Test fun changedSourceRevisionCannotExportOldResult() = runTest(dispatcher) {
        val model=vm();model.addItem();val id=model.uiState.value.items.first().itemId
        val draft=ManualExpenseDraft("Shop","10.00","CAD","2026-10-05")
        model.updateSource(id,true,draft);model.extractAll(null);advanceUntilIdle()
        val tx=model.uiState.value.items.first().transactions.first()
        model.updateSource(id,true,draft.copy(amount="20.00"));model.exportTransaction(id,tx.id);advanceUntilIdle()
        assertNull(model.uiState.value.items.first().transactions.first().review.exportExpenseId)
    }
    @Test fun failedRetryKeepsEditsButCannotExportStaleResults() = runTest(dispatcher) {
        var fail=false;val model=vm(extract={if(fail) error("bad provider") else listOf(review())})
        model.prepareImages(listOf("content://a"));advanceUntilIdle();model.extractAll(null);advanceUntilIdle()
        val item=model.uiState.value.items.first();val tx=item.transactions.first()
        model.updateTransaction(item.itemId,tx.id,tx.review.copy(merchantName="Edited"));fail=true
        model.retryItem(item.itemId,null);advanceUntilIdle();assertEquals(BatchItemStatus.SUCCEEDED,model.uiState.value.items.first().status)
        model.retryItem(item.itemId,null,true);advanceUntilIdle()
        assertEquals("Edited",model.uiState.value.items.first().transactions.first().review.merchantName)
        model.exportTransaction(item.itemId,tx.id);advanceUntilIdle();assertNull(model.uiState.value.items.first().transactions.first().review.exportExpenseId)
    }
    @Test fun backgroundCancelsRemainingAndNeverPublishesLateResult() = runTest(dispatcher) {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val model=vm(extract={entered.complete(Unit);release.await();listOf(review())})
        model.prepareImages(listOf("content://a","content://b"));advanceUntilIdle();model.extractAll(null);runCurrent();assertTrue(entered.isCompleted)
        model.setForeground(false);release.complete(Unit);advanceUntilIdle()
        assertFalse(model.uiState.value.extracting);assertTrue(model.uiState.value.items.all { it.transactions.isEmpty() && it.status == BatchItemStatus.CANCELLED })
    }
    @Test fun typedMultipleOutputsFailAndDebugOffDiscardsRawOutput() = runTest(dispatcher) {
        val model=vm(typed={listOf(review(),review())});model.addItem();val id=model.uiState.value.items.first().itemId
        model.updateSource(id,true,ManualExpenseDraft("Shop","10.00","CAD","2026-10-05"));model.extractAll(null);advanceUntilIdle()
        assertEquals(BatchItemStatus.FAILED,model.uiState.value.items.first().status)
        val images=vm(extract={listOf(review().copy(rawModelOutput="private"))});images.prepareImages(listOf("content://a"));advanceUntilIdle();images.extractAll(null);advanceUntilIdle()
        assertEquals("",images.uiState.value.items.first().transactions.first().review.rawModelOutput)
    }
    @Test fun itemSelectionAttachesToExactExistingCardAndPreservesPositionAndTypedDraft() = runTest(dispatcher) {
        val model=vm(); model.addItem(); model.addItem()
        val target=model.uiState.value.items.last(); val draft=ManualExpenseDraft("Typed draft","10.00","CAD","2026-10-05","keep notes")
        model.updateSource(target.itemId,true,draft); model.updateSource(target.itemId,false,draft)
        model.prepareImages(listOf("content://target"), target.itemId);advanceUntilIdle()
        assertEquals(2,model.uiState.value.items.size)
        assertEquals(target.itemId,model.uiState.value.items.last().itemId)
        assertEquals(draft,model.uiState.value.items.last().draft)
        assertEquals("content://target",model.uiState.value.items.last().sourceUri)
        assertEquals(BatchItemStatus.EMPTY,model.uiState.value.items.first().status)
    }
    @Test fun providerFailureAllowsExplicitValidatedManualTypedFallback() = runTest(dispatcher) {
        val model=vm(typed={error("model unavailable")});model.addTypedItem()
        val id=model.uiState.value.items.first().itemId
        model.updateSource(id,true,ManualExpenseDraft("Shop","10.00","CAD","Oct 5, 2026","typed notes"))
        model.extractAll(null);advanceUntilIdle();assertEquals(BatchItemStatus.FAILED,model.uiState.value.items.first().status)
        model.useManualValues(id)
        val item=model.uiState.value.items.first();val review=item.transactions.first().review
        assertEquals(BatchItemStatus.SUCCEEDED,item.status);assertEquals("Oct 5, 2026",review.receiptDate)
        assertEquals("typed notes",review.notes);assertEquals("manual",review.extractionStatus);assertEquals(ReceiptSourceType.TYPED,review.sourceType)
        assertTrue(review.expenseValidationErrors().isNotEmpty())
    }

    @Test fun singleFieldDraftsDispatchUnchangedAndRetryPreservesOriginalNotes() = runTest(dispatcher) {
        val text = "  Walmrat, Oct 7 2026, 15+50 Canadian dollars\n"
        for (draft in listOf(ManualExpenseDraft(merchant=text,currency=""), ManualExpenseDraft(currency="",notes=text))) {
            val received=mutableListOf<ManualExpenseDraft>();var fail=true
            val model=vm(typed={ received+=it; if(fail) error("provider unavailable") else listOf(review().copy(merchantName="Walmart",totalAmount="65",receiptDate="2026-10-07",notes=it.notes)) })
            model.addTypedItem();val id=model.uiState.value.items.single().itemId
            model.updateSource(id,true,draft);model.extractAll(null);advanceUntilIdle()
            assertEquals(BatchItemStatus.FAILED,model.uiState.value.items.single().status)
            assertEquals(draft,model.uiState.value.items.single().draft)
            fail=false;model.retryItem(id,null);advanceUntilIdle()
            assertEquals(listOf(draft,draft),received)
            val item=model.uiState.value.items.single()
            assertEquals(draft,item.draft);assertEquals("65",item.transactions.single().review.totalAmount)
            assertEquals(draft.notes,item.transactions.single().review.notes)
        }
    }

    @Test fun manualFallbackRequiresStructuredCorrectionsBeforeExportAndKeepsSource() = runTest(dispatcher) {
        val model=vm(typed={error("unresolved")});model.addTypedItem();val id=model.uiState.value.items.single().itemId
        val draft=ManualExpenseDraft(currency="",notes="Walmrat, Oct 7 2026, 15+50 Canadian dollars")
        model.updateSource(id,true,draft);model.extractAll(null);advanceUntilIdle();model.useManualValues(id)
        val tx=model.uiState.value.items.single().transactions.single()
        model.exportTransaction(id,tx.id);advanceUntilIdle()
        assertNull(model.uiState.value.items.single().transactions.single().review.exportExpenseId)
        model.updateTransaction(id,tx.id,tx.review.copy(merchantName="Walmart",receiptDate="2026-10-07",totalAmount="65",currency="CAD"))
        model.exportTransaction(id,tx.id);advanceUntilIdle()
        assertNotNull(model.uiState.value.items.single().transactions.single().review.exportExpenseId)
        assertEquals(draft,model.uiState.value.items.single().draft)
    }
    @Test fun restorationKeepsStableIdsAndDoesNotOverwriteSavedBatchOnDebugOff() = runTest(dispatcher) {
        val restored=BatchItemState(itemId="stable",noImage=true,status=BatchItemStatus.SUCCEEDED,transactions=listOf(BatchTransaction("tx",0,review().copy(rawModelOutput="private",exportExpenseId="saved"))))
        var saved:List<BatchItemState>?=null
        val store=object:PreparedBatchStore {
            override suspend fun load()=listOf(restored)
            override suspend fun save(items:List<BatchItemState>){saved=items}
            override suspend fun stage(itemId:String,image:ReceiptImage)="file://staged"
            override suspend fun read(uri:String,mimeType:String)=image
        }
        val model=vm(store=store);advanceUntilIdle()
        assertEquals("stable",saved?.firstOrNull()?.itemId)
        assertEquals("tx",model.uiState.value.items.first().transactions.first().id)
        assertEquals("saved",model.uiState.value.items.first().transactions.first().review.exportExpenseId)
        assertEquals("",saved?.firstOrNull()?.transactions?.first()?.review?.rawModelOutput)
    }

    @Test fun debugFailureRawIsRetainedOnlyWhileDebugEnabled() = runTest(dispatcher) {
        val settings=Settings();settings.setDebugOutputEnabled(true)
        val model=ReceiptWorkflowViewModel(settings,{_,_,_,_,_->throw BatchExtractionFailure("invalid output","{broken JSON")},{sampleRecord()},{sampleRecord()},dispatcher,prepareImage={_,_->image})
        model.prepareImages(listOf("content://a"));advanceUntilIdle();model.extractAll(null);advanceUntilIdle()
        assertEquals("{broken JSON",model.uiState.value.items.first().rawFailureOutput)
        settings.setDebugOutputEnabled(false);advanceUntilIdle();assertEquals("",model.uiState.value.items.first().rawFailureOutput)
    }

    @Test fun unreadableImagePreparationIsFailedAndNeverEligibleForInference() = runTest(dispatcher) {
        var calls=0
        val model=ReceiptWorkflowViewModel(Settings(),{_,_,_,_,_->calls++;listOf(review())},{sampleRecord()},{sampleRecord()},dispatcher,prepareImage={_,_->error("Image is unreadable")})
        model.prepareImages(listOf("content://bad"));advanceUntilIdle();model.extractAll(null);advanceUntilIdle()
        model.retryItem(model.uiState.value.items.first().itemId, null);advanceUntilIdle()
        assertEquals(0,calls);assertEquals(BatchItemStatus.FAILED,model.uiState.value.items.first().status)
        assertFalse(model.uiState.value.items.first().eligible);assertContains(model.uiState.value.items.first().error.orEmpty(),"unreadable")
    }

    @Test fun lifecycleConfigurationStopRetainsActiveRunButActualBackgroundStopCancelsIt() = runTest(dispatcher) {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val model=vm(extract={entered.complete(Unit);release.await();listOf(review())})
        var changingConfiguration=true
        val owner=object:androidx.lifecycle.LifecycleOwner {
            val registry=androidx.lifecycle.LifecycleRegistry.createUnsafe(this)
            override val lifecycle:androidx.lifecycle.Lifecycle get()=registry
        }
        owner.registry.addObserver(ReceiptWorkflowLifecycleObserver(model::setForeground) { changingConfiguration })
        owner.registry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_CREATE)
        owner.registry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
        owner.registry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
        model.prepareImages(listOf("content://rotation"));advanceUntilIdle();model.extractAll(null);runCurrent();assertTrue(entered.isCompleted)
        owner.registry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_PAUSE)
        owner.registry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_STOP)
        runCurrent();assertTrue(model.uiState.value.extracting)
        owner.registry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
        owner.registry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
        changingConfiguration=false
        owner.registry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_PAUSE)
        owner.registry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_STOP)
        release.complete(Unit);advanceUntilIdle()
        assertFalse(model.uiState.value.extracting);assertEquals(BatchItemStatus.CANCELLED,model.uiState.value.items.single().status)
        assertTrue(model.uiState.value.items.single().transactions.isEmpty())
    }

    @Test fun exportAllRequiresEveryRemainingInputReadyAndEveryResultValid() = runTest(dispatcher) {
        var calls=0
        val model=vm(batch={_,_->calls++;emptyList()})
        model.prepareImages(listOf("content://ready"));advanceUntilIdle();model.extractAll(null);advanceUntilIdle()
        model.addTypedItem()
        assertFalse(model.uiState.value.canExportAll)
        model.exportAll();advanceUntilIdle();assertEquals(0,calls)
        val typed=model.uiState.value.items.last()
        model.updateSource(typed.itemId,true,ManualExpenseDraft(notes="Lunch at Shop, Oct 5 2026, ten dollars"))
        model.extractAll(null);advanceUntilIdle()
        assertTrue(model.uiState.value.canExportAll)
        val tx=model.uiState.value.items.last().transactions.single()
        model.updateTransaction(typed.itemId,tx.id,tx.review.copy(totalAmount="ten"))
        model.exportAll();advanceUntilIdle();assertEquals(0,calls)
        assertContains(model.uiState.value.batchMessage.orEmpty(),"invalid review fields")
        model.updateTransaction(typed.itemId,tx.id,tx.review.copy(totalAmount="10"))
        model.updateSource(typed.itemId,true,ManualExpenseDraft(notes="changed expense"))
        assertFalse(model.uiState.value.canExportAll)
    }

    @Test fun exportAllSendsOneOrderedMixedListFreezesSnapshotsAndExcludesCompletedTransactions() = runTest(dispatcher) {
        val batches=mutableListOf<List<Pair<String,ReceiptReviewState>>>()
        val release=CompletableDeferred<Unit>()
        val model=vm(extract={listOf(review(),review().copy(merchantName="Other Shop"))},typed={listOf(review().copy(notes=it.notes))},batch={reviews,saved->
            batches+=reviews
            val records=reviews.map { (id,r)->sampleRecord().copy(expenseId=id,
                sourceType=r.sourceType,schemaVersion=if(r.sourceType==ReceiptSourceType.TYPED)3 else 2,
                sourceImageUri=r.sourceImageUri.takeIf { r.sourceType==ReceiptSourceType.IMAGE },
                receiptImageRelativePath=if(r.sourceType==ReceiptSourceType.TYPED)null else sampleRecord().receiptImageRelativePath,
                normalizedImageLocalPath=null,extractionStatus="manual",notes=r.notes,status=ReceiptExportStatus.READY) }
            records.forEach(saved)
            release.await()
            records.map { it.copy(status=ReceiptExportStatus.EXPORTED) }
        })
        model.prepareImages(listOf("content://image"));advanceUntilIdle()
        model.addTypedItem();val typed=model.uiState.value.items.last()
        model.updateSource(typed.itemId,true,ManualExpenseDraft(notes="Narrated expense"))
        model.extractAll(null);advanceUntilIdle()
        val ids=model.uiState.value.items.flatMap { it.transactions }.map { it.id }
        model.exportAll();model.exportAll();runCurrent()
        assertEquals(1,batches.size)
        assertEquals(ids,batches.single().map { it.first })
        assertEquals(listOf(ReceiptSourceType.IMAGE,ReceiptSourceType.IMAGE,ReceiptSourceType.TYPED),batches.single().map { it.second.sourceType })
        assertEquals("Narrated expense",batches.single().last().second.notes)
        assertTrue(model.uiState.value.exporting)
        assertTrue(model.uiState.value.items.all { it.immutable })
        model.updateSource(typed.itemId,true,ManualExpenseDraft(notes="Cannot change saved snapshot"))
        assertEquals("Narrated expense",model.uiState.value.items.last().draft.notes)
        release.complete(Unit);advanceUntilIdle()
        assertTrue(model.uiState.value.items.all { it.transactions.all { tx->tx.review.exportComplete } })
        val preview=model.uiState.value.items.first().transactions.first().review.exportJsonPreview!!
        val parsedPreview = assertIs<com.hugo.smartexpense.extraction.ParseResult.Valid>(
            com.hugo.smartexpense.extraction.ReceiptExtractionParser().parse(preview))
        assertEquals(3, parsedPreview.values.size)
        ids.forEach { assertContains(preview, "\"expenseId\":\"$it\"") }
        assertTrue(model.uiState.value.items.flatMap { it.transactions }.all { it.review.exportJsonPreview==preview })
        assertFalse(model.uiState.value.canExportAll)
        model.exportAll();advanceUntilIdle();assertEquals(1,batches.size)
        assertContains(model.uiState.value.batchMessage.orEmpty(),"no remaining transactions")
        model.addTypedItem();val additional=model.uiState.value.items.last()
        model.updateSource(additional.itemId,true,ManualExpenseDraft(notes="New expense"))
        model.extractAll(null);advanceUntilIdle();model.exportAll();advanceUntilIdle()
        assertEquals(2,batches.size)
        assertEquals(listOf(additional.itemId),model.uiState.value.items.filter { it.transactions.any { tx->tx.id in batches.last().map { pair->pair.first } } }.map { it.itemId })
        assertEquals(1,batches.last().size)
    }

    @Test fun failedCombinedExportKeepsSavedIdentityAndOffersExplicitRetry() = runTest(dispatcher) {
        var id:String?=null
        val model=vm(batch={reviews,saved->
            id=reviews.single().first
            val record=sampleRecord().copy(expenseId=id!!,status=ReceiptExportStatus.READY)
            saved(record)
            listOf(record.copy(status=ReceiptExportStatus.FAILED))
        })
        model.prepareImages(listOf("content://image"));advanceUntilIdle();model.extractAll(null);advanceUntilIdle()
        model.exportAll();advanceUntilIdle()
        assertEquals(id,model.uiState.value.items.single().transactions.single().review.exportExpenseId)
        assertFalse(model.uiState.value.items.single().transactions.single().review.exportComplete)
        assertTrue(model.uiState.value.canExportAll)
        assertFalse(model.uiState.value.exporting)
        assertContains(model.uiState.value.batchMessage.orEmpty(),"Retry the saved export")
    }

}
