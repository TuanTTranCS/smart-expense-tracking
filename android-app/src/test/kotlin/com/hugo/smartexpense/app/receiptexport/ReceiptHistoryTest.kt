package com.hugo.smartexpense.app.receiptexport

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.json.JSONObject
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
class ReceiptHistoryTest {
    private lateinit var db: ReceiptExportDatabase
    private lateinit var repository: RoomReceiptExportRepository
    private lateinit var store: RoomReceiptHistoryStore
    private val original = sampleRecord().copy(status = ReceiptExportStatus.EXPORTED)
    private var pending = true
    private var failPublication = false
    private var account = "account"
    private val operations = mutableListOf<ReceiptCorrectionOperation>()
    private val remote = object : ReceiptCorrectionRemote {
        override suspend fun inspect(record: ReceiptExportRecord): ReceiptRemoteObservation {
            val payload = JSONObject(record.toHandoffJson()).put("futureField", "keep").toString()
            return ReceiptRemoteObservation(if (pending) ReceiptRemoteStatus.PENDING else ReceiptRemoteStatus.PROCESSED,
                "2026-10-16T10:00:00Z", account, "drive", "item", "etag", "fingerprint", payloadJson = payload, correctionsEnabled = true)
        }
        override suspend fun publish(operation: ReceiptCorrectionOperation): ReceiptCorrectionAcknowledgement {
            operations += operation
            if (failPublication) error("offline")
            return ReceiptCorrectionAcknowledgement(operation.operationId, "2026-10-16T10:01:00Z")
        }
    }
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ReceiptExportDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = RoomReceiptExportRepository(db.receiptExportDao())
        store = RoomReceiptHistoryStore(db.receiptExportDao())
    }
    @After fun teardown() = db.close()
    private fun controller() = ReceiptHistoryController(repository, store, remote, { "2026-10-16T10:00:00Z" }, { "11111111-2222-3333-4444-555555555555" })

    @Test fun completedRowsOrderByCompletionOrExplicitLegacyCreationFallbackAndHideDurably() = runTest {
        repository.save(original)
        val newer = original.copy(expenseId = "newer", completedAt = "2026-10-16T00:00:00Z")
        repository.save(newer)
        repository.save(original.copy(expenseId = "failed", status = ReceiptExportStatus.FAILED))
        assertEquals(listOf("newer", original.expenseId), repository.observeCompleted().first().map { it.expenseId })
        controller().hide(original.expenseId, true)
        val recreated = RoomReceiptExportRepository(db.receiptExportDao())
        assertEquals(listOf("newer"), recreated.observeCompleted().first().map { it.expenseId })
        controller().hide(original.expenseId, false)
        assertEquals(2, recreated.observeCompleted().first().size)
        assertNull(recreated.get(original.expenseId)!!.completedAt)
    }
    @Test fun historyOrderingUsesActualInstantAcrossOffsetsAndLegacyFallback() = runTest {
        repository.save(original.copy(expenseId = "earlier", completedAt = "2026-11-01T01:50:00-07:00"))
        repository.save(original.copy(expenseId = "later", completedAt = "2026-11-01T01:10:00-08:00"))
        repository.save(original.copy(expenseId = "legacy", createdAt = "2026-11-01T12:00:00+02:00"))
        assertEquals(listOf("legacy", "later", "earlier"), repository.observeCompleted().first().map { it.expenseId })
    }
    @Test fun committedBatchRepairsInterruptedMemberWritesWithoutResurrectingHiddenRows() = runTest {
        val batch = ReceiptExportBatch("batch", original.createdAt, listOf("first", "second"), "batch.json", "batch.json.uploading")
        repository.createBatch(batch, listOf(original.copy(expenseId = "first", batchId = "batch", historyHidden = true),
            original.copy(expenseId = "second", batchId = "batch", status = ReceiptExportStatus.READY)))
        repository.markBatchCommitted("batch")
        assertEquals(listOf("second"), repository.observeCompleted().first().map { it.expenseId })
        assertEquals(ReceiptExportStatus.EXPORTED, repository.get("second")!!.status)
        assertTrue(repository.get("first")!!.historyHidden)
        assertEquals(batch.expenseIds, repository.getBatch("batch")!!.expenseIds)
    }
    @Test fun failureRetainsPublishedValuesAndImmutableRetryAfterStoreRecreation() = runTest {
        repository.save(original)
        val controller = controller()
        val draft = controller.openDraft(original.expenseId).copy(merchantName = "Corrected", notes = "new notes")
        controller.saveDraft(draft)
        failPublication = true
        assertFailsWith<IllegalStateException> { controller.publish(original.expenseId) }
        assertEquals(original, repository.get(original.expenseId))
        val saved = assertNotNull(RoomReceiptHistoryStore(db.receiptExportDao()).operation(original.expenseId))
        assertFailsWith<IllegalStateException> { controller.saveDraft(draft.copy(merchantName = "Mutate")) }
        failPublication = false
        val published = controller().publish(original.expenseId)
        assertEquals(2, operations.size)
        assertEquals(saved.payloadJson, operations.last().payloadJson)
        assertEquals("Corrected", published.merchantName)
        assertEquals(original.expenseId, published.expenseId)
        assertEquals(original.createdAt, published.createdAt)
        assertEquals(original.receiptImageRelativePath, published.receiptImageRelativePath)
        assertEquals("manual", published.extractionStatus)
        assertNull(store.operation(original.expenseId))
        assertNull(store.draft(original.expenseId))
        val payload = JSONObject(saved.payloadJson).getJSONObject("payload")
        assertEquals("keep", payload.getString("futureField"))
        assertEquals("pending", payload.getString("itemType"))
    }
    @Test fun processedCorrectionUsesProcessedDiscriminatorAndPreservesOriginalImage() = runTest {
        pending = false
        repository.save(original)
        val controller = controller()
        controller.openDraft(original.expenseId)
        controller.publish(original.expenseId)
        val operation = operations.single()
        assertEquals("processed", operation.itemType)
        val envelope = JSONObject(operation.payloadJson)
        assertEquals(1, envelope.getInt("correctionProtocolVersion"))
        assertEquals(original.receiptImageRelativePath, envelope.getJSONObject("payload").getString("receiptImageRelativePath"))
        assertEquals(operation.basePayloadJson, envelope.getString("expectedPayloadJson"))
    }
    @Test fun invalidDraftAndChangedAccountCannotPublish() = runTest {
        repository.save(original)
        val controller = controller()
        val draft = controller.openDraft(original.expenseId)
        controller.saveDraft(draft.copy(receiptDate = "2026-02-30"))
        assertFailsWith<IllegalArgumentException> { controller.publish(original.expenseId) }
        controller.saveDraft(draft)
        account = "other-account"
        assertFailsWith<IllegalStateException> { controller.publish(original.expenseId) }
        assertTrue(operations.isEmpty())
        assertEquals(original, repository.get(original.expenseId))
    }
    @Test fun cachedRemoteStatusSurvivesRepositoryRecreationAndCancelNeverPublishes() = runTest {
        repository.save(original)
        val controller = controller()
        controller.inspect(original.expenseId)
        val saved = RoomReceiptExportRepository(db.receiptExportDao()).get(original.expenseId)!!
        assertEquals(ReceiptRemoteStatus.PENDING, saved.lastRemoteStatus)
        assertEquals("2026-10-16T10:00:00Z", saved.lastRemoteCheckedAt)
        val draft = controller.openDraft(original.expenseId)
        controller.saveDraft(draft.copy(merchantName = "Discard"))
        controller.cancel(original.expenseId)
        assertNull(store.draft(original.expenseId))
        assertTrue(operations.isEmpty())
        assertEquals(original.merchantName, repository.get(original.expenseId)!!.merchantName)
    }
    @Test fun draftStartsFromVerifiedRemoteFieldsInsteadOfStaleLocalSnapshot() = runTest {
        repository.save(original.copy(merchantName = "Stale local"))
        val changedRemote = object : ReceiptCorrectionRemote {
            override suspend fun inspect(record: ReceiptExportRecord): ReceiptRemoteObservation {
                val payload = JSONObject(original.toHandoffJson()).put("merchantName", "Latest remote").toString()
                return ReceiptRemoteObservation(ReceiptRemoteStatus.PROCESSED, "now", "account", "drive",
                    payloadFingerprint = "changed", payloadJson = payload, revision = 2, correctionsEnabled = true)
            }
            override suspend fun publish(operation: ReceiptCorrectionOperation): ReceiptCorrectionAcknowledgement = error("unused")
        }
        val controller = ReceiptHistoryController(repository, store, changedRemote)
        assertEquals("Latest remote", controller.openDraft(original.expenseId).merchantName)
        assertEquals("Stale local", repository.get(original.expenseId)!!.merchantName)
    }
    @Test fun operationAndDraftSurviveActualDatabaseCloseAndReopen() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "history-operation-reopen.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, ReceiptExportDatabase::class.java, name).allowMainThreadQueries().build()
        var disk = open()
        try {
            var repo = RoomReceiptExportRepository(disk.receiptExportDao())
            var diskStore = RoomReceiptHistoryStore(disk.receiptExportDao())
            repo.save(original)
            val first = ReceiptHistoryController(repo, diskStore, remote)
            first.openDraft(original.expenseId)
            failPublication = true
            assertFailsWith<IllegalStateException> { first.publish(original.expenseId) }
            val frozen = diskStore.operation(original.expenseId)!!.payloadJson
            disk.close()
            disk = open()
            repo = RoomReceiptExportRepository(disk.receiptExportDao())
            diskStore = RoomReceiptHistoryStore(disk.receiptExportDao())
            assertEquals(frozen, diskStore.operation(original.expenseId)!!.payloadJson)
            assertNotNull(diskStore.draft(original.expenseId))
            failPublication = false
            ReceiptHistoryController(repo, diskStore, remote).publish(original.expenseId)
            assertEquals(frozen, operations.last().payloadJson)
            assertNull(diskStore.operation(original.expenseId))
        } finally { disk.close(); context.deleteDatabase(name) }
    }
    @Test fun correctionPreservesUnknownNestedNumericExtensionsExactly() = runTest {
        repository.save(original)
        val base = original.toHandoffJson().dropLast(1) + ",\"futureExtension\":{\"large\":9007199254740993,\"decimal\":0.123456789012345678901234567890}}"
        val preciseRemote = object : ReceiptCorrectionRemote {
            override suspend fun inspect(record: ReceiptExportRecord) = ReceiptRemoteObservation(ReceiptRemoteStatus.PENDING,
                "now", "account", "drive", payloadFingerprint = "stable", payloadJson = base, correctionsEnabled = true)
            override suspend fun publish(operation: ReceiptCorrectionOperation): ReceiptCorrectionAcknowledgement {
                val extension = (parseHandoffJson(operation.payloadJson) as JSONObject).getJSONObject("payload").getJSONObject("futureExtension")
                assertEquals("9007199254740993", extension.get("large").toString())
                assertEquals("0.123456789012345678901234567890", extension.get("decimal").toString())
                error("Retain operation to verify persisted retry")
            }
        }
        val controller = ReceiptHistoryController(repository, store, preciseRemote)
        controller.openDraft(original.expenseId)
        assertFailsWith<IllegalStateException> { controller.publish(original.expenseId) }
        val operation = assertNotNull(RoomReceiptHistoryStore(db.receiptExportDao()).operation(original.expenseId))
        val extension = (parseHandoffJson(operation.payloadJson) as JSONObject).getJSONObject("payload").getJSONObject("futureExtension")
        assertEquals("9007199254740993", extension.get("large").toString())
        assertEquals("0.123456789012345678901234567890", extension.get("decimal").toString())
    }
    @Test fun everyInitialHandoffIncludesPendingDiscriminator() {
        assertEquals("pending", JSONObject(original.toHandoffJson()).getString("itemType"))
        assertEquals("pending", JSONObject(sampleTypedRecord().toHandoffJson()).getString("itemType"))
    }
}
