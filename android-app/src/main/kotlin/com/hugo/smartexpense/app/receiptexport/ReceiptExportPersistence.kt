package com.hugo.smartexpense.app.receiptexport

import android.content.Context
import com.hugo.smartexpense.app.ReceiptSourceType
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.Transaction
import androidx.room.Insert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

@Entity(tableName = "receipt_exports")
data class ReceiptExportEntity(
    @PrimaryKey @ColumnInfo(name = "expense_id") val expenseId: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "source_device_id") val sourceDeviceId: String,
    @ColumnInfo(name = "source_device_name") val sourceDeviceName: String,
    @ColumnInfo(name = "receipt_date") val receiptDate: String,
    @ColumnInfo(name = "merchant_name") val merchantName: String,
    @ColumnInfo(name = "total_amount") val totalAmount: String,
    val currency: String,
    @ColumnInfo(name = "extraction_status") val extractionStatus: String,
    @ColumnInfo(name = "merchant_location") val merchantLocation: String?,
    @ColumnInfo(name = "original_image_file_name") val originalImageFileName: String?,
    @ColumnInfo(name = "source_image_uri") val sourceImageUri: String?,
    @ColumnInfo(name = "receipt_image_relative_path") val receiptImageRelativePath: String?,
    @ColumnInfo(name = "json_relative_path") val jsonRelativePath: String,
    @ColumnInfo(name = "temporary_json_relative_path") val temporaryJsonRelativePath: String,
    @ColumnInfo(name = "normalized_image_local_path") val normalizedImageLocalPath: String?,
    val status: String,
    val failure: String?,
    @ColumnInfo(name = "retry_after_seconds") val retryAfterSeconds: Long?,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,
    @ColumnInfo(name = "source_type") val sourceType: String,
    val notes: String,
    @ColumnInfo(name = "batch_id") val batchId: String? = null,
    @ColumnInfo(name = "completed_at") val completedAt: String? = null,
    @ColumnInfo(name = "history_hidden", defaultValue = "0") val historyHidden: Boolean = false,
    @ColumnInfo(name = "export_account_id") val exportAccountId: String? = null,
    @ColumnInfo(name = "last_correction_operation_id") val lastCorrectionOperationId: String? = null,
    @ColumnInfo(name = "last_remote_status") val lastRemoteStatus: String? = null,
    @ColumnInfo(name = "last_remote_checked_at") val lastRemoteCheckedAt: String? = null,
    @ColumnInfo(name = "last_remote_message") val lastRemoteMessage: String? = null,
)

@Entity(tableName = "receipt_export_batches")
data class ReceiptExportBatchEntity(
    @PrimaryKey @ColumnInfo(name = "batch_id") val batchId: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "expense_ids") val expenseIds: String,
    @ColumnInfo(name = "json_relative_path") val jsonRelativePath: String,
    @ColumnInfo(name = "temporary_json_relative_path") val temporaryJsonRelativePath: String,
    val committed: Boolean,
    @ColumnInfo(name = "completed_at") val completedAt: String? = null,
)

@Entity(tableName = "receipt_history_drafts")
data class ReceiptHistoryDraftEntity(@PrimaryKey val expenseId: String, val dataJson: String)
@Entity(tableName = "receipt_correction_operations")
data class ReceiptCorrectionOperationEntity(@PrimaryKey val expenseId: String, val operationId: String, val dataJson: String)

@Dao
interface ReceiptExportDao {
    @Upsert
    suspend fun upsert(record: ReceiptExportEntity)

    @Query("SELECT * FROM receipt_exports WHERE expense_id = :expenseId")
    suspend fun get(expenseId: String): ReceiptExportEntity?

    @Query("SELECT * FROM receipt_export_batches WHERE batch_id = :batchId")
    suspend fun getBatch(batchId: String): ReceiptExportBatchEntity?

    @Insert
    suspend fun insertBatch(batch: ReceiptExportBatchEntity)

    @Insert
    suspend fun insertRecords(records: List<ReceiptExportEntity>)

    @Transaction
    suspend fun createBatch(batch: ReceiptExportBatchEntity, records: List<ReceiptExportEntity>) {
        insertBatch(batch)
        insertRecords(records)
    }

    @Query("SELECT * FROM receipt_exports WHERE status = 'EXPORTED' AND history_hidden = 0 ORDER BY julianday(COALESCE(completed_at, created_at)) DESC, expense_id ASC")
    fun observeCompleted(): Flow<List<ReceiptExportEntity>>

