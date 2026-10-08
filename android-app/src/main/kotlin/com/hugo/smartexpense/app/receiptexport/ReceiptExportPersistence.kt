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
)

@Entity(tableName = "receipt_export_batches")
data class ReceiptExportBatchEntity(
    @PrimaryKey @ColumnInfo(name = "batch_id") val batchId: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "expense_ids") val expenseIds: String,
    @ColumnInfo(name = "json_relative_path") val jsonRelativePath: String,
    @ColumnInfo(name = "temporary_json_relative_path") val temporaryJsonRelativePath: String,
    val committed: Boolean,
)

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

    @Query("UPDATE receipt_export_batches SET committed = 1 WHERE batch_id = :batchId")
    suspend fun markBatchCommitted(batchId: String)
}

@Database(entities = [ReceiptExportEntity::class, ReceiptExportBatchEntity::class], version = 4, exportSchema = true)
abstract class ReceiptExportDatabase : RoomDatabase() {
    abstract fun receiptExportDao(): ReceiptExportDao

    companion object {
        @Volatile private var instance: ReceiptExportDatabase? = null

        fun getInstance(context: Context): ReceiptExportDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ReceiptExportDatabase::class.java,
                "receipt-exports.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
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
    override suspend fun save(record: ReceiptExportRecord) = dao.upsert(record.toEntity())
    override suspend fun get(expenseId: String): ReceiptExportRecord? = dao.get(expenseId)?.toDomain()
    override suspend fun getBatch(batchId: String): ReceiptExportBatch? = dao.getBatch(batchId)?.let {
        ReceiptExportBatch(it.batchId, it.createdAt, it.expenseIds.split("\n"), it.jsonRelativePath, it.temporaryJsonRelativePath, it.committed)
    }
    override suspend fun createBatch(batch: ReceiptExportBatch, records: List<ReceiptExportRecord>) {
        require(batch.expenseIds == records.map { it.expenseId } && records.all { it.batchId == batch.batchId })
        require(batch.expenseIds.all { it.isNotBlank() && !it.contains('\n') })
        dao.createBatch(ReceiptExportBatchEntity(batch.batchId, batch.createdAt, batch.expenseIds.joinToString("\n"), batch.jsonRelativePath, batch.temporaryJsonRelativePath, batch.committed), records.map { it.toEntity() })
    }
    override suspend fun markBatchCommitted(batchId: String) = dao.markBatchCommitted(batchId)
}

private fun ReceiptExportRecord.toEntity() = ReceiptExportEntity(
    expenseId, createdAt, sourceDeviceId, sourceDeviceName, receiptDate, merchantName, totalAmount, currency,
    extractionStatus, merchantLocation, originalImageFileName, sourceImageUri,
    receiptImageRelativePath, jsonRelativePath, temporaryJsonRelativePath,
    normalizedImageLocalPath, status.name, failure?.name, retryAfterSeconds,
    schemaVersion, sourceType.name, notes, batchId,
)

private fun ReceiptExportEntity.toDomain() = ReceiptExportRecord(
    expenseId, createdAt, sourceDeviceId, sourceDeviceName, receiptDate, merchantName, totalAmount, currency,
    extractionStatus, merchantLocation, originalImageFileName, sourceImageUri,
    receiptImageRelativePath, jsonRelativePath, temporaryJsonRelativePath,
    normalizedImageLocalPath, ReceiptExportStatus.valueOf(status),
    failure?.let(ReceiptExportFailure::valueOf), retryAfterSeconds,
    schemaVersion, ReceiptSourceType.valueOf(sourceType), notes, batchId,
)
