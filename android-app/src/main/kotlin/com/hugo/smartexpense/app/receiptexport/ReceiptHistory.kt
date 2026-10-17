package com.hugo.smartexpense.app.receiptexport

import com.hugo.smartexpense.app.ReceiptReviewState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZonedDateTime
import java.util.UUID

enum class ReceiptRemoteStatus { PENDING, PROCESSED, NEEDS_REVIEW, ERROR, MISSING, UNAVAILABLE }
data class ReceiptRemoteObservation(
    val status: ReceiptRemoteStatus,
    val checkedAt: String,
    val accountId: String? = null,
    val driveId: String? = null,
    val itemId: String? = null,
    val eTag: String? = null,
    val payloadFingerprint: String? = null,
    val message: String? = null,
    val payloadJson: String? = null,
    val revision: Int = 0,
    val correctionsEnabled: Boolean = false,
    val correctionRejected: Boolean = false,
) {
    val editable: Boolean get() = correctionsEnabled && status in setOf(ReceiptRemoteStatus.PENDING, ReceiptRemoteStatus.PROCESSED) &&
        !accountId.isNullOrBlank() && !payloadFingerprint.isNullOrBlank() && !payloadJson.isNullOrBlank()
}

interface ReceiptCorrectionRemote {
    suspend fun inspect(record: ReceiptExportRecord): ReceiptRemoteObservation
    suspend fun publish(operation: ReceiptCorrectionOperation): ReceiptCorrectionAcknowledgement
    suspend fun canDiscard(operation: ReceiptCorrectionOperation): Boolean = false
}
class ReceiptCorrectionNotPublishedException(message: String) : IllegalStateException(message)

data class ReceiptCorrectionAcknowledgement(val operationId: String, val publishedAt: String)
data class ReceiptCorrectionDraft(
    val expenseId: String,
    val merchantName: String,
    val receiptDate: String,
    val totalAmount: String,
    val currency: String,
    val merchantLocation: String,
    val notes: String,
    val accountId: String? = null,
    val driveId: String? = null,
    val baseFingerprint: String? = null,
    val expectedRevision: Int = 0,
) {
    fun review() = ReceiptReviewState(merchantName = merchantName, receiptDate = receiptDate,
        totalAmount = totalAmount, currency = currency, merchantLocation = merchantLocation,
        notes = notes, extractionStatus = "manual")
    fun applyTo(record: ReceiptExportRecord) = record.copy(merchantName = merchantName.trim(),
        receiptDate = receiptDate.trim(), totalAmount = java.math.BigDecimal(totalAmount.trim()).toPlainString(),
        currency = currency.trim().uppercase(), merchantLocation = merchantLocation.trim().ifBlank { null },
        notes = notes, extractionStatus = "manual")
    companion object {
        fun from(record: ReceiptExportRecord) = ReceiptCorrectionDraft(record.expenseId, record.merchantName,
            record.receiptDate, record.totalAmount, record.currency, record.merchantLocation.orEmpty(), record.notes)
        fun from(expenseId: String, review: ReceiptReviewState) = ReceiptCorrectionDraft(expenseId,
            review.merchantName, review.receiptDate, review.totalAmount, review.currency, review.merchantLocation, review.notes)
    }
}
data class ReceiptCorrectionOperation(
    val operationId: String,
    val expenseId: String,
    val baseFingerprint: String,
    val itemType: String,
    val payloadJson: String,
    val accountId: String,
    val createdAt: String,
    val corrected: ReceiptExportRecord,
    val observation: ReceiptRemoteObservation,
    val basePayloadJson: String,
    val expectedRevision: Int,
)
interface ReceiptHistoryStore {
    suspend fun draft(expenseId: String): ReceiptCorrectionDraft?
    suspend fun saveDraft(draft: ReceiptCorrectionDraft)
    suspend fun deleteDraft(expenseId: String)
    suspend fun operation(expenseId: String): ReceiptCorrectionOperation?
    suspend fun saveOperation(operation: ReceiptCorrectionOperation)
    suspend fun discardOperation(expenseId: String)
    /** Atomically replace the published snapshot and remove the acknowledged operation/draft. */
    suspend fun acknowledge(operation: ReceiptCorrectionOperation, publishedAt: String)
}

