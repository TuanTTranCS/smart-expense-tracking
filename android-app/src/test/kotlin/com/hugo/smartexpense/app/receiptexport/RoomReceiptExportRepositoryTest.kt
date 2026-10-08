package com.hugo.smartexpense.app.receiptexport

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class RoomReceiptExportRepositoryTest {
    private lateinit var database: ReceiptExportDatabase
    private lateinit var repository: RoomReceiptExportRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ReceiptExportDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RoomReceiptExportRepository(database.receiptExportDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun persistsIdentityPathsImageAndFailureForRestartSafeRetry() = runTest {
        val failed = sampleRecord().copy(
            status = ReceiptExportStatus.FAILED,
            failure = ReceiptExportFailure.RATE_LIMITED,
            retryAfterSeconds = 45,
        )
        repository.save(failed)

        val recreated = RoomReceiptExportRepository(database.receiptExportDao())

        assertEquals(failed, recreated.get(failed.expenseId))
    }
    @Test
    fun typedPayloadRoundTripsWithoutInventingImageFields() = runTest {
        val typed = sampleTypedRecord()
        repository.save(typed)
        assertEquals(typed, RoomReceiptExportRepository(database.receiptExportDao()).get(typed.expenseId))
    }

    @Test
    fun realV2MigrationPreservesPendingAndCompletedExportsAndSupportsTypedRows() = runTest {
        verifyMigration(2)
    }

    @Test
    fun realV1MigrationPreservesPendingAndCompletedExportsAndSupportsTypedRows() = runTest {
        verifyMigration(1)
    }

    @Test
    fun durableBatchMembershipAndCommitMarkerSurviveRepositoryRecreation() = runTest {
        val path = "$HANDOFF_FOLDER/expense_batch_20261007_140507_abcd1234.json"
        val batch = ReceiptExportBatch("batch-1", sampleRecord().createdAt, listOf("image-member", "typed-member"), path, "$path.uploading")
        val records = listOf(sampleRecord().copy(expenseId = "image-member"), sampleTypedRecord().copy(expenseId = "typed-member")).map {
            it.copy(batchId = batch.batchId, jsonRelativePath = path, temporaryJsonRelativePath = "$path.uploading")
        }
        repository.createBatch(batch, records)
        val recreated = RoomReceiptExportRepository(database.receiptExportDao())
        assertEquals(batch, recreated.getBatch(batch.batchId))
        assertEquals(records, batch.expenseIds.map { recreated.get(it) })
        recreated.markBatchCommitted(batch.batchId)
        assertEquals(batch.copy(committed = true), repository.getBatch(batch.batchId))
    }

    @Test
    fun batchSnapshotInsertionRollsBackEntireManifestOnExistingMemberConflict() = runTest {
        val existing = sampleRecord()
        repository.save(existing)
        val batch = ReceiptExportBatch("conflicting-batch", existing.createdAt, listOf("new-member", existing.expenseId), existing.jsonRelativePath, existing.temporaryJsonRelativePath)
        val members = listOf(existing.copy(expenseId = "new-member", batchId = batch.batchId), existing.copy(batchId = batch.batchId))
        kotlin.test.assertFailsWith<android.database.sqlite.SQLiteConstraintException> { repository.createBatch(batch, members) }
        assertNull(repository.getBatch(batch.batchId))
        assertNull(repository.get("new-member"))
        assertEquals(existing, repository.get(existing.expenseId))
    }

    @Test
    fun realV3MigrationPreservesTypedPendingSnapshots() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "receipt-export-v3-migration-test.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val typed = sampleTypedRecord().copy(status = ReceiptExportStatus.FAILED, failure = ReceiptExportFailure.NETWORK_UNAVAILABLE)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("""CREATE TABLE receipt_exports (expense_id TEXT NOT NULL PRIMARY KEY,
                created_at TEXT NOT NULL, source_device_id TEXT NOT NULL, source_device_name TEXT NOT NULL,
                receipt_date TEXT NOT NULL, merchant_name TEXT NOT NULL, total_amount TEXT NOT NULL,
                currency TEXT NOT NULL, extraction_status TEXT NOT NULL, merchant_location TEXT,
                original_image_file_name TEXT, source_image_uri TEXT, receipt_image_relative_path TEXT,
                json_relative_path TEXT NOT NULL, temporary_json_relative_path TEXT NOT NULL,
                normalized_image_local_path TEXT, status TEXT NOT NULL, failure TEXT, retry_after_seconds INTEGER,
                schema_version INTEGER NOT NULL, source_type TEXT NOT NULL, notes TEXT NOT NULL)""")
            val values = listOf<Any?>(typed.expenseId, typed.createdAt, typed.sourceDeviceId, typed.sourceDeviceName,
                typed.receiptDate, typed.merchantName, typed.totalAmount, typed.currency, typed.extractionStatus,
                typed.merchantLocation, typed.originalImageFileName, typed.sourceImageUri, typed.receiptImageRelativePath,
                typed.jsonRelativePath, typed.temporaryJsonRelativePath, typed.normalizedImageLocalPath,
                typed.status.name, typed.failure?.name, typed.retryAfterSeconds, typed.schemaVersion, typed.sourceType.name, typed.notes)
            db.execSQL("INSERT INTO receipt_exports VALUES (${values.joinToString { "?" }})", values.toTypedArray())
            db.version = 3
        }
        val migratedDatabase = Room.databaseBuilder(context, ReceiptExportDatabase::class.java, name)
            .addMigrations(ReceiptExportDatabase.MIGRATION_3_4).allowMainThreadQueries().build()
        try {
            assertEquals(typed, RoomReceiptExportRepository(migratedDatabase.receiptExportDao()).get(typed.expenseId))
        } finally {
            migratedDatabase.close()
            context.deleteDatabase(name)
        }
    }

    private suspend fun verifyMigration(version: Int) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "receipt-export-v$version-migration-test.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val pending = sampleRecord().copy(status = ReceiptExportStatus.FAILED,
            failure = ReceiptExportFailure.RATE_LIMITED, retryAfterSeconds = 30)
        val completed = sampleRecord().copy(expenseId = "completed-export", status = ReceiptExportStatus.EXPORTED)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("""CREATE TABLE receipt_exports (expense_id TEXT NOT NULL PRIMARY KEY,
                created_at TEXT NOT NULL, source_device_id TEXT NOT NULL,
                ${if (version == 2) "source_device_name TEXT NOT NULL," else ""}
                receipt_date TEXT NOT NULL, merchant_name TEXT NOT NULL, total_amount TEXT NOT NULL,
                currency TEXT NOT NULL, extraction_status TEXT NOT NULL, merchant_location TEXT,
                original_image_file_name TEXT, source_image_uri TEXT NOT NULL,
                receipt_image_relative_path TEXT NOT NULL, json_relative_path TEXT NOT NULL,
                temporary_json_relative_path TEXT NOT NULL, normalized_image_local_path TEXT,
                status TEXT NOT NULL, failure TEXT, retry_after_seconds INTEGER)""")
            listOf(pending, completed).forEach { record ->
                val values = mutableListOf<Any?>(
                    record.expenseId, record.createdAt, record.sourceDeviceId, record.sourceDeviceName,
                    record.receiptDate, record.merchantName, record.totalAmount, record.currency,
                    record.extractionStatus, record.merchantLocation, record.originalImageFileName,
                    record.sourceImageUri, record.receiptImageRelativePath, record.jsonRelativePath,
                    record.temporaryJsonRelativePath, record.normalizedImageLocalPath, record.status.name,
                    record.failure?.name, record.retryAfterSeconds)
                if (version == 1) values.removeAt(3)
                db.execSQL("INSERT INTO receipt_exports VALUES (${values.joinToString { "?" }})", values.toTypedArray())
            }
            db.version = version
        }
        val migratedDatabase = Room.databaseBuilder(context, ReceiptExportDatabase::class.java, name)
            .addMigrations(ReceiptExportDatabase.MIGRATION_1_2, ReceiptExportDatabase.MIGRATION_2_3, ReceiptExportDatabase.MIGRATION_3_4).allowMainThreadQueries().build()
        try {
            val migrated = RoomReceiptExportRepository(migratedDatabase.receiptExportDao())
            assertEquals(pending, migrated.get(pending.expenseId))
            assertEquals(completed, migrated.get(completed.expenseId))
            val retried = ReceiptExportController(migrated,
                ReceiptExportImagePreparer { _, _ -> error("Existing image snapshot was lost") },
                ReceiptExportImageReader { byteArrayOf(1, 2, 3) },
                ReceiptExportPublisher { record, _ -> assertEquals(pending.jsonRelativePath, record.jsonRelativePath) },
                { "device" }).retry(pending.expenseId)
            assertEquals(pending.expenseId, retried.expenseId)
            assertEquals(pending.receiptImageRelativePath, retried.receiptImageRelativePath)
            assertEquals(ReceiptExportStatus.EXPORTED, retried.status)
            val typed = sampleTypedRecord().copy(expenseId = "typed-expense")
            migrated.save(typed)
            assertEquals(typed, migrated.get(typed.expenseId))
        } finally {
            migratedDatabase.close()
            context.deleteDatabase(name)
        }
    }

}
