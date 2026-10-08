package com.hugo.smartexpense.app.receiptexport

import com.hugo.smartexpense.app.ReceiptReviewState
import com.hugo.smartexpense.app.ReceiptSourceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.math.BigDecimal
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

const val HANDOFF_FOLDER = "Documents/2_Others/Expenses_finance/logs"
const val RECEIPT_IMAGE_FOLDER = "Documents/2_Others/Expenses_finance/receipt_images"

enum class ReceiptExportStatus {
    PREPARING_IMAGE,
    READY,
    CREATING_FOLDERS,
    UPLOADING_IMAGE,
    UPLOADING_JSON_TEMP,
    COMMITTING_JSON,
    EXPORTED,
    FAILED,
}

data class ReceiptExportRecord(
    val expenseId: String,
    val createdAt: String,
    val sourceDeviceId: String,
    val sourceDeviceName: String = "Android device",
    val receiptDate: String,
    val merchantName: String,
    val totalAmount: String,
    val currency: String,
    val extractionStatus: String,
    val merchantLocation: String?,
    val originalImageFileName: String?,
    val sourceImageUri: String?,
    val receiptImageRelativePath: String?,
    val jsonRelativePath: String,
    val temporaryJsonRelativePath: String,
    val normalizedImageLocalPath: String?,
    val status: ReceiptExportStatus,
    val failure: ReceiptExportFailure? = null,
    val retryAfterSeconds: Long? = null,
    val schemaVersion: Int = 2,
    val sourceType: ReceiptSourceType = ReceiptSourceType.IMAGE,
    val notes: String = "",
    val batchId: String? = null,
)

enum class ReceiptExportFailure {
    RECONNECT_REQUIRED,
    PERMISSION_DENIED,
    RATE_LIMITED,
    NETWORK_UNAVAILABLE,
    INSUFFICIENT_STORAGE,
    PATH_CONFLICT,
    INVALID_RECEIPT,
    SERVICE_FAILURE,
}

data class ReceiptExportPaths(
    val imageRelativePath: String,
    val jsonRelativePath: String,
    val temporaryJsonRelativePath: String,
) {
    companion object {
        private val fileTimestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        private val month = DateTimeFormatter.ofPattern("yyyy-MM")

        fun create(expenseId: String, timestamp: ZonedDateTime): ReceiptExportPaths {
            val shortId = expenseId.filter(Char::isLetterOrDigit).lowercase().take(8)
            require(shortId.length == 8) { "The expense ID cannot produce an 8-character file suffix." }
            val stamp = timestamp.format(fileTimestamp)
            val image = "$RECEIPT_IMAGE_FOLDER/${timestamp.format(month)}/${stamp}_receipt_$shortId.jpg"
            val json = "$HANDOFF_FOLDER/expense_${stamp}_$shortId.json"
            return ReceiptExportPaths(image, json, "$json.uploading")
        }
    }
}

data class PreparedReceiptImage(val localPath: String, val sizeBytes: Int)

fun interface ReceiptExportImagePreparer {
    fun prepare(sourceUri: String, stableFileName: String): PreparedReceiptImage
}

data class ReceiptExportBatch(
    val batchId: String,
    val createdAt: String,
    val expenseIds: List<String>,
    val jsonRelativePath: String,
    val temporaryJsonRelativePath: String,
    val committed: Boolean = false,
)

interface ReceiptExportRepository {
    suspend fun save(record: ReceiptExportRecord)
    suspend fun get(expenseId: String): ReceiptExportRecord?
    suspend fun getBatch(batchId: String): ReceiptExportBatch? = null
    /** Production implementations must atomically persist membership and all snapshots. */
    suspend fun createBatch(batch: ReceiptExportBatch, records: List<ReceiptExportRecord>) {
        error("This repository does not support durable batch exports.")
    }
    suspend fun markBatchCommitted(batchId: String) { error("Batch persistence is unavailable.") }
}

fun interface ReceiptExportPublisher {
    suspend fun publish(record: ReceiptExportRecord, imageBytes: ByteArray?)
    suspend fun publishBatch(batch: ReceiptExportBatch, records: List<Pair<ReceiptExportRecord, ByteArray?>>) {
        error("This publisher does not support combined batch exports.")
    }
}

fun interface ReceiptExportImageReader {
    fun read(localPath: String): ByteArray
}

class ReceiptExportException(
    val failure: ReceiptExportFailure,
    val retryAfterSeconds: Long? = null,
) : Exception(failure.name)

