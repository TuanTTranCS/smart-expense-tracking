package com.hugo.smartexpense.app.receiptexport

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

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
}