    @Query("UPDATE receipt_exports SET history_hidden = :hidden WHERE expense_id = :expenseId AND status = 'EXPORTED'")
    suspend fun setHistoryHidden(expenseId: String, hidden: Boolean)

    @Query("UPDATE receipt_exports SET status = 'EXPORTED', failure = NULL, retry_after_seconds = NULL, completed_at = COALESCE(completed_at, (SELECT completed_at FROM receipt_export_batches WHERE batch_id = receipt_exports.batch_id)) WHERE batch_id IN (SELECT batch_id FROM receipt_export_batches WHERE committed = 1) AND status != 'EXPORTED'")
    suspend fun reconcileCommittedBatches()

    @Query("SELECT * FROM receipt_history_drafts WHERE expenseId = :expenseId")
    suspend fun draft(expenseId: String): ReceiptHistoryDraftEntity?
    @Upsert suspend fun saveDraft(draft: ReceiptHistoryDraftEntity)
    @Query("DELETE FROM receipt_history_drafts WHERE expenseId = :expenseId")
    suspend fun deleteDraft(expenseId: String)
    @Query("SELECT * FROM receipt_correction_operations WHERE expenseId = :expenseId")
    suspend fun operation(expenseId: String): ReceiptCorrectionOperationEntity?
    @Insert suspend fun saveOperation(operation: ReceiptCorrectionOperationEntity)
    @Query("DELETE FROM receipt_correction_operations WHERE expenseId = :expenseId")
    suspend fun deleteOperation(expenseId: String)
    @Transaction suspend fun acknowledge(record: ReceiptExportEntity, operationId: String) {
        check(operation(record.expenseId)?.operationId == operationId)
        upsert(record)
        deleteOperation(record.expenseId)
        deleteDraft(record.expenseId)
    }

    @Query("UPDATE receipt_export_batches SET committed = 1, completed_at = :completedAt WHERE batch_id = :batchId")
    suspend fun markBatchCompleted(batchId: String, completedAt: String)
    @Query("UPDATE receipt_export_batches SET committed = 1 WHERE batch_id = :batchId")
    suspend fun markBatchCommitted(batchId: String)
}

@Database(entities = [ReceiptExportEntity::class, ReceiptExportBatchEntity::class, ReceiptHistoryDraftEntity::class, ReceiptCorrectionOperationEntity::class], version = 5, exportSchema = true)
abstract class ReceiptExportDatabase : RoomDatabase() {
    abstract fun receiptExportDao(): ReceiptExportDao

    companion object {
        @Volatile private var instance: ReceiptExportDatabase? = null

        fun getInstance(context: Context): ReceiptExportDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ReceiptExportDatabase::class.java,
                "receipt-exports.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { instance = it }
        }

        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE receipt_exports ADD COLUMN completed_at TEXT")
                db.execSQL("ALTER TABLE receipt_export_batches ADD COLUMN completed_at TEXT")
                db.execSQL("ALTER TABLE receipt_exports ADD COLUMN history_hidden INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE receipt_exports ADD COLUMN export_account_id TEXT")
                db.execSQL("ALTER TABLE receipt_exports ADD COLUMN last_correction_operation_id TEXT")
                db.execSQL("ALTER TABLE receipt_exports ADD COLUMN last_remote_status TEXT")
                db.execSQL("ALTER TABLE receipt_exports ADD COLUMN last_remote_checked_at TEXT")
                db.execSQL("ALTER TABLE receipt_exports ADD COLUMN last_remote_message TEXT")
                db.execSQL("CREATE TABLE receipt_history_drafts (expenseId TEXT NOT NULL PRIMARY KEY, dataJson TEXT NOT NULL)")
                db.execSQL("CREATE TABLE receipt_correction_operations (expenseId TEXT NOT NULL PRIMARY KEY, operationId TEXT NOT NULL, dataJson TEXT NOT NULL)")
            }
        }

        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE receipt_exports ADD COLUMN batch_id TEXT")
                db.execSQL("CREATE TABLE receipt_export_batches (batch_id TEXT NOT NULL PRIMARY KEY, created_at TEXT NOT NULL, expense_ids TEXT NOT NULL, json_relative_path TEXT NOT NULL, temporary_json_relative_path TEXT NOT NULL, committed INTEGER NOT NULL)")
            }
        }

        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE receipt_exports ADD COLUMN source_device_name TEXT NOT NULL DEFAULT 'Android device'")
            }
        }

        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Rebuild only this table to relax image nullability, retaining every retry snapshot.
                db.execSQL("""CREATE TABLE receipt_exports_v3 (
                    expense_id TEXT NOT NULL PRIMARY KEY, created_at TEXT NOT NULL,
                    source_device_id TEXT NOT NULL, source_device_name TEXT NOT NULL,
                    receipt_date TEXT NOT NULL, merchant_name TEXT NOT NULL, total_amount TEXT NOT NULL,
                    currency TEXT NOT NULL, extraction_status TEXT NOT NULL, merchant_location TEXT,
                    original_image_file_name TEXT, source_image_uri TEXT, receipt_image_relative_path TEXT,
                    json_relative_path TEXT NOT NULL, temporary_json_relative_path TEXT NOT NULL,
                    normalized_image_local_path TEXT, status TEXT NOT NULL, failure TEXT,
                    retry_after_seconds INTEGER, schema_version INTEGER NOT NULL, source_type TEXT NOT NULL,
                    notes TEXT NOT NULL
                )""")
                db.execSQL("""INSERT INTO receipt_exports_v3 SELECT expense_id, created_at,
                    source_device_id, source_device_name, receipt_date, merchant_name, total_amount,
                    currency, extraction_status, merchant_location, original_image_file_name,
                    source_image_uri, receipt_image_relative_path, json_relative_path,
                    temporary_json_relative_path, normalized_image_local_path, status, failure,
                    retry_after_seconds, 2, 'IMAGE', '' FROM receipt_exports""")
                db.execSQL("DROP TABLE receipt_exports")
                db.execSQL("ALTER TABLE receipt_exports_v3 RENAME TO receipt_exports")
            }
        }
    }
}