class ReceiptExportController(
    private val repository: ReceiptExportRepository,
    private val imagePreparer: ReceiptExportImagePreparer,
    private val imageReader: ReceiptExportImageReader,
    private val publisher: ReceiptExportPublisher,
    private val sourceDeviceId: () -> String,
    private val sourceDeviceName: () -> String = { "Android device" },
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val newExpenseId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun start(review: ReceiptReviewState): ReceiptExportRecord = start(review) {}

    suspend fun start(
        review: ReceiptReviewState,
        onRecordSaved: (ReceiptExportRecord) -> Unit,
    ): ReceiptExportRecord = startWithIdentity(review, newExpenseId(), onRecordSaved)

    /** A stable transaction identity closes the crash window between batch and export stores. */
    suspend fun startWithIdentity(
        review: ReceiptReviewState,
        expenseId: String,
        onRecordSaved: (ReceiptExportRecord) -> Unit = {},
    ): ReceiptExportRecord {
        repository.get(expenseId)?.let { saved ->
            onRecordSaved(saved)
            return retry(saved.expenseId)
        }
        val initial = createSnapshot(review, expenseId, now())
        repository.save(initial)
        onRecordSaved(initial)
        return resume(initial)
    }

    private fun createSnapshot(review: ReceiptReviewState, expenseId: String, timestamp: ZonedDateTime): ReceiptExportRecord {
        validate(review)
        val confirmedReview = review.confirmedForExport()
        val paths = ReceiptExportPaths.create(expenseId, timestamp)
        return ReceiptExportRecord(
            expenseId = expenseId,
            createdAt = timestamp.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            sourceDeviceId = sourceDeviceId(),
            sourceDeviceName = sourceDeviceName().trim().ifBlank { "Android device" },
            receiptDate = confirmedReview.receiptDate.trim(),
            merchantName = confirmedReview.merchantName.trim(),
            totalAmount = BigDecimal(confirmedReview.totalAmount.trim()).toPlainString(),
            currency = confirmedReview.currency.trim().uppercase(),
            extractionStatus = if (review.sourceType == ReceiptSourceType.TYPED) "manual" else confirmedReview.extractionStatus,
            merchantLocation = confirmedReview.merchantLocation.trim().ifBlank { null },
            originalImageFileName = null,
            sourceImageUri = review.sourceImageUri.takeIf { review.sourceType == ReceiptSourceType.IMAGE },
            receiptImageRelativePath = paths.imageRelativePath.takeIf { review.sourceType == ReceiptSourceType.IMAGE },
            jsonRelativePath = paths.jsonRelativePath,
            temporaryJsonRelativePath = paths.temporaryJsonRelativePath,
            normalizedImageLocalPath = null,
            status = if (review.sourceType == ReceiptSourceType.IMAGE) ReceiptExportStatus.PREPARING_IMAGE else ReceiptExportStatus.READY,
            schemaVersion = if (review.sourceType == ReceiptSourceType.IMAGE) 2 else 3,
            sourceType = review.sourceType,
            notes = review.notes,
        )
    }

    private val batchMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun startBatch(
        reviews: List<Pair<String, ReceiptReviewState>>,
        onRecordSaved: (ReceiptExportRecord) -> Unit = {},
    ): List<ReceiptExportRecord> = batchMutex.withLock {
        require(reviews.isNotEmpty()) { "There are no reviewed expenses to export." }
        val ids = reviews.map { it.first }
        require(ids.distinct().size == ids.size) { "Expense identities must be unique." }
        require(ids.map { it.filter(Char::isLetterOrDigit).lowercase().take(8) }.distinct().size == ids.size) {
            "Expense identities must produce unique canonical filename suffixes."
        }
        val existing = ids.map { repository.get(it) }
        val existingBatchIds = existing.mapNotNull { it?.batchId }.distinct()
        val batch = if (existingBatchIds.isNotEmpty()) {
            require(existingBatchIds.size == 1 && existing.all { it?.batchId == existingBatchIds.single() }) {
                "A pending batch cannot be combined with other exports. Retry its original membership."
            }
            requireNotNull(repository.getBatch(existingBatchIds.single())).also {
                require(it.expenseIds == ids) { "A saved batch's membership and order cannot be changed." }
            }
        } else {
            require(existing.all { it == null }) { "Existing individual exports cannot be included in a new batch." }
            val timestamp = now()
            val batchId = UUID.randomUUID().toString()
            val name = "expense_batch_${timestamp.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))}_${batchId.filter(Char::isLetterOrDigit).take(8)}.json"
            val path = "$HANDOFF_FOLDER/$name"
            val newBatch = ReceiptExportBatch(batchId, timestamp.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), ids, path, "$path.uploading")
            val records = reviews.map { (id, review) ->
                createSnapshot(review, id, timestamp).copy(batchId = batchId, jsonRelativePath = path, temporaryJsonRelativePath = "$path.uploading")
            }
            require(records.mapNotNull { it.receiptImageRelativePath }.distinct().size == records.count { it.receiptImageRelativePath != null }) {
                "Expense identities must produce unique image paths."
            }
            repository.createBatch(newBatch, records)
            newBatch
        }
        batch.expenseIds.forEach { onRecordSaved(requireNotNull(repository.get(it))) }
        resumeBatch(batch)
    }

    /** Restores local export state without network calls or publication. */
    suspend fun savedRecordForExpense(expenseId: String): ReceiptExportRecord? {
        val record = repository.get(expenseId) ?: return null
        val batchId = record.batchId ?: return record
        val batch = requireNotNull(repository.getBatch(batchId)) { "The saved export batch could not be found." }
        return if (batch.committed) record.copy(status = ReceiptExportStatus.EXPORTED, failure = null, retryAfterSeconds = null) else record
    }

    suspend fun batchRecordsForExpense(expenseId: String): List<ReceiptExportRecord> {
        val batchId = repository.get(expenseId)?.batchId ?: return emptyList()
        val batch = requireNotNull(repository.getBatch(batchId))
        return batch.expenseIds.map { requireNotNull(repository.get(it)) }
    }

    suspend fun retry(expenseId: String): ReceiptExportRecord {
        val record = requireNotNull(repository.get(expenseId)) { "The saved export could not be found." }
        val batchId = record.batchId ?: return resume(record)
        return batchMutex.withLock {
            resumeBatch(requireNotNull(repository.getBatch(batchId))).first { it.expenseId == expenseId }
        }
    }

    private suspend fun resumeBatch(batch: ReceiptExportBatch): List<ReceiptExportRecord> {
        var records = batch.expenseIds.map { requireNotNull(repository.get(it)) }
        if (batch.committed || records.all { it.status == ReceiptExportStatus.EXPORTED }) {
            return records.map { if (it.status == ReceiptExportStatus.EXPORTED) it else saveAndReturn(it.copy(status = ReceiptExportStatus.EXPORTED, failure = null, retryAfterSeconds = null)) }
        }
        try {
            val prepared = mutableListOf<Pair<ReceiptExportRecord, ByteArray?>>()
            for (saved in records) {
                coroutineContext.ensureActive()
                var current = saved.copy(failure = null, retryAfterSeconds = null)
                current.validateSourceContract()
                if (current.sourceType == ReceiptSourceType.IMAGE && current.normalizedImageLocalPath == null) {
                    val image = imagePreparer.prepare(requireNotNull(current.sourceImageUri), requireNotNull(current.receiptImageRelativePath).substringAfterLast('/'))
                    require(image.sizeBytes in 1 until 200 * 1024)
                    current = current.copy(normalizedImageLocalPath = image.localPath)
                }
                val bytes = current.normalizedImageLocalPath?.let { imageReader.read(it).also { bytes -> require(bytes.size in 1 until 200 * 1024) } }
                current = saveAndReturn(current.copy(status = ReceiptExportStatus.READY))
                prepared += current to bytes
            }
            records = prepared.map { it.first }
            coroutineContext.ensureActive()
            publisher.publishBatch(batch, prepared)
            // Persist one commit marker before member updates, allowing restart after any member save.
            repository.markBatchCommitted(batch.batchId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val failure = when (error) {
                is ReceiptExportException -> error.failure
                is IllegalArgumentException -> ReceiptExportFailure.INVALID_RECEIPT
                is java.io.IOException -> ReceiptExportFailure.INSUFFICIENT_STORAGE
                else -> ReceiptExportFailure.NETWORK_UNAVAILABLE
            }
            return batch.expenseIds.map { id ->
                val latest = requireNotNull(repository.get(id))
                if (latest.status == ReceiptExportStatus.EXPORTED) latest else saveAndReturn(latest.copy(status = ReceiptExportStatus.FAILED, failure = failure, retryAfterSeconds = (error as? ReceiptExportException)?.retryAfterSeconds))
            }
        }
        return records.map { saveAndReturn(it.copy(status = ReceiptExportStatus.EXPORTED)) }
    }

    private suspend fun resume(saved: ReceiptExportRecord): ReceiptExportRecord {
        if (saved.status == ReceiptExportStatus.EXPORTED) return saved
        var current = saved.copy(failure = null, retryAfterSeconds = null)
        return try {
            current.validateSourceContract()
            if (current.sourceType == ReceiptSourceType.IMAGE && current.normalizedImageLocalPath == null) {
                repository.save(current.copy(status = ReceiptExportStatus.PREPARING_IMAGE))
                val fileName = requireNotNull(current.receiptImageRelativePath).substringAfterLast('/')
                val prepared = imagePreparer.prepare(requireNotNull(current.sourceImageUri), fileName)
                require(prepared.sizeBytes in 1 until 200 * 1024) { "The normalized JPEG must be below 200 KB." }
                current = current.copy(
                    normalizedImageLocalPath = prepared.localPath,
                    status = ReceiptExportStatus.READY,
                )
                repository.save(current)
            }
            val imageBytes = if (current.sourceType == ReceiptSourceType.IMAGE) {
                imageReader.read(requireNotNull(current.normalizedImageLocalPath)).also {
                    require(it.size in 1 until 200 * 1024) { "The normalized JPEG must be below 200 KB." }
                }
            } else null
            current = current.copy(status = ReceiptExportStatus.READY)
            repository.save(current)
            publisher.publish(current, imageBytes)
            saveAndReturn(current.copy(status = ReceiptExportStatus.EXPORTED))
        } catch (error: CancellationException) {
            throw error
        } catch (error: ReceiptExportException) {
            saveAndReturn(current.copy(
                status = ReceiptExportStatus.FAILED,
                failure = error.failure,
                retryAfterSeconds = error.retryAfterSeconds,
            ))
        } catch (_: IllegalArgumentException) {
            saveAndReturn(current.copy(status = ReceiptExportStatus.FAILED, failure = ReceiptExportFailure.INVALID_RECEIPT))
        } catch (_: java.io.IOException) {
            saveAndReturn(current.copy(status = ReceiptExportStatus.FAILED, failure = ReceiptExportFailure.INSUFFICIENT_STORAGE))
        } catch (_: Exception) {
            saveAndReturn(current.copy(status = ReceiptExportStatus.FAILED, failure = ReceiptExportFailure.NETWORK_UNAVAILABLE))
        }
    }

    private suspend fun saveAndReturn(record: ReceiptExportRecord): ReceiptExportRecord {
        repository.save(record)
        return record
    }

    private fun validate(review: ReceiptReviewState) {
        if (review.sourceType == ReceiptSourceType.IMAGE) {
            require(review.sourceImageUri.isNotBlank()) { "A receipt image is required." }
        }
        require(review.expenseValidationErrors().isEmpty()) { review.expenseValidationErrors().joinToString(" ") }
        require(review.extractionStatus in setOf("confirmed", "manual", "low_confidence")) {
            "The receipt must be reviewed before export."
        }
    }
}

