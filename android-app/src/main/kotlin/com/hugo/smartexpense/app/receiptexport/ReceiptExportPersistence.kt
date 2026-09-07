package com.hugo.smartexpense.app.receiptexport

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert

@Entity(tableName = "receipt_exports")
data class ReceiptExportEntity(
    @PrimaryKey @ColumnInfo(name = "expense_id") val expenseId: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "source_device_id") val sourceDeviceId: String,
    @ColumnInfo(name = "receipt_date") val receiptDate: String,
    @ColumnInfo(name = "merchant_name") val merchantName: String,
    @ColumnInfo(name = "total_amount") val totalAmount: String,
    val currency: String,
    @ColumnInfo(name = "extraction_status") val extractionStatus: String,
    @ColumnInfo(name = "merchant_location") val merchantLocation: String?,
    @ColumnInfo(name = "original_image_file_name") val originalImageFileName: String?,
    @ColumnInfo(name = "source_image_uri") val sourceImageUri: String,
    @ColumnInfo(name = "receipt_image_relative_path") val receiptImageRelativePath: String,
    @ColumnInfo(name = "json_relative_path") val jsonRelativePath: String,
    @ColumnInfo(name = "temporary_json_relative_path") val temporaryJsonRelativePath: String,
    @ColumnInfo(name = "normalized_image_local_path") val normalizedImageLocalPath: String?,
    val status: String,
    val failure: String?,
    @ColumnInfo(name = "retry_after_seconds") val retryAfterSeconds: Long?,
)

@Dao
interface ReceiptExportDao {
    @Upsert
    suspend fun upsert(record: ReceiptExportEntity)

    @Query("SELECT * FROM receipt_exports WHERE expense_id = :expenseId")
    suspend fun get(expenseId: String): ReceiptExportEntity?
}

@Database(entities = [ReceiptExportEntity::class], version = 1, exportSchema = false)
abstract class ReceiptExportDatabase : RoomDatabase() {
    abstract fun receiptExportDao(): ReceiptExportDao

    companion object {
        @Volatile private var instance: ReceiptExportDatabase? = null

        fun getInstance(context: Context): ReceiptExportDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ReceiptExportDatabase::class.java,
                "receipt-exports.db",
            ).build().also { instance = it }
        }
    }
}

class RoomReceiptExportRepository(
    private val dao: ReceiptExportDao,
) : ReceiptExportRepository {
    override suspend fun save(record: ReceiptExportRecord) = dao.upsert(record.toEntity())
    override suspend fun get(expenseId: String): ReceiptExportRecord? = dao.get(expenseId)?.toDomain()
}

private fun ReceiptExportRecord.toEntity() = ReceiptExportEntity(
    expenseId, createdAt, sourceDeviceId, receiptDate, merchantName, totalAmount, currency,
    extractionStatus, merchantLocation, originalImageFileName, sourceImageUri,
    receiptImageRelativePath, jsonRelativePath, temporaryJsonRelativePath,
    normalizedImageLocalPath, status.name, failure?.name, retryAfterSeconds,
)

private fun ReceiptExportEntity.toDomain() = ReceiptExportRecord(
    expenseId, createdAt, sourceDeviceId, receiptDate, merchantName, totalAmount, currency,
    extractionStatus, merchantLocation, originalImageFileName, sourceImageUri,
    receiptImageRelativePath, jsonRelativePath, temporaryJsonRelativePath,
    normalizedImageLocalPath, ReceiptExportStatus.valueOf(status),
    failure?.let(ReceiptExportFailure::valueOf), retryAfterSeconds,
)
