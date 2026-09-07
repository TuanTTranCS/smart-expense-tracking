package com.hugo.smartexpense.app.receiptexport

import com.hugo.smartexpense.app.ReceiptReviewState
import java.math.BigDecimal
import java.time.LocalDate
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
    val receiptDate: String,
    val merchantName: String,
    val totalAmount: String,
    val currency: String,
    val extractionStatus: String,
    val merchantLocation: String?,
    val originalImageFileName: String?,
    val sourceImageUri: String,
    val receiptImageRelativePath: String,
    val jsonRelativePath: String,
    val temporaryJsonRelativePath: String,
    val normalizedImageLocalPath: String?,
    val status: ReceiptExportStatus,
    val failure: ReceiptExportFailure? = null,
    val retryAfterSeconds: Long? = null,
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

interface ReceiptExportRepository {
    suspend fun save(record: ReceiptExportRecord)
    suspend fun get(expenseId: String): ReceiptExportRecord?
}

fun interface ReceiptExportPublisher {
    suspend fun publish(record: ReceiptExportRecord, imageBytes: ByteArray)
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
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val newExpenseId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun start(review: ReceiptReviewState): ReceiptExportRecord {
        validate(review)
        val expenseId = newExpenseId()
        val timestamp = now()
        val paths = ReceiptExportPaths.create(expenseId, timestamp)
        val initial = ReceiptExportRecord(
            expenseId = expenseId,
            createdAt = timestamp.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            sourceDeviceId = sourceDeviceId(),
            receiptDate = review.receiptDate.trim(),
            merchantName = review.merchantName.trim(),
            totalAmount = BigDecimal(review.totalAmount.trim()).toPlainString(),
            currency = review.currency.trim().uppercase(),
            extractionStatus = review.extractionStatus,
            merchantLocation = review.merchantLocation.trim().ifBlank { null },
            originalImageFileName = null,
            sourceImageUri = review.sourceImageUri,
            receiptImageRelativePath = paths.imageRelativePath,
            jsonRelativePath = paths.jsonRelativePath,
            temporaryJsonRelativePath = paths.temporaryJsonRelativePath,
            normalizedImageLocalPath = null,
            status = ReceiptExportStatus.PREPARING_IMAGE,
        )
        repository.save(initial)
        return resume(initial)
    }

    suspend fun retry(expenseId: String): ReceiptExportRecord =
        resume(requireNotNull(repository.get(expenseId)) { "The saved export could not be found." })

    private suspend fun resume(saved: ReceiptExportRecord): ReceiptExportRecord {
        if (saved.status == ReceiptExportStatus.EXPORTED) return saved
        var current = saved.copy(failure = null, retryAfterSeconds = null)
        return try {
            if (current.normalizedImageLocalPath == null) {
                repository.save(current.copy(status = ReceiptExportStatus.PREPARING_IMAGE))
                val fileName = current.receiptImageRelativePath.substringAfterLast('/')
                val prepared = imagePreparer.prepare(current.sourceImageUri, fileName)
                require(prepared.sizeBytes in 1 until 200 * 1024) { "The normalized JPEG must be below 200 KB." }
                current = current.copy(
                    normalizedImageLocalPath = prepared.localPath,
                    status = ReceiptExportStatus.READY,
                )
                repository.save(current)
            }
            val imageBytes = imageReader.read(requireNotNull(current.normalizedImageLocalPath))
            require(imageBytes.size in 1 until 200 * 1024) { "The normalized JPEG must be below 200 KB." }
            publisher.publish(current, imageBytes)
            saveAndReturn(current.copy(status = ReceiptExportStatus.EXPORTED))
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
        require(review.sourceImageUri.isNotBlank()) { "A receipt image is required." }
        LocalDate.parse(review.receiptDate.trim())
        require(review.merchantName.isNotBlank()) { "Merchant is required." }
        require(BigDecimal(review.totalAmount.trim()) >= BigDecimal.ZERO) { "Amount must not be negative." }
        require(Regex("[A-Z]{3}").matches(review.currency.trim().uppercase())) { "Currency must have three letters." }
        require(review.extractionStatus in setOf("confirmed", "manual", "low_confidence")) {
            "The receipt must be reviewed before export."
        }
    }
}

fun ReceiptExportRecord.toVersion2Json(): String = buildString {
    append('{')
    field("schemaVersion", "2", quoted = false)
    field("expenseId", expenseId)
    field("createdAt", createdAt)
    field("sourceDeviceId", sourceDeviceId)
    field("receiptDate", receiptDate)
    field("merchantName", merchantName)
    field("totalAmount", totalAmount, quoted = false)
    field("currency", currency)
    field("extractionStatus", extractionStatus)
    merchantLocation?.let { field("merchantLocation", it) }
    originalImageFileName?.let { field("originalImageFileName", it) }
    field("receiptImageRelativePath", receiptImageRelativePath, trailingComma = false)
    append('}')
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
