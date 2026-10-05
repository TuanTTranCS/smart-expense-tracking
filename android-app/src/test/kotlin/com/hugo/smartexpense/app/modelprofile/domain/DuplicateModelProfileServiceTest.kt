package com.hugo.smartexpense.app.modelprofile.domain

import com.hugo.smartexpense.extraction.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.*

class DuplicateModelProfileServiceTest {
    private val source = ModelProfile(
        "source", "Provider", "http://lan.test:1234/v1", "namespace/model:version", RemoteInputMode.OCR_TEXT,
        RemoteStructuredOutputFormat.JSON_OBJECT, "actual-legacy-alias", 5, 8, true,
    )

    @Test fun copiesEverySavedFieldAndIndependentCredentialWithFreshIdentity() = runTest {
        val repository = Repository(source)
        val store = Store(mutableMapOf(source.credentialAlias to "secret"))
        val result = assertIs<DuplicateModelProfileResult.Success>(service(repository, store).duplicate(source.id))
        assertEquals("Provider", result.sourceName)
        assertEquals(source.copy(id = "new", displayName = "Provider (copy)", credentialAlias = "remote-provider:new",
            createdAtEpochMillis = 100, updatedAtEpochMillis = 100), result.copy)
        assertEquals(source, repository.getProfile(source.id))
        assertEquals("secret", store.get(result.copy.credentialAlias))
        assertFalse(result.toString().contains("secret"))
        store.remove(source.credentialAlias)
        assertEquals("secret", store.get(result.copy.credentialAlias))
        store.put(source.credentialAlias, "changed")
        store.remove(result.copy.credentialAlias)
        assertEquals("changed", store.get(source.credentialAlias))
        assertEquals(ModelProfileSelectorState("source", true), repository.selector.value)
    }

    @Test fun noKeyAndRepeatedCopyNamesAreValid() = runTest {
        val repository = Repository(source)
        val store = Store()
        assertIs<DuplicateModelProfileResult.Success>(service(repository, store).duplicate("source"))
        val second = DuplicateModelProfileService(repository, store, { "second" }, { 101 }).duplicate("source")
        assertEquals("Provider (copy)", assertIs<DuplicateModelProfileResult.Success>(second).copy.displayName)
        val third = DuplicateModelProfileService(repository, store, { "third" }, { 102 }).duplicate("new")
        assertEquals("Provider (copy) (copy)", assertIs<DuplicateModelProfileResult.Success>(third).copy.displayName)
        assertTrue(store.values.isEmpty())
    }

    @Test fun missingSourceAndCredentialReadFailuresMakeNoMutationAndAreRedacted() = runTest {
        val repository = Repository(source)
        val store = Store()
        assertIs<DuplicateModelProfileResult.Failure>(service(repository, store).duplicate("missing"))
        store.failReadAlias = source.credentialAlias
        val result = service(repository, store).duplicate("source")
        assertIs<DuplicateModelProfileResult.Failure>(result)
        assertFalse(result.toString().contains("secret"))
        assertNull(repository.getProfile("new"))
        assertTrue(store.values.isEmpty())
    }

    @Test fun identityAndAliasCollisionsNeverOverwriteOrRemoveExistingCredentials() = runTest {
        for (collision in listOf("id", "reference", "orphan", "atomic")) {
            val repository = Repository(source)
            val store = Store(mutableMapOf(source.credentialAlias to "secret"))
            when (collision) {
                "id" -> repository.rows["new"] = source.copy(id = "new")
                "reference" -> repository.rows["other"] = source.copy(id = "other", credentialAlias = "remote-provider:new")
                "orphan" -> store.values["remote-provider:new"] = "existing"
                "atomic" -> store.writeResult = DurableCredentialWriteResult.ALREADY_EXISTS
            }
            val rows = repository.rows.toMap()
            val values = store.values.toMap()
            assertIs<DuplicateModelProfileResult.Failure>(service(repository, store).duplicate("source"))
            assertEquals(rows, repository.rows)
            assertEquals(values, store.values)
            assertEquals(0, store.removals)
        }
    }

    @Test fun failedAndThrownWritesAndFailedInsertsCompensateOnlyNewAlias() = runTest {
        for (failure in listOf("write", "throw", "insert", "cancel")) {
            val repository = Repository(source)
            val store = Store(mutableMapOf(source.credentialAlias to "secret"))
            when (failure) {
                "write" -> store.writeResult = DurableCredentialWriteResult.FAILED
                "throw" -> store.throwWrite = true
                "insert" -> repository.onInsert = { _, _ -> error("secret insert failure") }
                "cancel" -> repository.onInsert = { _, _ -> throw CancellationException("secret cancellation") }
            }
            val result = assertIs<DuplicateModelProfileResult.Failure>(service(repository, store).duplicate("source"))
            assertFalse(result.message.contains("secret"))
            assertEquals(mapOf(source.credentialAlias to "secret"), store.values)
            assertNull(repository.getProfile("new"))
            assertEquals(1, store.removals)
        }
    }

    @Test fun cleanupFailureIsReportedWithoutCredentialLeakage() = runTest {
        for (throwCleanup in listOf(false, true)) {
            val repository = Repository(source).apply { onInsert = { _, _ -> error("secret") } }
            val store = Store(mutableMapOf(source.credentialAlias to "secret")).apply {
                failRemove = true; this.throwCleanup = throwCleanup
            }
            val failure = assertIs<DuplicateModelProfileResult.Failure>(service(repository, store).duplicate("source"))
            assertTrue(failure.message.contains("temporary credential could not be removed"))
            assertFalse(failure.message.contains("secret"))
            assertEquals("secret", store.get(source.credentialAlias))
        }
    }

