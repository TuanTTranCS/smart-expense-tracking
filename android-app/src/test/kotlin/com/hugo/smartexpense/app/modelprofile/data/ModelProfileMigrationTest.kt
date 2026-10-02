package com.hugo.smartexpense.app.modelprofile.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ModelProfileMigrationTest {
    @Test fun upgradesRealV1DatabasePreservingMetadataSelectionAndRemoteOptIn() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "tailscale-migration-test.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE model_profiles (id TEXT NOT NULL PRIMARY KEY, display_name TEXT NOT NULL, base_url TEXT NOT NULL, model_id TEXT NOT NULL, input_mode TEXT NOT NULL, structured_output_format TEXT NOT NULL, credential_alias TEXT NOT NULL, created_at_epoch_millis INTEGER NOT NULL, updated_at_epoch_millis INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE model_selector_state (singletonId INTEGER NOT NULL PRIMARY KEY, selected_remote_profile_id TEXT, remote_providers_enabled INTEGER NOT NULL)")
            db.execSQL("INSERT INTO model_profiles VALUES ('saved', 'Saved', 'https://example.test/v1', 'model', 'DIRECT_IMAGE', 'JSON_SCHEMA', 'kept-alias', 1, 2)")
            db.execSQL("INSERT INTO model_selector_state VALUES (1, 'saved', 1)")
            db.version = 1
        }
        val database = Room.databaseBuilder(context, ModelProfileDatabase::class.java, name)
            .addMigrations(ModelProfileDatabase.MIGRATION_1_2).allowMainThreadQueries().build()
        try {
            val repository = RoomModelProfileRepository(database.modelProfileDao())
            val migrated = repository.getProfile("saved")!!
            assertFalse(migrated.showTailscaleToggle)
            assertEquals("kept-alias", migrated.credentialAlias)
            assertEquals(1L, migrated.createdAtEpochMillis)
            assertEquals(2L, migrated.updatedAtEpochMillis)
            assertEquals("saved", repository.observeSelectorState().first().selectedRemoteProfileId)
            assertTrue(repository.observeSelectorState().first().remoteProvidersEnabled)
            repository.saveProfile(migrated.copy(showTailscaleToggle = true))
            assertTrue(repository.getProfile("saved")!!.showTailscaleToggle)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