/** Rejects inconsistent provenance instead of interpreting a missing image as typed input. */
fun ReceiptExportRecord.validateSourceContract() {
    when (sourceType) {
        ReceiptSourceType.IMAGE -> {
            require(schemaVersion == 2 && !sourceImageUri.isNullOrBlank() && !receiptImageRelativePath.isNullOrBlank()) {
                "Version 2 exports require a receipt image."
            }
        }
        ReceiptSourceType.TYPED -> {
            require(schemaVersion == 3 && extractionStatus == "manual" && sourceImageUri == null &&
                receiptImageRelativePath == null && normalizedImageLocalPath == null && originalImageFileName == null) {
                "Version 3 exports require typed provenance and no receipt image."
            }
        }
    }
}

fun ReceiptExportRecord.toVersion2Json(): String {
    require(schemaVersion == 2) { "This export is not Version 2." }
    return toHandoffJson()
}

fun ReceiptExportRecord.toHandoffJson(): String {
    validateSourceContract()
    return buildString {
        append('{')
        field("schemaVersion", schemaVersion.toString(), quoted = false)
        field("expenseId", expenseId)
        field("createdAt", createdAt)
        field("sourceDeviceId", sourceDeviceId)
        field("sourceDeviceName", sourceDeviceName)
        field("receiptDate", receiptDate)
        field("merchantName", merchantName)
        field("totalAmount", totalAmount, quoted = false)
        field("currency", currency)
        field("extractionStatus", extractionStatus)
        merchantLocation?.let { field("merchantLocation", it) }
        if (notes.isNotEmpty()) field("notes", notes)
        if (sourceType == ReceiptSourceType.TYPED) {
            field("inputSource", "typed")
            field("hasReceiptImage", "false", quoted = false)
            field("receiptImageRelativePath", "null", quoted = false, trailingComma = false)
        } else {
            originalImageFileName?.let { field("originalImageFileName", it) }
            field("receiptImageRelativePath", requireNotNull(receiptImageRelativePath), trailingComma = false)
        }
        append('}')
    }
}

private fun StringBuilder.field(
    name: String,
    value: String,
    quoted: Boolean = true,
    trailingComma: Boolean = true,
) {
    append('"').append(name).append("\":")
    if (quoted) append('"').append(value.jsonEscape()).append('"') else append(value)
    if (trailingComma) append(',')
}

private fun String.jsonEscape(): String = buildString(length) {
    this@jsonEscape.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
        }
    }
}
