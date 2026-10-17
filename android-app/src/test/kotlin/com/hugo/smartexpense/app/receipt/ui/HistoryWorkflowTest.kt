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
    private fun model(fail: Boolean = false) = ReceiptWorkflowViewModel(Settings(),
        { _, _, _, _, _ -> listOf("First", "Second").map { merchant -> ReceiptReviewState(receiptDate = "2026-10-16",
            merchantName = merchant, totalAmount = "12.00", sourceImageUri = "content://receipt", manualEntryRequired = false) } },
        { error("identity required") }, { error("not used") }, dispatcher,
        prepareImage = { _, _ -> ReceiptImage("receipt.jpg", byteArrayOf(1), "image/jpeg") },
        startExportWithIdentity = { review, id, saved -> sampleRecord().copy(expenseId = id, merchantName = review.merchantName,
            status = if (fail) ReceiptExportStatus.FAILED else ReceiptExportStatus.EXPORTED).also { record -> repository.save(record); saved(record) } },
        historyController = ReceiptHistoryController(repository, RoomReceiptHistoryStore(db.receiptExportDao()), remote))

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
