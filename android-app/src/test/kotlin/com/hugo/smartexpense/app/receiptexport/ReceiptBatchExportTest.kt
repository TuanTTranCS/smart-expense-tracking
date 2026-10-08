package com.hugo.smartexpense.app.receiptexport

import com.hugo.smartexpense.app.ReceiptReviewState
import com.hugo.smartexpense.app.ReceiptSourceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.time.ZonedDateTime
import kotlin.test.*

class ReceiptBatchExportTest {
    private val image = ReceiptReviewState(receiptDate = "2026-10-07", merchantName = "Shop",
        totalAmount = "65", currency = "CAD", extractionStatus = "confirmed", sourceImageUri = "content://receipt/1")
    private val typed = image.copy(sourceType = ReceiptSourceType.TYPED, sourceImageUri = "", notes = "  Exact notes\n  ")
    private val reviews get() = listOf("aaaaaaaa-transaction" to image, "bbbbbbbb-transaction" to typed)

    @Test fun mixedBatchUsesOnePathAndImmutableSnapshotOnMemberRetryAfterRestart() = runTest {
        val repo = MemoryRepository()
        var attempts = 0
        val published = mutableListOf<List<ReceiptExportRecord>>()
        val publisher = batchPublisher { _, records ->
            published += records.map { it.first }
            assertNotNull(records[0].second)
            assertNull(records[1].second)
            if (attempts++ == 0) throw ReceiptExportException(ReceiptExportFailure.RATE_LIMITED, 30)
        }
        val callbacks = mutableListOf<ReceiptExportRecord>()
        val failed = controller(repo, publisher).startBatch(reviews) { callbacks += it }
        assertEquals(2, callbacks.size)
        assertTrue(failed.all { it.status == ReceiptExportStatus.FAILED && it.retryAfterSeconds == 30L })
        val completed = controller(repo, publisher).retry(failed[1].expenseId)
        assertEquals(ReceiptExportStatus.EXPORTED, completed.status)
        assertEquals(published[0].map { it.toHandoffJson() }, published[1].map { it.toHandoffJson() })
        assertEquals(1, published[0].map { it.jsonRelativePath }.distinct().size)
        assertTrue(completed.jsonRelativePath.contains("expense_batch_20261007_"))
        assertEquals(typed.notes, completed.notes)
        assertEquals("manual", completed.extractionStatus)
        assertTrue(controller(repo, publisher).batchRecordsForExpense(completed.expenseId).all { it.status == ReceiptExportStatus.EXPORTED })
        controller(repo, publisher).startBatch(reviews.map { it.first to it.second.copy(merchantName = "Changed") })
        assertEquals(2, attempts)
    }

    @Test fun failedBatchCannotBeExtendedReducedReorderedOrCombinedWithSingleton() = runTest {
        val repo = MemoryRepository()
        val controller = controller(repo, batchPublisher { _, _ -> throw ReceiptExportException(ReceiptExportFailure.NETWORK_UNAVAILABLE) })
        controller.startBatch(reviews)
        assertFailsWith<IllegalArgumentException> { controller.startBatch(reviews + ("cccccccc-transaction" to typed)) }
        assertFailsWith<IllegalArgumentException> { controller.startBatch(reviews.take(1)) }
        assertFailsWith<IllegalArgumentException> { controller.startBatch(reviews.reversed()) }
        val singletonRepo = MemoryRepository()
        singletonRepo.save(sampleRecord())
        assertFailsWith<IllegalArgumentException> { controller(singletonRepo, batchPublisher { _, _ -> }).startBatch(listOf(sampleRecord().expenseId to image)) }
    }

    @Test fun invalidReviewIsRejectedBeforeSavingAnyBatchOrPreparingImages() = runTest {
        val repo = MemoryRepository()
        assertFailsWith<IllegalArgumentException> {
            controller(repo, batchPublisher { _, _ -> error("No publication") }).startBatch(
                listOf("aaaaaaaa-transaction" to image, "bbbbbbbb-transaction" to typed.copy(totalAmount = "15+50")))
        }
        assertTrue(repo.records.isEmpty())
        assertNull(repo.batch)
    }