/** An operation is written before network publication. Failed attempts retain those exact bytes. */
class ReceiptHistoryController(
    val repository: ReceiptExportRepository,
    private val store: ReceiptHistoryStore,
    private val remote: ReceiptCorrectionRemote,
    private val now: () -> String = { ZonedDateTime.now().toString() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    // Serializes shared-batch corrections and local visibility changes along with singletons.
    private val mutex = Mutex()
    fun observeCompleted() = repository.observeCompleted()
    suspend fun reconcile() = repository.reconcileCommittedBatches()
    suspend fun inspect(expenseId: String): ReceiptRemoteObservation = mutex.withLock {
        val record = requireNotNull(repository.get(expenseId))
        val observation = remote.inspect(record)
        repository.save(record.copy(lastRemoteStatus = observation.status, lastRemoteCheckedAt = observation.checkedAt,
            lastRemoteMessage = observation.message))
        observation
    }
    suspend fun openDraft(expenseId: String): ReceiptCorrectionDraft = mutex.withLock {
        requireNotNull(repository.get(expenseId)).let { record ->
            require(record.status == ReceiptExportStatus.EXPORTED && !record.historyHidden)
            store.draft(expenseId) ?: run {
                val observation = remote.inspect(record)
                check(observation.editable) { observation.message ?: "OneDrive status does not allow editing." }
                check(record.exportAccountId == null || record.exportAccountId == observation.accountId) { "Reconnect the original OneDrive account." }
                val source = if (observation.correctionRejected) record else (parseHandoffJson(requireNotNull(observation.payloadJson)) as org.json.JSONObject).let { payload ->
                    record.copy(merchantName = payload.getString("merchantName"), receiptDate = payload.getString("receiptDate"),
                        totalAmount = payload.get("totalAmount").toString(), currency = payload.getString("currency"),
                        merchantLocation = if (payload.isNull("merchantLocation")) null else payload.optString("merchantLocation"),
                        notes = payload.optString("notes"))
                }
                ReceiptCorrectionDraft.from(source).copy(accountId = observation.accountId, driveId = observation.driveId,
                    baseFingerprint = observation.payloadFingerprint, expectedRevision = observation.revision).also { store.saveDraft(it) }
            }
        }
    }
    suspend fun saveDraft(draft: ReceiptCorrectionDraft) = mutex.withLock {
        check(store.operation(draft.expenseId) == null) { "Retry or cancel the saved correction before editing." }
        val saved = requireNotNull(store.draft(draft.expenseId))
        store.saveDraft(draft.copy(accountId = saved.accountId, driveId = saved.driveId, baseFingerprint = saved.baseFingerprint, expectedRevision = saved.expectedRevision))
    }
    suspend fun prepareForPublication(draft: ReceiptCorrectionDraft) = mutex.withLock {
        // A retry reuses its immutable operation, even if a late UI save was queued.
        if (store.operation(draft.expenseId) == null) {
            val saved = requireNotNull(store.draft(draft.expenseId))
            store.saveDraft(draft.copy(accountId = saved.accountId, driveId = saved.driveId, baseFingerprint = saved.baseFingerprint, expectedRevision = saved.expectedRevision))
        }
    }
    suspend fun hasOperation(expenseId: String): Boolean = mutex.withLock { store.operation(expenseId) != null }
    suspend fun cancel(expenseId: String) = mutex.withLock {
        store.operation(expenseId)?.let { operation ->
            check(remote.canDiscard(operation)) { "This publication may still be queued. Retry the saved correction before editing." }
            store.discardOperation(expenseId)
        }
        store.deleteDraft(expenseId)
    }
    suspend fun hide(expenseId: String, hidden: Boolean) = mutex.withLock {
        repository.setHistoryHidden(expenseId, hidden)
    }
    suspend fun publish(expenseId: String): ReceiptExportRecord = mutex.withLock {
        val original = requireNotNull(repository.get(expenseId))
        require(original.status == ReceiptExportStatus.EXPORTED && !original.historyHidden)
        val operation = store.operation(expenseId) ?: run {
            val draft = requireNotNull(store.draft(expenseId))
            require(draft.review().expenseValidationErrors().isEmpty()) { draft.review().expenseValidationErrors().joinToString(" ") }
            val observed = remote.inspect(original)
            check(observed.editable) { observed.message ?: "Refresh OneDrive status before re-exporting." }
            check(draft.accountId == observed.accountId && draft.driveId == observed.driveId) { "Reconnect the account used to open this edit." }
            check(draft.baseFingerprint == observed.payloadFingerprint && draft.expectedRevision == observed.revision) {
                "The remote expense changed after this edit opened. Cancel the draft and reopen Edit to review the current fields."
            }
            val corrected = draft.applyTo(original).copy(lastCorrectionOperationId = null)
            val id = newId()
            val itemType = if (observed.status == ReceiptRemoteStatus.PROCESSED) "processed" else "pending"
            // Original objects are never replaced. The consumer processes this immutable sidecar.
            val payload = (parseHandoffJson(requireNotNull(observed.payloadJson)) as org.json.JSONObject).apply {
                put("merchantName", corrected.merchantName); put("receiptDate", corrected.receiptDate)
                put("totalAmount", java.math.BigDecimal(corrected.totalAmount)); put("currency", corrected.currency)
                put("merchantLocation", corrected.merchantLocation ?: org.json.JSONObject.NULL)
                put("notes", corrected.notes); put("extractionStatus", "manual"); put("itemType", itemType)
            }.toString()
            val baseJson = requireNotNull(observed.payloadJson)
            val json = "{\"correctionProtocolVersion\":1,\"operationId\":${quoteJson(id)},\"expenseId\":${quoteJson(expenseId)}," +
                "\"expectedRevision\":${observed.revision},\"expectedPayloadJson\":${quoteJson(baseJson)}," +
                "\"expectedPayloadSha256\":${quoteJson(requireNotNull(observed.payloadFingerprint))},\"payload\":$payload}"
            ReceiptCorrectionOperation(id, expenseId, requireNotNull(observed.payloadFingerprint), itemType,
                json, requireNotNull(observed.accountId), now(), corrected.copy(lastCorrectionOperationId = id), observed, baseJson, observed.revision).also { store.saveOperation(it) }
        }
        val ack = try { remote.publish(operation) }
        catch (error: ReceiptCorrectionNotPublishedException) {
            store.discardOperation(expenseId)
            throw error
        }
        check(ack.operationId == operation.operationId) { "OneDrive acknowledged a different correction." }
        store.acknowledge(operation, ack.publishedAt)
        requireNotNull(repository.get(expenseId))
    }
}

internal fun quoteJson(value: String): String = buildString {
    append('"')
    value.forEach { c -> when (c) {
        '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
        else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
    } }
    append('"')
}