class RoomReceiptExportRepository(
    private val dao: ReceiptExportDao,
) : ReceiptExportRepository {
    override fun observeCompleted(): Flow<List<ReceiptExportRecord>> = dao.observeCompleted()
        .onStart { dao.reconcileCommittedBatches() }.map { rows -> rows.map { it.toDomain() } }
    override suspend fun reconcileCommittedBatches() = dao.reconcileCommittedBatches()
    override suspend fun setHistoryHidden(expenseId: String, hidden: Boolean) = dao.setHistoryHidden(expenseId, hidden)
    override suspend fun save(record: ReceiptExportRecord) = dao.upsert(record.toEntity())
    override suspend fun get(expenseId: String): ReceiptExportRecord? = dao.get(expenseId)?.toDomain()
    override suspend fun getBatch(batchId: String): ReceiptExportBatch? = dao.getBatch(batchId)?.let {
        ReceiptExportBatch(it.batchId, it.createdAt, it.expenseIds.split("\n"), it.jsonRelativePath, it.temporaryJsonRelativePath, it.committed, it.completedAt)
    }
    override suspend fun createBatch(batch: ReceiptExportBatch, records: List<ReceiptExportRecord>) {
        require(batch.expenseIds == records.map { it.expenseId } && records.all { it.batchId == batch.batchId })
        require(batch.expenseIds.all { it.isNotBlank() && !it.contains('\n') })
        dao.createBatch(ReceiptExportBatchEntity(batch.batchId, batch.createdAt, batch.expenseIds.joinToString("\n"), batch.jsonRelativePath, batch.temporaryJsonRelativePath, batch.committed, batch.completedAt), records.map { it.toEntity() })
    }
    override suspend fun markBatchCompleted(batchId: String, completedAt: String) = dao.markBatchCompleted(batchId, completedAt)
    override suspend fun markBatchCommitted(batchId: String) = dao.markBatchCommitted(batchId)
}

private fun ReceiptExportRecord.toEntity() = ReceiptExportEntity(
    expenseId, createdAt, sourceDeviceId, sourceDeviceName, receiptDate, merchantName, totalAmount, currency,
    extractionStatus, merchantLocation, originalImageFileName, sourceImageUri,
    receiptImageRelativePath, jsonRelativePath, temporaryJsonRelativePath,
    normalizedImageLocalPath, status.name, failure?.name, retryAfterSeconds,
    schemaVersion, sourceType.name, notes, batchId, completedAt, historyHidden, exportAccountId, lastCorrectionOperationId, lastRemoteStatus?.name, lastRemoteCheckedAt, lastRemoteMessage,
)

