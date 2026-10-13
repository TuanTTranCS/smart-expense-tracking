package com.hugo.smartexpense.app.modelprofile.domain

import com.hugo.smartexpense.extraction.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.*

class ProfileConfigurationCoordinatorTest {
    private val profile = ModelProfile("p", "Phone", ApiEndpointValidator.SAME_PHONE_DEFAULT, "model",
        RemoteInputMode.DIRECT_IMAGE, RemoteStructuredOutputFormat.PROMPT_ONLY_JSON, "legacy", 1, 2,
        category = ApiProfileCategory.SAME_PHONE_SERVER)

    @Test fun frozenAdmissionsNeverObserveMixedEndpointAndKeyAndClearIsExplicit() = runTest {
        val repo = Repository(profile)
        val store = Store().apply { values["legacy"] = "old-secret" }
        var revision = 0
        val coordinator = ProfileConfigurationCoordinator(repo, store) { "r${revision++}" }
        val old = coordinator.snapshot(profile)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        repo.beforePublish = { entered.complete(Unit); release.await() }
        val saving = async { coordinator.save(profile.copy(baseUrl = "http://127.0.0.1:8091/expense/v1"), CredentialEdit.Replace("new-secret")) }
        entered.await()
        val admission = async { coordinator.snapshot(profile) }
        assertFalse(admission.isCompleted)
        release.complete(Unit)
        val saved = saving.await()
        val next = admission.await()
        assertEquals(profile.baseUrl, old.profile.baseUrl)
        assertEquals("old-secret", old.apiKey)
        assertEquals(saved.baseUrl, next.profile.baseUrl)
        assertEquals("new-secret", next.apiKey)
        assertNull(store.values["legacy"])
        assertFalse(next.toString().contains("new-secret"))
        assertNull(coordinator.editorSnapshot(saved, CredentialEdit.Clear).apiKey)
        coordinator.save(saved, CredentialEdit.Clear)
        assertNull(coordinator.snapshot(profile).apiKey)
        assertTrue(store.values.isEmpty())
    }

    @Test fun rollbackAndAmbiguousCommitNeverRemoveReferencedKeys() = runTest {
        for (commit in listOf(false, true)) {
            val repo = Repository(profile).apply { failPublish = true; publishBeforeFailure = commit }
            val store = Store().apply { values["legacy"] = "old" }
            var revision = 0
            val coordinator = ProfileConfigurationCoordinator(repo, store) { "r${revision++}" }
            assertFailsWith<IllegalStateException> { coordinator.save(profile, CredentialEdit.Replace("new")) }
            val saved = repo.getProfile("p")!!
            assertEquals(if (commit) "new" else "old", store.get(saved.credentialAlias))
            repo.failPublish = false
            coordinator.recover()
            assertEquals(if (commit) "new" else "old", store.get(saved.credentialAlias))
            assertTrue(repo.journal.isEmpty())
        }
    }

    @Test fun cleanupFailureRemainsDurableAndRecoveryOnlyCleansOwnedUnreferencedAliases() = runTest {
        val repo = Repository(profile)
        val store = Store().apply { values["legacy"] = "old"; values["unrelated"] = "keep"; failRemove = true }
        val coordinator = ProfileConfigurationCoordinator(repo, store)
        assertFailsWith<IllegalStateException> { coordinator.save(profile, CredentialEdit.Clear) }
        assertTrue(repo.journal.values.any { it.alias == "legacy" })
        assertNull(coordinator.snapshot(profile).apiKey)
        store.failRemove = false
        coordinator.recover()
        assertNull(store.values["legacy"])
        assertEquals("keep", store.values["unrelated"])
        coordinator.delete("p")
        assertNull(repo.getProfile("p"))
        assertNull(repo.state.value.selectedRemoteProfileId)
    }

    @Test fun crashRecoveryPreservesCommittedStageAndRemovesOrphanStage() = runTest {
        val repo = Repository(profile)
        val store = Store().apply { values["legacy"] = "saved"; values["orphan"] = "orphan" }
        repo.recordJournal(CredentialMutationJournal("referenced", "p", "legacy", "STAGED"))
        repo.recordJournal(CredentialMutationJournal("orphan", "p", "orphan", "STAGED"))
        ProfileConfigurationCoordinator(repo, store).recover()
        assertEquals("saved", store.values["legacy"])
        assertNull(store.values["orphan"])
        assertTrue(repo.journal.isEmpty())
    }

    @Test fun deletionCommitsBeforeCleanupAndFailedRemovalIsRecoverable() = runTest {
        val repo = Repository(profile)
        val store = Store().apply { values["legacy"] = "saved"; failRemove = true }
        val coordinator = ProfileConfigurationCoordinator(repo, store)
        assertFailsWith<IllegalStateException> { coordinator.delete(profile.id) }
        assertNull(repo.getProfile(profile.id))
        assertNull(repo.getSelectorState().selectedRemoteProfileId)
        assertEquals("saved", store.values["legacy"])
        assertEquals("CLEANUP", repo.journal.values.single().phase)
        store.failRemove = false
        coordinator.recover()
        assertNull(store.values["legacy"])
        assertTrue(repo.journal.isEmpty())
    }

