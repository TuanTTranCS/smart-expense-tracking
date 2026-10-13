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
    @Test fun upgradesV2PreservingLoopbackCategoryAndJournalAndReopensCleanV3() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "category-v2-migration-test.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE model_profiles (id TEXT NOT NULL PRIMARY KEY, display_name TEXT NOT NULL, base_url TEXT NOT NULL, model_id TEXT NOT NULL, input_mode TEXT NOT NULL, structured_output_format TEXT NOT NULL, credential_alias TEXT NOT NULL, created_at_epoch_millis INTEGER NOT NULL, updated_at_epoch_millis INTEGER NOT NULL, show_tailscale_toggle INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE model_selector_state (singletonId INTEGER NOT NULL PRIMARY KEY, selected_remote_profile_id TEXT, remote_providers_enabled INTEGER NOT NULL)")
            db.execSQL("INSERT INTO model_profiles VALUES ('saved', 'Saved', 'http://127.0.0.1:8080/v1', 'model', 'OCR_TEXT', 'JSON_OBJECT', 'kept-alias', 10, 20, 1)")
            db.execSQL("INSERT INTO model_selector_state VALUES (1, 'saved', 1)")
            db.version = 2
        }
        fun open() = Room.databaseBuilder(context, ModelProfileDatabase::class.java, name)
            .addMigrations(ModelProfileDatabase.MIGRATION_1_2, ModelProfileDatabase.MIGRATION_2_3)
            .allowMainThreadQueries().build()
        try {
            open().let { database ->
                try {
                    val repository = RoomModelProfileRepository(database.modelProfileDao())
                    val saved = repository.getProfile("saved")!!
                    assertEquals(com.hugo.smartexpense.extraction.ApiProfileCategory.REMOTE_API, saved.category)
                    assertEquals(com.hugo.smartexpense.extraction.RemoteInputMode.OCR_TEXT, saved.inputMode)
                    assertEquals(com.hugo.smartexpense.extraction.RemoteStructuredOutputFormat.JSON_OBJECT, saved.structuredOutputFormat)
                    assertTrue(saved.showTailscaleToggle)
                    assertEquals("kept-alias", saved.credentialAlias)
                    assertEquals(10L, saved.createdAtEpochMillis)
                    assertEquals(20L, saved.updatedAtEpochMillis)
                    assertEquals("saved", repository.getSelectorState().selectedRemoteProfileId)
                    repository.recordJournal(com.hugo.smartexpense.extraction.CredentialMutationJournal("j", "saved", "orphan", "STAGED"))
                } finally { database.close() }
            }
            open().let { database ->
                try {
                    val repository = RoomModelProfileRepository(database.modelProfileDao())
                    assertEquals("orphan", repository.journalEntries().single().alias)
                    assertEquals(1, repository.observeProfiles().first().size)
                    repository.removeJournal("j")
                    assertTrue(repository.journalEntries().isEmpty())
                } finally { database.close() }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun cleanV3PersistsSamePhoneAndJournalAfterReopen() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "category-clean-v3-test.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, ModelProfileDatabase::class.java, name).allowMainThreadQueries().build()
        val profile = com.hugo.smartexpense.extraction.ModelProfile("phone", "Phone", "http://127.0.0.1:8091/expense/v1", "m",
            com.hugo.smartexpense.extraction.RemoteInputMode.DIRECT_IMAGE,
            com.hugo.smartexpense.extraction.RemoteStructuredOutputFormat.PROMPT_ONLY_JSON, "credential-free", 1, 2,
            category = com.hugo.smartexpense.extraction.ApiProfileCategory.SAME_PHONE_SERVER)
        try {
            open().let { database ->
                try {
                    val repository = RoomModelProfileRepository(database.modelProfileDao())
                    repository.saveProfile(profile)
                    repository.selectProfile(profile.id)
                    repository.recordJournal(com.hugo.smartexpense.extraction.CredentialMutationJournal("cleanup", "phone", "orphan", "CLEANUP"))
                } finally { database.close() }
            }
            open().let { database ->
                try {
                    val repository = RoomModelProfileRepository(database.modelProfileDao())
                    assertEquals(profile, repository.getProfile(profile.id))
                    repository.setRemoteProvidersEnabled(false)
                    assertEquals(profile.id, repository.getSelectorState().selectedRemoteProfileId)
                    assertEquals("cleanup", repository.journalEntries().single().id)
                } finally { database.close() }
            }
        } finally { context.deleteDatabase(name) }
    }

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
            .addMigrations(ModelProfileDatabase.MIGRATION_1_2, ModelProfileDatabase.MIGRATION_2_3).allowMainThreadQueries().build()
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