private fun ReceiptExportEntity.toDomain() = ReceiptExportRecord(
    expenseId, createdAt, sourceDeviceId, sourceDeviceName, receiptDate, merchantName, totalAmount, currency,
    extractionStatus, merchantLocation, originalImageFileName, sourceImageUri,
    receiptImageRelativePath, jsonRelativePath, temporaryJsonRelativePath,
    normalizedImageLocalPath, ReceiptExportStatus.valueOf(status),
    failure?.let(ReceiptExportFailure::valueOf), retryAfterSeconds,
    schemaVersion, ReceiptSourceType.valueOf(sourceType), notes, batchId, completedAt, historyHidden, exportAccountId, lastCorrectionOperationId, lastRemoteStatus?.let(ReceiptRemoteStatus::valueOf), lastRemoteCheckedAt, lastRemoteMessage,
)

class RoomReceiptHistoryStore(private val dao: ReceiptExportDao) : ReceiptHistoryStore {
    override suspend fun draft(expenseId: String): ReceiptCorrectionDraft? = dao.draft(expenseId)?.let {
        val json = org.json.JSONObject(it.dataJson)
        ReceiptCorrectionDraft(expenseId, json.getString("merchantName"), json.getString("receiptDate"),
            json.getString("totalAmount"), json.getString("currency"), json.getString("merchantLocation"),
            json.getString("notes"), json.optString("accountId").ifBlank { null }, json.optString("driveId").ifBlank { null },
            json.optString("baseFingerprint").ifBlank { null }, json.optInt("expectedRevision", 0))
    }
    override suspend fun saveDraft(draft: ReceiptCorrectionDraft) {
        val json = org.json.JSONObject().apply {
            put("merchantName", draft.merchantName); put("receiptDate", draft.receiptDate)
            put("totalAmount", draft.totalAmount); put("currency", draft.currency)
            put("merchantLocation", draft.merchantLocation); put("notes", draft.notes)
            put("accountId", draft.accountId.orEmpty()); put("driveId", draft.driveId.orEmpty())
            put("baseFingerprint", draft.baseFingerprint.orEmpty()); put("expectedRevision", draft.expectedRevision)
        }
        dao.saveDraft(ReceiptHistoryDraftEntity(draft.expenseId, json.toString()))
    }
    override suspend fun deleteDraft(expenseId: String) = dao.deleteDraft(expenseId)
    override suspend fun discardOperation(expenseId: String) = dao.deleteOperation(expenseId)
    override suspend fun saveOperation(operation: ReceiptCorrectionOperation) {
        val observation = operation.observation
        val json = org.json.JSONObject().apply {
            put("envelope", operation.payloadJson); put("createdAt", operation.createdAt)
            put("accountId", operation.accountId); put("driveId", observation.driveId.orEmpty())
            put("itemId", observation.itemId.orEmpty()); put("eTag", observation.eTag.orEmpty())
            put("checkedAt", observation.checkedAt); put("status", observation.status.name)
        }
        dao.saveOperation(ReceiptCorrectionOperationEntity(operation.expenseId, operation.operationId, json.toString()))
    }
    override suspend fun operation(expenseId: String): ReceiptCorrectionOperation? = dao.operation(expenseId)?.let { entity ->
        val json = org.json.JSONObject(entity.dataJson)
        val envelope = parseHandoffJson(json.getString("envelope")) as org.json.JSONObject
        val payload = envelope.getJSONObject("payload")
        val record = requireNotNull(dao.get(expenseId)).toDomain()
        val corrected = ReceiptCorrectionDraft(expenseId, payload.getString("merchantName"), payload.getString("receiptDate"),
            payload.get("totalAmount").toString(), payload.getString("currency"),
            if (payload.isNull("merchantLocation")) "" else payload.optString("merchantLocation"), payload.optString("notes")).applyTo(record)
            .copy(lastCorrectionOperationId = entity.operationId)
        val base = envelope.getString("expectedPayloadJson")
        val fingerprint = envelope.getString("expectedPayloadSha256")
        val revision = envelope.getInt("expectedRevision")
        val observation = ReceiptRemoteObservation(ReceiptRemoteStatus.valueOf(json.getString("status")), json.getString("checkedAt"),
            json.getString("accountId"), json.optString("driveId").ifBlank { null }, json.optString("itemId").ifBlank { null },
            json.optString("eTag").ifBlank { null }, fingerprint, payloadJson = base, revision = revision, correctionsEnabled = true)
        ReceiptCorrectionOperation(entity.operationId, expenseId, fingerprint, payload.getString("itemType"),
            json.getString("envelope"), json.getString("accountId"), json.getString("createdAt"), corrected, observation, base, revision)
    }
    override suspend fun acknowledge(operation: ReceiptCorrectionOperation, publishedAt: String) {
        dao.acknowledge(operation.corrected.toEntity(), operation.operationId)
    }
}