    @Test fun failedDurableWriteRetainsOldConfigurationAndEditorKeepReadsLatestRevision() = runTest {
        val repo = Repository(profile)
        val store = Store().apply { values["legacy"] = "old"; failWrite = true }
        val coordinator = ProfileConfigurationCoordinator(repo, store)
        assertFailsWith<IllegalStateException> { coordinator.save(profile, CredentialEdit.Replace("new")) }
        assertEquals(profile, repo.getProfile("p"))
        store.failWrite = false
        coordinator.save(profile, CredentialEdit.Replace("new"))
        assertEquals("new", coordinator.editorSnapshot(profile, CredentialEdit.Keep).apiKey)
        assertEquals(ApiProfileCategory.SAME_PHONE_SERVER, coordinator.selectedSnapshot().configuration!!.profile.category)
    }

    @Test fun durableAliasCollisionNeverRemovesAnExistingKeyAndReadFailureIsNotNoKey() = runTest {
        val repo = Repository(profile)
        val store = Store().apply { values["legacy"] = "old"; collisionOnWrite = true }
        var revision = 0
        val coordinator = ProfileConfigurationCoordinator(repo, store) { "r${revision++}" }
        assertFailsWith<IllegalStateException> { coordinator.save(profile, CredentialEdit.Replace("new")) }
        assertEquals(profile, repo.getProfile("p"))
        assertTrue(store.values.values.contains("independent-owner"))
        assertTrue(repo.journal.isEmpty())
        store.failRead = true
        val error = assertFailsWith<IllegalStateException> { coordinator.snapshot("p") }
        assertFalse(error.message!!.contains("secret"))
        assertTrue(error.message!!.contains("could not be read"))
    }

    @Test fun keepRejectsUnreadableSavedKeyAndNewKeepDoesNotAdoptAnOrphanCredential() = runTest {
        val repo = Repository(profile)
        val store = Store().apply { values["legacy"] = "old"; failRead = true }
        var revision = 0
        val coordinator = ProfileConfigurationCoordinator(repo, store) { "r${revision++}" }
        assertFailsWith<IllegalStateException> { coordinator.save(profile.copy(modelId = "changed"), CredentialEdit.Keep) }
        assertEquals(profile, repo.getProfile("p"))
        store.failRead = false
        store.values["remote-provider:new"] = "orphan-secret"
        val created = coordinator.save(profile.copy(id = "new", credentialAlias = "remote-provider:new"), CredentialEdit.Keep)
        assertNotEquals("remote-provider:new", created.credentialAlias)
        assertNull(coordinator.snapshot(created).apiKey)
        assertEquals("orphan-secret", store.values["remote-provider:new"])
    }

    private class Repository(initial: ModelProfile) : ModelProfileRepository {
        val rows = mutableMapOf(initial.id to initial)
        val state = MutableStateFlow(ModelProfileSelectorState(initial.id, false))
        val journal = mutableMapOf<String, CredentialMutationJournal>()
        var beforePublish: (suspend () -> Unit)? = null
        var failPublish = false
        var publishBeforeFailure = false
        override fun observeProfiles() = MutableStateFlow(rows.values.toList())
        override fun observeSelectorState() = state
        override suspend fun getProfile(id: String) = rows[id]
        override suspend fun saveProfile(profile: ModelProfile) { rows[profile.id] = profile }
        override suspend fun saveProfiles(profiles: List<ModelProfile>) { profiles.forEach { saveProfile(it) } }
        override suspend fun credentialAliasInUse(alias: String) = rows.values.any { it.credentialAlias == alias }
        override suspend fun selectProfile(id: String?) { state.value = state.value.copy(selectedRemoteProfileId = id) }
        override suspend fun setRemoteProvidersEnabled(enabled: Boolean) { state.value = state.value.copy(remoteProvidersEnabled = enabled) }
        override suspend fun deleteProfile(id: String) { rows.remove(id); selectProfile(null) }
        override suspend fun journalEntries() = journal.values.toList()
        override suspend fun recordJournal(entry: CredentialMutationJournal) { journal[entry.id] = entry }
        override suspend fun removeJournal(id: String) { journal.remove(id) }
        override suspend fun publishProfile(profile: ModelProfile, cleanup: CredentialMutationJournal?) {
            beforePublish?.invoke()
            if (!failPublish || publishBeforeFailure) {
                saveProfile(profile)
                cleanup?.let { recordJournal(it) }
            }
            if (failPublish) error("secret storage diagnostic")
        }
    }
    private class Store : WritableApiKeyStore {
        val values = mutableMapOf<String, String>()
        var failRemove = false
        var failWrite = false
        var failRead = false
        var collisionOnWrite = false
        override fun get(alias: String): String? {
            if (failRead) error("secret read diagnostic")
            return values[alias]
        }
        override fun put(alias: String, value: String) { values[alias] = value }
        override fun remove(alias: String) { values.remove(alias) }
        override fun putDurablyIfAbsent(alias: String, value: String): DurableCredentialWriteResult {
            if (collisionOnWrite) {
                values[alias] = "independent-owner"
                return DurableCredentialWriteResult.ALREADY_EXISTS
            }
            if (values.containsKey(alias)) return DurableCredentialWriteResult.ALREADY_EXISTS
            values[alias] = value
            return if (failWrite) DurableCredentialWriteResult.FAILED else DurableCredentialWriteResult.STORED
        }
        override fun removeDurably(alias: String): Boolean {
            if (failRemove) return false
            values.remove(alias)
            return true
        }
    }
}
