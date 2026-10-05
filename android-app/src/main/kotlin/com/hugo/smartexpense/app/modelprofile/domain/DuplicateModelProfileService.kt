package com.hugo.smartexpense.app.modelprofile.domain

import com.hugo.smartexpense.extraction.DurableCredentialWriteResult
import com.hugo.smartexpense.extraction.ModelProfile
import com.hugo.smartexpense.extraction.ModelProfileRepository
import com.hugo.smartexpense.extraction.WritableApiKeyStore
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

sealed interface DuplicateModelProfileResult {
    data class Success(val sourceName: String, val copy: ModelProfile) : DuplicateModelProfileResult
    data class Failure(val message: String) : DuplicateModelProfileResult
}

/** The caller serializes this operation with other profile and credential mutations. */
class DuplicateModelProfileService(
    private val repository: ModelProfileRepository,
    private val credentialStore: WritableApiKeyStore,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun duplicate(sourceId: String): DuplicateModelProfileResult {
        var source: ModelProfile? = null
        var copy: ModelProfile? = null
        var ownsCredential = false
        var insertAttempted = false
        try {
            return withContext(Dispatchers.IO) {
                val saved = repository.getProfile(sourceId)
                    ?: return@withContext failure("The source profile no longer exists. Refresh Settings and retry.")
                source = saved
                val id = idFactory()
                val alias = ModelProfile.credentialAlias(id)
                if (id.isBlank() || id == saved.id || alias == saved.credentialAlias ||
                    repository.getProfile(id) != null || repository.credentialAliasInUse(alias) ||
                    credentialStore.get(alias) != null
                ) return@withContext failure("A new profile identity could not be allocated. Retry duplication.")
                val now = clock()
                val duplicate = saved.copy(
                    id = id, displayName = saved.displayName + " (copy)", credentialAlias = alias,
                    createdAtEpochMillis = now, updatedAtEpochMillis = now,
                )
                copy = duplicate
                val key = credentialStore.get(saved.credentialAlias)
                currentCoroutineContext().ensureActive()
                if (key != null) {
                    // Even a failed durable write may have changed preferences in memory/on disk.
                    ownsCredential = true
                    when (credentialStore.putDurablyIfAbsent(alias, key)) {
                        DurableCredentialWriteResult.STORED -> Unit
                        DurableCredentialWriteResult.ALREADY_EXISTS -> {
                            ownsCredential = false
                            return@withContext failure("A new credential identity could not be allocated. Retry duplication.")
                        }
                        DurableCredentialWriteResult.FAILED -> error("Credential storage failed.")
                    }
                }
                currentCoroutineContext().ensureActive()
                insertAttempted = true
                repository.insertDuplicate(saved, duplicate)
                DuplicateModelProfileResult.Success(saved.displayName, duplicate)
            }
        } catch (error: Exception) {
            return withContext(NonCancellable + Dispatchers.IO) {
                val pending = copy
                if (insertAttempted && pending != null) {
                    val committed = try {
                        repository.getProfile(pending.id)
                    } catch (_: Exception) {
                        // Unknown commit status: retaining an alias is safer than breaking a saved row.
                        return@withContext failure("The copy's save status could not be confirmed. Refresh Settings before retrying.")
                    }
                    if (committed == pending) {
                        return@withContext DuplicateModelProfileResult.Success(source!!.displayName, pending)
                    }
                }
                if (ownsCredential && pending != null) {
                    val cleanupSucceeded = try {
                        // Never remove an alias that another committed profile now references.
                        !repository.credentialAliasInUse(pending.credentialAlias) &&
                            credentialStore.removeDurably(pending.credentialAlias)
                    } catch (_: Exception) {
                        false
                    }
                    if (!cleanupSucceeded) return@withContext failure(
                        "The profile was not duplicated, and its temporary credential could not be removed. Retry after checking secure storage.",
                    )
                }
                failure(if (error is CancellationException) "Profile duplication was cancelled. Retry when ready."
                    else "The profile could not be duplicated. Check secure storage and retry.")
            }
        }
    }

    private fun failure(message: String) = DuplicateModelProfileResult.Failure(message)
}
