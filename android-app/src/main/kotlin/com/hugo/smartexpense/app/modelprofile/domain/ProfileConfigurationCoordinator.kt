package com.hugo.smartexpense.app.modelprofile.domain

import com.hugo.smartexpense.extraction.*
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** In-memory only: secrets must never be placed in UI saved state or Room. */
class ProviderConfigurationSnapshot(val profile: ModelProfile, val apiKey: String?) {
    override fun toString(): String = "ProviderConfigurationSnapshot(profileId=${profile.id}, credential=[redacted])"
    fun keyStore(): ApiKeyStore = ApiKeyStore { alias -> if (alias == profile.credentialAlias) apiKey else null }
}

data class SelectedProfileConfigurationSnapshot(
    val selectorState: ModelProfileSelectorState,
    val configuration: ProviderConfigurationSnapshot?,
)

/** Serializes metadata/key publication and admission, never HTTP or model work. */
class ProfileConfigurationCoordinator(
    private val repository: ModelProfileRepository,
    private val credentialStore: WritableApiKeyStore,
    private val revisionFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutex = lockFor(repository)

    suspend fun <T> coordinated(block: suspend () -> T): T = mutex.withLock { block() }

    suspend fun snapshot(profileId: String): ProviderConfigurationSnapshot = coordinated {
        capture(repository.getProfile(profileId) ?: error("The profile no longer exists. Refresh Settings."))
    }

    /** Always re-read metadata; stale UI copies cannot be paired with a newly saved key. */
    suspend fun snapshot(profile: ModelProfile): ProviderConfigurationSnapshot = snapshot(profile.id)

    suspend fun selectedSnapshot(): SelectedProfileConfigurationSnapshot = coordinated {
        val state = repository.getSelectorState()
        val saved = state.selectedRemoteProfileId?.let { repository.getProfile(it) }
        require(saved?.configurationError == null) { saved?.configurationError ?: "Repair the profile configuration." }
        val profile = saved?.takeIf { it.category == ApiProfileCategory.SAME_PHONE_SERVER || state.remoteProvidersEnabled }
        SelectedProfileConfigurationSnapshot(state, profile?.let(::capture))
    }

    suspend fun editorSnapshot(profile: ModelProfile, edit: CredentialEdit): ProviderConfigurationSnapshot = coordinated {
        val key = when (edit) {
            CredentialEdit.Keep -> readKey(repository.getProfile(profile.id)?.credentialAlias ?: profile.credentialAlias)
            CredentialEdit.Clear -> null
            is CredentialEdit.Replace -> edit.value
        }
        validatedSnapshot(profile, key)
    }

    suspend fun save(profile: ModelProfile, edit: CredentialEdit = CredentialEdit.Keep): ModelProfile = coordinated {
        validate(profile)
        withContext(NonCancellable) {
            val existing = repository.getProfile(profile.id)
            if (edit == CredentialEdit.Keep) {
                val alias = existing?.credentialAlias ?: "${ModelProfile.credentialAlias(profile.id)}:revision:${revisionFactory()}"
                val key = readKey(alias)
                if (existing == null) {
                    check(!repository.credentialAliasInUse(alias) && key == null) {
                        "A new credential identity could not be allocated. Retry saving."
                    }
                }
                val kept = profile.copy(credentialAlias = alias)
                validatedSnapshot(kept, key)
                repository.saveProfile(kept)
                return@withContext kept
            }
            val alias = "${ModelProfile.credentialAlias(profile.id)}:revision:${revisionFactory()}"
            check(!repository.credentialAliasInUse(alias) && readKey(alias) == null) {
                "A new credential identity could not be allocated. Retry saving."
            }
            val staged = CredentialMutationJournal(revisionFactory(), profile.id, alias, "STAGED")
            repository.recordJournal(staged) // durable ownership before any secure-store mutation
            val published = profile.copy(credentialAlias = alias)
            var ownsAlias = true
            try {
                if (edit is CredentialEdit.Replace) {
                    val result = credentialStore.putDurablyIfAbsent(alias, edit.value)
                    if (result == DurableCredentialWriteResult.ALREADY_EXISTS) {
                        ownsAlias = false
                        repository.removeJournal(staged.id)
                    }
                    check(result == DurableCredentialWriteResult.STORED) {
                        "The credential could not be stored durably. Retry saving."
                    }
                }
                val cleanup = existing?.credentialAlias?.takeIf { it != alias }?.let {
                    CredentialMutationJournal(revisionFactory(), profile.id, it, "CLEANUP")
                }
                repository.publishProfile(published, cleanup)
                repository.removeJournal(staged.id)
                cleanup?.let { clean(it) }
                published
            } catch (_: Exception) {
                // A Room exception can occur after commit. Never remove a referenced revision.
                val committed = try { repository.getProfile(profile.id) == published } catch (_: Exception) {
                    error("Save status is uncertain. Refresh Settings and recover credential cleanup before retrying.")
                }
                if (!committed && ownsAlias) runCatching { clean(staged) }
                error(if (committed) "Profile saved, but secure credential cleanup is pending. Retry credential recovery."
                    else "Profile was not saved. Retry saving or credential recovery.")
            }
        }
    }

    suspend fun delete(profileId: String): Unit = coordinated {
        withContext(NonCancellable) {
            val profile = repository.getProfile(profileId) ?: return@withContext
            val entry = CredentialMutationJournal(revisionFactory(), profileId, profile.credentialAlias, "CLEANUP")
            repository.deleteProfileWithCleanup(profileId, entry)
            clean(entry)
        }
    }

    /** Only journal-owned aliases are examined. Referenced credentials are always retained. */
    suspend fun recover(): Unit = coordinated {
        withContext(NonCancellable) {
            repository.journalEntries().forEach { entry ->
                check(entry.phase == "STAGED" || entry.phase == "CLEANUP") {
                    "Credential recovery has an unknown journal phase. Repair configuration storage before cleanup."
                }
                if (repository.credentialAliasInUse(entry.alias)) {
                    if (entry.phase == "STAGED") repository.removeJournal(entry.id)
                    else error("Credential cleanup is pending for a referenced alias. Repair the profile configuration.")
                } else clean(entry)
            }
        }
    }

    private suspend fun clean(entry: CredentialMutationJournal) {
        check(!repository.credentialAliasInUse(entry.alias)) { "Credential cleanup is pending for a referenced alias." }
        check(credentialStore.removeDurably(entry.alias)) { "Secure credential cleanup is pending. Retry credential recovery." }
        repository.removeJournal(entry.id)
    }

    private fun readKey(alias: String): String? = try { credentialStore.get(alias) } catch (_: Exception) {
        error("Secure credential could not be read. Repair credential storage and retry.")
    }
    private fun capture(profile: ModelProfile): ProviderConfigurationSnapshot {
        validate(profile)
        return validatedSnapshot(profile, readKey(profile.credentialAlias))
    }
    private fun validatedSnapshot(profile: ModelProfile, key: String?): ProviderConfigurationSnapshot {
        validate(profile)
        require(key == null || (key.isNotBlank() && key.none { it.code < 32 || it.code == 127 })) {
            "Repair the saved API key before using this profile."
        }
        return ProviderConfigurationSnapshot(profile, key)
    }
    private fun validate(profile: ModelProfile) {
        require(profile.configurationError == null) { profile.configurationError ?: "Repair the profile configuration." }
        val validation = ModelProfileValidator().validate(ModelProfileDraft(
            id = profile.id, displayName = profile.displayName, baseUrl = profile.baseUrl, modelId = profile.modelId,
            inputMode = profile.inputMode, structuredOutputFormat = profile.structuredOutputFormat,
            showTailscaleToggle = profile.showTailscaleToggle, category = profile.category,
        ))
        require(validation.isValid) { validation.errors.values.joinToString(" ") }
    }

    companion object {
        suspend fun <T> withRepositoryLock(repository: ModelProfileRepository, block: suspend () -> T): T =
            lockFor(repository).withLock { block() }
        // Repository identity defines the shared boundary even when a service constructs a wrapper.
        private val locks = java.util.WeakHashMap<ModelProfileRepository, Mutex>()
        private fun lockFor(repository: ModelProfileRepository): Mutex = synchronized(locks) { locks.getOrPut(repository) { Mutex() } }
    }
}
