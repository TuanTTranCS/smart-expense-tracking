package com.hugo.smartexpense.app.modelprofile.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hugo.smartexpense.extraction.ModelProfile
import com.hugo.smartexpense.extraction.RemoteInputMode
import com.hugo.smartexpense.extraction.RemoteStructuredOutputFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class RoomModelProfileRepositoryTest {
    private lateinit var database: ModelProfileDatabase
    private lateinit var repository: RoomModelProfileRepository

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), ModelProfileDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RoomModelProfileRepository(database.modelProfileDao())
    }

    @After fun tearDown() = database.close()

    @Test fun ordersProfilesAndPersistsSelectorAcrossRepositoryInstances() = runTest {
        repository.saveProfile(profile("2", "zeta"))
        repository.saveProfile(profile("1", "Alpha"))
        repository.setRemoteProvidersEnabled(true)
        repository.selectProfile("2")

        assertEquals(listOf("1", "2"), repository.observeProfiles().first().map { it.id })
        val recreated = RoomModelProfileRepository(database.modelProfileDao())
        assertEquals("2", recreated.observeSelectorState().first().selectedRemoteProfileId)
    }

    @Test fun deletingSelectedProfileClearsSelectionAtomically() = runTest {
        repository.saveProfile(profile("1", "Provider"))
        repository.setRemoteProvidersEnabled(true)
        repository.selectProfile("1")
        repository.deleteProfile("1")

        assertNull(repository.observeSelectorState().first().selectedRemoteProfileId)
        assertFalse(repository.observeProfiles().first().any())
    }

    @Test fun danglingSelectionIsRejected() = runTest {
        repository.selectProfile("missing")
        assertNull(repository.observeSelectorState().first().selectedRemoteProfileId)
    }

    @Test fun savesImportedProfilesTogetherWithoutChangingSelector() = runTest {
        repository.saveProfile(profile("selected", "Selected"))
        repository.setRemoteProvidersEnabled(true)
        repository.selectProfile("selected")

        repository.saveProfiles(listOf(profile("import-1", "Imported One"), profile("import-2", "Imported Two")))

        assertEquals(
            listOf("import-1", "import-2", "selected"),
            repository.observeProfiles().first().map { it.id },
        )
        assertEquals("selected", repository.observeSelectorState().first().selectedRemoteProfileId)
        assertEquals(true, repository.observeSelectorState().first().remoteProvidersEnabled)
    }

    @Test fun duplicateInsertionPersistsEveryFieldWithoutChangingSelection() = runTest {
        val source = profile("source", "Provider").copy(showTailscaleToggle = true)
        val copy = source.copy(id = "copy", displayName = "Provider (copy)", credentialAlias = "remote-provider:copy",
            createdAtEpochMillis = 100, updatedAtEpochMillis = 100)
        repository.saveProfile(source)
        repository.selectProfile(source.id)
        repository.setRemoteProvidersEnabled(true)
        repository.insertDuplicate(source, copy)
        val recreated = RoomModelProfileRepository(database.modelProfileDao())
        assertEquals(copy, recreated.getProfile(copy.id))
        assertEquals(source, recreated.getProfile(source.id))
        assertEquals(source.id, recreated.observeSelectorState().first().selectedRemoteProfileId)
        assertTrue(recreated.observeSelectorState().first().remoteProvidersEnabled)
    }

    @Test fun duplicateInsertionRejectsChangedDeletedSourceAndIdentityOrAliasCollisions() = runTest {
        val source = profile("source", "Provider")
        val copy = source.copy(id = "copy", credentialAlias = "remote-provider:copy")
        repository.saveProfile(source)
        repository.saveProfile(source.copy(modelId = "changed"))
        assertFailsWith<IllegalStateException> { repository.insertDuplicate(source, copy) }
        assertNull(repository.getProfile(copy.id))
        repository.deleteProfile(source.id)
        assertFailsWith<IllegalStateException> { repository.insertDuplicate(source, copy) }
        repository.saveProfile(source)
        val existing = profile("copy", "Keep me").copy(credentialAlias = "independent")
        repository.saveProfile(existing)
        assertFailsWith<android.database.sqlite.SQLiteConstraintException> { repository.insertDuplicate(source, copy) }
        assertEquals(existing, repository.getProfile("copy"))
        repository.deleteProfile("copy")
        repository.saveProfile(profile("other", "Other").copy(credentialAlias = copy.credentialAlias))
        assertFailsWith<IllegalStateException> { repository.insertDuplicate(source, copy) }
        assertNull(repository.getProfile(copy.id))
    }

    @Test fun duplicateSurvivesClosingAndReopeningDatabase() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "duplicate-persistence-${java.util.UUID.randomUUID()}.db"
        val source = profile("source", "Provider").copy(showTailscaleToggle = true)
        val copy = source.copy(id = "copy", displayName = "Provider (copy)", credentialAlias = "remote-provider:copy",
            createdAtEpochMillis = 200, updatedAtEpochMillis = 200)
        try {
            val savedDatabase = Room.databaseBuilder(context, ModelProfileDatabase::class.java, name).build()
            try {
                val saved = RoomModelProfileRepository(savedDatabase.modelProfileDao())
                saved.saveProfile(source)
                saved.selectProfile(source.id)
                saved.insertDuplicate(source, copy)
            } finally {
                savedDatabase.close()
            }
            val reopenedDatabase = Room.databaseBuilder(context, ModelProfileDatabase::class.java, name).build()
            try {
                val reopened = RoomModelProfileRepository(reopenedDatabase.modelProfileDao())
                assertEquals(copy, reopened.getProfile(copy.id))
                assertEquals(source, reopened.getProfile(source.id))
                assertEquals(source.id, reopened.observeSelectorState().first().selectedRemoteProfileId)
            } finally {
                reopenedDatabase.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun profile(id: String, name: String) = ModelProfile(
        id, name, "https://example.test/v1", "model-$id", RemoteInputMode.DIRECT_IMAGE,
        RemoteStructuredOutputFormat.JSON_SCHEMA, ModelProfile.credentialAlias(id), 1, 1,
    )
}
