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

    private fun profile(id: String, name: String) = ModelProfile(
        id, name, "https://example.test/v1", "model-$id", RemoteInputMode.DIRECT_IMAGE,
        RemoteStructuredOutputFormat.JSON_SCHEMA, ModelProfile.credentialAlias(id), 1, 1,
    )
}