    @Test fun cancellationRetainsAllMembersAndRetryResumesOriginalManifest() = runTest {
        val repo = MemoryRepository()
        var calls = 0
        val publisher = batchPublisher { _, _ -> if (calls++ == 0) throw CancellationException("cancel") }
        assertFailsWith<CancellationException> { controller(repo, publisher).startBatch(reviews) }
        assertEquals(2, repo.records.size)
        assertTrue(repo.records.values.none { it.status == ReceiptExportStatus.EXPORTED })
        controller(repo, publisher).retry(reviews[0].first)
        assertEquals(2, calls)
    }

    @Test fun committedManifestRecoversPartialMemberSuccessWithoutRepublishing() = runTest {
        val repo = MemoryRepository()
        var calls = 0
        repo.failSecondCompletedSave = true
        val publisher = batchPublisher { _, _ -> calls++ }
        assertFailsWith<IllegalStateException> { controller(repo, publisher).startBatch(reviews) }
        assertTrue(requireNotNull(repo.batch).committed)
        assertEquals(1, repo.records.values.count { it.status == ReceiptExportStatus.EXPORTED })
        val restored = controller(repo, publisher).savedRecordForExpense(reviews[1].first)
        assertEquals(ReceiptExportStatus.EXPORTED, restored?.status)
        assertNull(restored?.failure)
        assertNull(restored?.retryAfterSeconds)
        assertEquals(1, calls)
        controller(repo, publisher).retry(reviews[1].first)
        assertTrue(repo.records.values.all { it.status == ReceiptExportStatus.EXPORTED })
        assertEquals(1, calls)
    }

    @Test fun localRestorationReturnsPendingSnapshotAndMissingIdentityWithoutPublishing() = runTest {
        val repo = MemoryRepository()
        val controller = controller(repo, batchPublisher { _, _ -> throw ReceiptExportException(ReceiptExportFailure.RATE_LIMITED, 30) })
        val failed = controller.startBatch(reviews)
        assertEquals(failed[1], controller.savedRecordForExpense(reviews[1].first))
        assertNull(controller.savedRecordForExpense("missing"))
    }

    @Test fun typedMembersWithCanonicalFilenameCollisionsAreRejectedBeforePersistence() = runTest {
        val repo = MemoryRepository()
        assertFailsWith<IllegalArgumentException> {
            controller(repo, batchPublisher { _, _ -> error("No publication") }).startBatch(
                listOf("aaaaaaaa-first" to typed, "aaaaaaaa-second" to typed))
        }
        assertTrue(repo.records.isEmpty())
        assertNull(repo.batch)
    }

    private fun controller(repo: ReceiptExportRepository, publisher: ReceiptExportPublisher) = ReceiptExportController(
        repo, ReceiptExportImagePreparer { _, name -> PreparedReceiptImage(name, 3) },
        ReceiptExportImageReader { byteArrayOf(1, 2, 3) }, publisher, { "device" },
        now = { ZonedDateTime.parse("2026-10-07T14:05:07-07:00") })

    private fun batchPublisher(action: suspend (ReceiptExportBatch, List<Pair<ReceiptExportRecord, ByteArray?>>) -> Unit) = object : ReceiptExportPublisher {
        override suspend fun publish(record: ReceiptExportRecord, imageBytes: ByteArray?) = error("Batch member must never publish singly")
        override suspend fun publishBatch(batch: ReceiptExportBatch, records: List<Pair<ReceiptExportRecord, ByteArray?>>) = action(batch, records)
    }

    private class MemoryRepository : ReceiptExportRepository {
        val records = linkedMapOf<String, ReceiptExportRecord>()
        var batch: ReceiptExportBatch? = null
        var failSecondCompletedSave = false
        override suspend fun save(record: ReceiptExportRecord) {
            if (failSecondCompletedSave && record.status == ReceiptExportStatus.EXPORTED && records.values.any { it.status == ReceiptExportStatus.EXPORTED }) {
                failSecondCompletedSave = false
                error("Simulated process interruption after first completed member")
            }
            records[record.expenseId] = record
        }
        override suspend fun get(expenseId: String) = records[expenseId]
        override suspend fun getBatch(batchId: String) = batch?.takeIf { it.batchId == batchId }
        override suspend fun createBatch(batch: ReceiptExportBatch, records: List<ReceiptExportRecord>) {
            this.batch = batch
            records.forEach { this.records[it.expenseId] = it }
        }
        override suspend fun markBatchCommitted(batchId: String) { batch = requireNotNull(batch).copy(committed = true) }
    }
}
