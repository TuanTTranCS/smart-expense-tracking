package com.hugo.smartexpense.app.receipt.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hugo.smartexpense.app.ReceiptReviewState
import com.hugo.smartexpense.app.receiptexport.*
import com.hugo.smartexpense.app.settings.data.*
import com.hugo.smartexpense.extraction.ReceiptImage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HistoryWorkflowTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: ReceiptExportDatabase
    private lateinit var repository: RoomReceiptExportRepository
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ReceiptExportDatabase::class.java)
            .allowMainThreadQueries().setQueryCoroutineContext(dispatcher).build()
        repository = RoomReceiptExportRepository(db.receiptExportDao())
    }
    @After fun teardown() { db.close(); Dispatchers.resetMain() }
    private class Settings : AppSettingsRepository {
        override val settings = MutableStateFlow(AppSettings())
        override fun setReduceOversizedImages(enabled: Boolean) {}
        override fun setDebugOutputEnabled(enabled: Boolean) {}
        override fun setThemeMode(mode: com.hugo.smartexpense.app.settings.data.AppThemeMode) {}
        override fun setDeviceNameOverride(name: String) {}
    }
    private val remote = object : ReceiptCorrectionRemote {
        override suspend fun inspect(record: ReceiptExportRecord) = ReceiptRemoteObservation(ReceiptRemoteStatus.UNAVAILABLE, "now")
        override suspend fun publish(operation: ReceiptCorrectionOperation): ReceiptCorrectionAcknowledgement = error("not used")
    }
    private fun model(fail: Boolean = false, images: ReceiptHistoryImageLoader? = null) = ReceiptWorkflowViewModel(Settings(),
        { _, _, _, _, _ -> listOf("First", "Second").map { merchant -> ReceiptReviewState(receiptDate = "2026-10-16",
            merchantName = merchant, totalAmount = "12.00", sourceImageUri = "content://receipt", manualEntryRequired = false) } },
        { error("identity required") }, { error("not used") }, dispatcher,
        prepareImage = { _, _ -> ReceiptImage("receipt.jpg", byteArrayOf(1), "image/jpeg") },
        startExportWithIdentity = { review, id, saved -> sampleRecord().copy(expenseId = id, merchantName = review.merchantName,
            status = if (fail) ReceiptExportStatus.FAILED else ReceiptExportStatus.EXPORTED).also { record -> repository.save(record); saved(record) } },
        historyController = ReceiptHistoryController(repository, RoomReceiptHistoryStore(db.receiptExportDao()), remote),
        historyImageLoader = images)

    @Test fun previewDoesNotChangePublishedValuesDraftsOrProcessingState() = runTest(dispatcher) {
        val record = sampleRecord().copy(status = ReceiptExportStatus.EXPORTED, exportAccountId = "account", exportDriveId = "drive")
        repository.save(record)
        val store = RoomReceiptHistoryStore(db.receiptExportDao())
        val draft = ReceiptCorrectionDraft.from(record).copy(merchantName = "Unsaved correction")
        store.saveDraft(draft)
        val requests = mutableListOf<Triple<ReceiptExportRecord, Boolean, Boolean>>()
        val expected = ReceiptHistoryImageResult.Available(byteArrayOf(1, 2, 3))
        val model = model(images = ReceiptHistoryImageLoader { row, download, thumbnail ->
            requests += Triple(row, download, thumbnail)
            expected
        })
        advanceUntilIdle()
        val before = model.uiState.value
        assertSame(expected, model.loadHistoryImage(record, false, true))
        assertSame(expected, model.loadHistoryImage(record, true, false))
        assertEquals(listOf(Triple(record, false, true), Triple(record, true, false)), requests)
        assertEquals(before, model.uiState.value)
        assertEquals(record, repository.get(record.expenseId))
        assertEquals(draft, store.draft(record.expenseId))
        model.deleteHistory(record.expenseId)
        runCurrent()
        assertTrue(model.uiState.value.history.isEmpty())
        assertIs<ReceiptHistoryImageResult.Unavailable>(model.loadHistoryImage(record, true, false))
        assertEquals(2, requests.size)
        model.undoDeleteHistory()
        runCurrent()
        assertSame(expected, model.loadHistoryImage(record, false, true))
        assertEquals(draft, store.draft(record.expenseId))
    }

    @Test fun removedOrChangedReceiptCannotDeliverALatePreview() = runTest(dispatcher) {
        val record = sampleRecord().copy(status = ReceiptExportStatus.EXPORTED)
        repository.save(record)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val model = model(images = ReceiptHistoryImageLoader { _, _, _ ->
            started.complete(Unit)
            finish.await()
            ReceiptHistoryImageResult.Available(byteArrayOf(1))
        })
        advanceUntilIdle()
        val result = async { model.loadHistoryImage(record, true, false) }
        started.await()
        repository.save(record.copy(receiptImageRelativePath = "changed.jpg"))
        runCurrent()
        finish.complete(Unit)
        assertIs<ReceiptHistoryImageResult.Unavailable>(result.await())
    }

    @Test fun screenCancellationPropagatesToPreviewWork() = runTest(dispatcher) {
        val record = sampleRecord().copy(status = ReceiptExportStatus.EXPORTED)
        repository.save(record)
        var cancelled = false
        val model = model(images = ReceiptHistoryImageLoader { _, _, _ ->
            try { awaitCancellation() } finally { cancelled = true }
        })
        advanceUntilIdle()
        val request = launch { model.loadHistoryImage(record, true, false) }
        runCurrent()
        request.cancelAndJoin()
        assertTrue(cancelled)
        assertEquals(record, repository.get(record.expenseId))
    }

    @Test fun successfulTransactionMovesIndependentlyAndRetainsSiblingSource() = runTest(dispatcher) {
        val model = model()
        model.prepareImages(listOf("content://receipt")); advanceUntilIdle()
        model.extractAll(null); advanceUntilIdle()
        val item = model.uiState.value.items.single()
        assertEquals(2, item.transactions.size)
        model.exportTransaction(item.itemId, item.transactions.first().id); advanceUntilIdle()
        val remaining = model.uiState.value.items.single()
        assertEquals(listOf("Second"), remaining.transactions.map { it.review.merchantName })
        assertEquals("content://receipt", remaining.sourceUri)
        model.exportTransaction(remaining.itemId, remaining.transactions.single().id); advanceUntilIdle()
        assertTrue(model.uiState.value.items.isEmpty())
        assertEquals(ReceiptExportStatus.EXPORTED, repository.get(item.transactions.first().id)!!.status)
    }
    @Test fun failedInitialPublicationRemainsActiveAndRecoverable() = runTest(dispatcher) {
        val model = model(fail = true)
        model.prepareImages(listOf("content://receipt")); advanceUntilIdle()
        model.extractAll(null); advanceUntilIdle()
        val item = model.uiState.value.items.single()
        model.exportTransaction(item.itemId, item.transactions.first().id); advanceUntilIdle()
        assertEquals(2, model.uiState.value.items.single().transactions.size)
        assertNotNull(model.uiState.value.items.single().transactions.first().review.exportExpenseId)
        assertFalse(model.uiState.value.items.single().transactions.first().review.exportComplete)
    }
}
