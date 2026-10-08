package com.hugo.smartexpense.app.receipt.ui

import com.hugo.smartexpense.app.ReceiptReviewState
import com.hugo.smartexpense.app.ReceiptSourceType
import com.hugo.smartexpense.app.receiptexport.ReceiptExportRecord
import com.hugo.smartexpense.app.receiptexport.ReceiptExportStatus
import com.hugo.smartexpense.app.receiptexport.sampleRecord
import com.hugo.smartexpense.app.receiptexport.toHandoffJson
import com.hugo.smartexpense.app.settings.data.AppSettings
import com.hugo.smartexpense.app.settings.data.AppSettingsRepository
import com.hugo.smartexpense.extraction.ReceiptImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class BatchExportRecoveryTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun teardown() = Dispatchers.resetMain()

    private class Settings : AppSettingsRepository {
        override val settings = MutableStateFlow(AppSettings())
        override fun setReduceOversizedImages(enabled: Boolean) {}
        override fun setDebugOutputEnabled(enabled: Boolean) {}
        override fun setDeviceNameOverride(name: String) {}
    }

    private val draft = ManualExpenseDraft(notes = "  I paid ten dollars at Shop on Oct 5, 2026.\n", currency = "")
    private val review = ReceiptReviewState(receiptDate = "2026-10-05", merchantName = "Shop",
        totalAmount = "10", currency = "CAD", extractionStatus = "manual", manualEntryRequired = false,
        sourceType = ReceiptSourceType.TYPED)
    private val item = BatchItemState(itemId = "item", noImage = true, draft = draft,
        status = BatchItemStatus.SUCCEEDED, transactions = listOf(BatchTransaction("stable-transaction", 0, review)))

    private fun record(status: ReceiptExportStatus = ReceiptExportStatus.EXPORTED) = sampleRecord().copy(
        expenseId = "stable-transaction", receiptDate = "2026-10-07", merchantName = "Saved Shop",
        totalAmount = "65", currency = "USD", notes = "saved reviewed notes", extractionStatus = "manual",
        sourceType = ReceiptSourceType.TYPED, schemaVersion = 3, sourceImageUri = null,
        receiptImageRelativePath = null, normalizedImageLocalPath = null, status = status)

    private class Store(var items: List<BatchItemState>) : PreparedBatchStore {
        var saveGate: CompletableDeferred<Unit>? = null
        var failSaves = false
        val history = mutableListOf<List<BatchItemState>>()
        override suspend fun load() = items
        override suspend fun save(items: List<BatchItemState>) {
            if (failSaves) error("Storage unavailable")
            val gate = saveGate
            saveGate = null
            gate?.await()
            this.items = items
            history += items
        }
        override suspend fun stage(itemId: String, image: ReceiptImage): String = error("No image expected")
        override suspend fun read(uri: String, mimeType: String): ReceiptImage = error("No image expected")
    }

    @Test fun restoreReconcilesLostDraftExportMarkersAndKeepsRawSourceWithoutInference() = runTest(dispatcher) {
        val store = Store(listOf(item)) // Simulates death before onRecordSaved's draft update persisted.
        val saved = record()
        val lookups = mutableListOf<String>()
        val model = ReceiptWorkflowViewModel(Settings(), { _, _, _, _, _ -> error("No inference on restore") },
            { error("No publication on restore") }, { error("No retry on restore") }, dispatcher,
            batchStore = store, loadSavedExportRecord = { lookups += it; saved })

        advanceUntilIdle()

        val restored = model.uiState.value.items.single()
        val transaction = restored.transactions.single()
        assertEquals(listOf("stable-transaction"), lookups)
        assertEquals(item.itemId, restored.itemId)
        assertEquals(item.transactions.single().id, transaction.id)
        assertEquals(draft, restored.draft)
        assertTrue(restored.immutable)
        assertTrue(transaction.review.exportComplete)
        assertEquals(saved.expenseId, transaction.review.exportExpenseId)
        assertEquals(saved.merchantName, transaction.review.merchantName)
        assertEquals(saved.receiptDate, transaction.review.receiptDate)
        assertEquals(saved.totalAmount, transaction.review.totalAmount)
        assertEquals(saved.currency, transaction.review.currency)
        assertEquals(saved.notes, transaction.review.notes)
        assertEquals(saved.toHandoffJson(), transaction.review.exportJsonPreview)
        model.updateSource(restored.itemId, true, ManualExpenseDraft(notes = "replacement"))
        model.updateTransaction(restored.itemId, transaction.id, transaction.review.copy(totalAmount = "99"))
        assertEquals(restored.draft, model.uiState.value.items.single().draft)
        assertEquals(transaction.review, model.uiState.value.items.single().transactions.single().review)
        assertFalse(model.uiState.value.canExportAll)
    }

    @Test fun reconciliationFailureKeepsDraftUntouchedAndBlocksInteraction() = runTest(dispatcher) {
        val store = Store(listOf(item))
        val model = ReceiptWorkflowViewModel(Settings(), { _, _, _, _, _ -> error("No inference") },
            { error("No publication") }, { error("No retry") }, dispatcher,
            batchStore = store, loadSavedExportRecord = { error("Export database unavailable") })
        advanceUntilIdle()
        model.addTypedItem()
        model.extractAll(null)
        model.exportAll()
        advanceUntilIdle()
        assertTrue(model.uiState.value.items.isEmpty())
        assertEquals(listOf(item), store.items)
        assertTrue(store.history.isEmpty())
        assertContains(model.uiState.value.batchMessage.orEmpty(), "could not be restored safely")
    }

    @Test fun bothExportActionsCheckpointLatestIdentityAndQueuedOldSaveCannotOverwriteIt() = runTest(dispatcher) {
        for (all in listOf(true, false)) {
            val store = Store(listOf(item))
            var calls = 0
            fun verifyCheckpoint(): ReceiptExportRecord {
                calls++
                val persisted = store.items.single().transactions.single()
                assertEquals("stable-transaction", persisted.id)
                assertEquals("30", persisted.review.totalAmount)
                return record().copy(totalAmount = "30")
            }
            val model = ReceiptWorkflowViewModel(Settings(), { _, _, _, _, _ -> error("No inference") },
                { verifyCheckpoint() }, { error("No retry") }, dispatcher, batchStore = store,
                startExportWithIdentity = { _, id, saved ->
                    assertEquals("stable-transaction", id)
                    verifyCheckpoint().also(saved)
                }, exportReviewedBatch = { reviews, saved ->
                    assertEquals(listOf("stable-transaction"), reviews.map { it.first })
                    listOf(verifyCheckpoint().also(saved))
                })
            advanceUntilIdle()
            val gate = CompletableDeferred<Unit>()
            store.saveGate = gate
            model.updateTransaction(item.itemId, "stable-transaction", review.copy(totalAmount = "20"))
            runCurrent() // The old background save now holds the save mutex.
            model.updateTransaction(item.itemId, "stable-transaction", review.copy(totalAmount = "30"))
            if (all) model.exportAll() else model.exportTransaction(item.itemId, "stable-transaction")
            runCurrent()
            assertTrue(model.uiState.value.exporting)
            assertEquals(0, calls)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, calls)
            assertEquals("30", store.items.single().transactions.single().review.totalAmount)
            assertEquals("stable-transaction", store.items.single().transactions.single().review.exportExpenseId)
            assertTrue(store.items.single().transactions.single().review.exportComplete)
        }
    }

    @Test fun failedCheckpointPreventsPublication() = runTest(dispatcher) {
        val store = Store(listOf(item))
        var calls = 0
        val model = ReceiptWorkflowViewModel(Settings(), { _, _, _, _, _ -> error("No inference") },
            { calls++; record() }, { error("No retry") }, dispatcher, batchStore = store,
            exportReviewedBatch = { _, _ -> calls++; listOf(record()) })
        advanceUntilIdle()
        store.failSaves = true
        model.exportAll()
        advanceUntilIdle()
        model.exportTransaction(item.itemId, "stable-transaction")
        advanceUntilIdle()
        assertEquals(0, calls)
        assertFalse(model.uiState.value.exporting)
        assertNull(model.uiState.value.items.single().transactions.single().review.exportExpenseId)
    }

    @Test fun failedExtractionStatusIsNotReadyForExportAll() {
        val state = ReceiptWorkflowUiState(items = listOf(item.copy(transactions = listOf(
            BatchTransaction("failed", 0, review.copy(extractionStatus = "failed"))))))
        assertFalse(state.canExportAll)
        assertContains(state.exportAllBlockers.single(), "invalid review fields")
    }
}