    @Test fun committedInsertFollowedByCancellationRetainsKeyAndReportsSuccess() = runTest {
        val repository = Repository(source).apply {
            onInsert = { _, copy -> rows[copy.id] = copy; throw CancellationException("observer stopped") }
        }
        val store = Store(mutableMapOf(source.credentialAlias to "secret"))
        assertIs<DuplicateModelProfileResult.Success>(service(repository, store).duplicate("source"))
        assertEquals("secret", store.get("remote-provider:new"))
        assertEquals(0, store.removals)
    }

    @Test fun realCancellationRunsCompensationInNonCancellableContext() = runTest {
        val entered = CompletableDeferred<Unit>()
        val repository = Repository(source).apply { onInsert = { _, _ -> entered.complete(Unit); awaitCancellation() } }
        val store = Store(mutableMapOf(source.credentialAlias to "secret"))
        val job = launch { service(repository, store).duplicate("source") }
        entered.await()
        job.cancel()
        job.join()
        assertNull(store.get("remote-provider:new"))
        assertEquals("secret", store.get(source.credentialAlias))
    }

    @Test fun cancellationAfterDurableWriteBeforeInsertRemovesOnlyNewKey() = runTest {
        val repository = Repository(source)
        val store = Store(mutableMapOf(source.credentialAlias to "secret"))
        val job = launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            service(repository, store).duplicate("source")
        }
        store.afterWrite = { job.cancel() }
        job.start()
        job.join()
        assertNull(repository.getProfile("new"))
        assertNull(store.get("remote-provider:new"))
        assertEquals("secret", store.get(source.credentialAlias))
    }

    @Test fun changedSourceSnapshotRejectsCopyAndCompensatesCredential() = runTest {
        val repository = Repository(source)
        val store = Store(mutableMapOf(source.credentialAlias to "secret"))
        store.afterWrite = { repository.rows[source.id] = source.copy(modelId = "changed") }
        assertIs<DuplicateModelProfileResult.Failure>(service(repository, store).duplicate("source"))
        assertNull(repository.getProfile("new"))
        assertEquals("changed", repository.getProfile(source.id)!!.modelId)
        assertEquals(mapOf(source.credentialAlias to "secret"), store.values)
    }

    @Test fun uncertainCommitLookupFailureRetainsKeyAndReportsSafeRecovery() = runTest {
        val repository = Repository(source).apply {
            onInsert = { _, _ -> failLookup = true; throw CancellationException("secret") }
        }
        val store = Store(mutableMapOf(source.credentialAlias to "secret"))
        val result = assertIs<DuplicateModelProfileResult.Failure>(service(repository, store).duplicate("source"))
        assertTrue(result.message.contains("save status could not be confirmed"))
        assertEquals("secret", store.get("remote-provider:new"))
        assertEquals(0, store.removals)
    }

    private fun service(repository: Repository, store: Store) =
        DuplicateModelProfileService(repository, store, { "new" }, { 100 })

    private class Repository(source: ModelProfile) : ModelProfileRepository {
        val rows = mutableMapOf(source.id to source)
        val selector = MutableStateFlow(ModelProfileSelectorState("source", true))
        var failLookup = false
        var onInsert: (suspend (ModelProfile, ModelProfile) -> Unit)? = null
        override fun observeProfiles() = MutableStateFlow(rows.values.toList())
        override fun observeSelectorState() = selector
        override suspend fun getProfile(id: String): ModelProfile? {
            if (failLookup) error("secret lookup error")
            return rows[id]
        }
        override suspend fun credentialAliasInUse(alias: String) = rows.values.any { it.credentialAlias == alias }
        override suspend fun insertDuplicate(sourceSnapshot: ModelProfile, copy: ModelProfile) {
            onInsert?.let { it(sourceSnapshot, copy); return }
            check(rows[sourceSnapshot.id] == sourceSnapshot && copy.id !in rows)
            rows[copy.id] = copy
        }
        override suspend fun saveProfile(profile: ModelProfile) { rows[profile.id] = profile }
        override suspend fun saveProfiles(profiles: List<ModelProfile>) { profiles.forEach { saveProfile(it) } }
        override suspend fun selectProfile(id: String?) { selector.value = selector.value.copy(selectedRemoteProfileId = id) }
        override suspend fun setRemoteProvidersEnabled(enabled: Boolean) { selector.value = selector.value.copy(remoteProvidersEnabled = enabled) }
        override suspend fun deleteProfile(id: String) { rows.remove(id) }
    }

    private class Store(val values: MutableMap<String, String> = mutableMapOf()) : WritableApiKeyStore {
        var failReadAlias: String? = null
        var writeResult = DurableCredentialWriteResult.STORED
        var throwWrite = false
        var failRemove = false
        var throwCleanup = false
        var removals = 0
        var afterWrite: () -> Unit = {}
        override fun get(alias: String): String? {
            if (alias == failReadAlias) error("secret read failure")
            return values[alias]
        }
        override fun put(alias: String, value: String) { values[alias] = value }
        override fun remove(alias: String) { values.remove(alias) }
        override fun putDurablyIfAbsent(alias: String, value: String): DurableCredentialWriteResult {
            if (writeResult == DurableCredentialWriteResult.ALREADY_EXISTS) return writeResult
            values[alias] = value
            afterWrite()
            if (throwWrite) error("secret write failure")
            return writeResult
        }
        override fun removeDurably(alias: String): Boolean {
            removals++
            if (throwCleanup) error("secret cleanup failure")
            if (failRemove) return false
            values.remove(alias)
            return true
        }
    }
}
