package com.hugo.smartexpense.app.modelprofile.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hugo.smartexpense.app.AndroidApiKeyStore
import com.hugo.smartexpense.app.modelprofile.data.ModelProfileDatabase
import com.hugo.smartexpense.app.modelprofile.data.RoomModelProfileRepository
import com.hugo.smartexpense.app.modelprofile.transfer.ProviderConfigTransferService
import com.hugo.smartexpense.extraction.*
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DuplicateModelProfilePersistenceTest {
    @Test fun durableDuplicateSurvivesReopenAndCredentialMutationsRemainIndependent() = runBlocking(Dispatchers.IO) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val token = UUID.randomUUID().toString()
        val sourceId = "duplicate-test-source-$token"
        val copyId = "duplicate-test-copy-$token"
        val name = "duplicate-test-$token.db"
        val sourceAlias = ModelProfile.credentialAlias(sourceId)
        val copyAlias = ModelProfile.credentialAlias(copyId)
        val source = ModelProfile(sourceId, "Saved Provider", "http://127.0.0.1:1234/v1", "model:exact",
            RemoteInputMode.OCR_TEXT, RemoteStructuredOutputFormat.JSON_OBJECT, sourceAlias, 1, 2, true)
        val store = AndroidApiKeyStore(context)
        try {
            assertEquals(DurableCredentialWriteResult.STORED, store.putDurablyIfAbsent(sourceAlias, "original-test-key"))
            val savedDatabase = Room.databaseBuilder(context, ModelProfileDatabase::class.java, name).build()
            try {
                val repository = RoomModelProfileRepository(savedDatabase.modelProfileDao())
                repository.saveProfile(source)
                repository.selectProfile(sourceId)
                repository.setRemoteProvidersEnabled(false)
                val result = DuplicateModelProfileService(repository, store, { copyId }, { 100 }).duplicate(sourceId)
                assertTrue(result is DuplicateModelProfileResult.Success)
                val copy = (result as DuplicateModelProfileResult.Success).copy
                assertEquals(source.copy(id = copyId, displayName = "Saved Provider (copy)", credentialAlias = copyAlias,
                    createdAtEpochMillis = 100, updatedAtEpochMillis = 100), copy)
            } finally {
                savedDatabase.close()
            }
            val reopenedStore = AndroidApiKeyStore(context)
            val reopenedDatabase = Room.databaseBuilder(context, ModelProfileDatabase::class.java, name).build()
            try {
                val repository = RoomModelProfileRepository(reopenedDatabase.modelProfileDao())
                val copy = repository.getProfile(copyId)!!
                assertEquals(source, repository.getProfile(sourceId))
                assertEquals("Saved Provider (copy)", copy.displayName)
                assertEquals("original-test-key", reopenedStore.get(copyAlias))
                assertEquals("original-test-key", reopenedStore.get(sourceAlias))
                assertEquals(ModelProfileSelectorState(sourceId, false), repository.observeSelectorState().first())

                val exported = ProviderConfigTransferService(repository).createExportJson(
                    repository.observeProfiles().first(), repository.observeSelectorState().first(),
                )
                assertFalse(exported.contains("original-test-key"))
                assertFalse(exported.contains(sourceAlias))
                assertFalse(exported.contains(copyAlias))
                assertFalse(exported.contains("credentialAlias"))
                assertFalse(exported.contains("createdAtEpochMillis"))
                assertTrue(exported.contains("\"credentialsIncluded\":false"))

                assertTrue(reopenedStore.removeDurably(sourceAlias))
                assertNull(reopenedStore.get(sourceAlias))
                assertEquals("original-test-key", reopenedStore.get(copyAlias))
                assertEquals(DurableCredentialWriteResult.STORED, reopenedStore.putDurablyIfAbsent(sourceAlias, "edited-source-key"))
                repository.saveProfile(source.copy(modelId = "edited-source-model"))
                assertEquals("model:exact", repository.getProfile(copyId)!!.modelId)

                assertTrue(reopenedStore.removeDurably(copyAlias))
                assertNull(reopenedStore.get(copyAlias))
                assertEquals("edited-source-key", reopenedStore.get(sourceAlias))
                assertEquals(DurableCredentialWriteResult.STORED, reopenedStore.putDurablyIfAbsent(copyAlias, "edited-copy-key"))
                repository.saveProfile(copy.copy(modelId = "edited-copy-model"))
                assertEquals("edited-source-model", repository.getProfile(sourceId)!!.modelId)
                repository.deleteProfile(copyId)
                assertTrue(reopenedStore.removeDurably(copyAlias))
                assertEquals("edited-source-key", reopenedStore.get(sourceAlias))
                assertEquals(sourceId, repository.observeSelectorState().first().selectedRemoteProfileId)
            } finally {
                reopenedDatabase.close()
            }
        } finally {
            store.removeDurably(sourceAlias)
            store.removeDurably(copyAlias)
            context.deleteDatabase(name)
        }
    }
}
