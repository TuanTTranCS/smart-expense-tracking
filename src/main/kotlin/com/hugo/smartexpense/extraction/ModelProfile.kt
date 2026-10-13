package com.hugo.smartexpense.extraction

import java.net.URI
import kotlinx.coroutines.flow.first

data class ModelProfile(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val modelId: String,
    val inputMode: RemoteInputMode,
    val structuredOutputFormat: RemoteStructuredOutputFormat,
    val credentialAlias: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val showTailscaleToggle: Boolean = false,
    val category: ApiProfileCategory = ApiProfileCategory.REMOTE_API,
    val configurationError: String? = null,
) {
    fun asProviderOption(): OpenAiCompatibleProviderOption = OpenAiCompatibleProviderOption(
        id = id,
        displayName = displayName,
        baseUrl = baseUrl,
        modelId = modelId,
        inputMode = inputMode,
        apiKeyAlias = credentialAlias,
        structuredOutputFormat = structuredOutputFormat,
        showTailscaleToggle = showTailscaleToggle,
        category = category,
        configurationError = configurationError,
    )

    companion object {
        fun credentialAlias(profileId: String): String = "remote-provider:$profileId"
    }
}

data class ModelProfileDraft(
    val id: String? = null,
    val displayName: String = "",
    val baseUrl: String = "",
    val modelId: String = "",
    val inputMode: RemoteInputMode = RemoteInputMode.DIRECT_IMAGE,
    val structuredOutputFormat: RemoteStructuredOutputFormat = RemoteStructuredOutputFormat.JSON_SCHEMA,
    val apiKey: String = "",
    val showTailscaleToggle: Boolean = false,
    val category: ApiProfileCategory = ApiProfileCategory.REMOTE_API,
    val credentialEdit: CredentialEdit = CredentialEdit.Keep,
) {
    override fun toString(): String = "ModelProfileDraft(id=$id, category=$category, credential=[redacted])"
}

sealed interface CredentialEdit {
    data object Keep : CredentialEdit
    data object Clear : CredentialEdit
    class Replace(val value: String) : CredentialEdit {
        init { require(value.isNotBlank() && value.none { it.code < 32 || it.code == 127 }) { "Enter a nonblank header-safe API key." } }
        override fun toString(): String = "Replace([redacted])"
    }
}

data class ModelProfileValidationResult(
    val normalizedDisplayName: String,
    val normalizedBaseUrl: String,
    val normalizedModelId: String,
    val errors: Map<ModelProfileField, String>,
) {
    val isValid: Boolean get() = errors.isEmpty()
}

enum class ModelProfileField {
    DISPLAY_NAME,
    BASE_URL,
    MODEL_ID,
    CATEGORY,
    INPUT_MODE,
    OUTPUT_FORMAT,
    TAILSCALE,
}

class ModelProfileValidator {
    fun validate(draft: ModelProfileDraft): ModelProfileValidationResult {
        val displayName = draft.displayName.trim()
        val baseUrl = draft.baseUrl.trim().trimEnd('/')
        val modelId = draft.modelId.trim()
        val errors = buildMap {
            if (displayName.isBlank()) put(ModelProfileField.DISPLAY_NAME, "Display name is required.")
            runCatching { ApiEndpointValidator.normalize(draft.baseUrl, draft.category) }.exceptionOrNull()?.let {
                put(ModelProfileField.BASE_URL, it.message ?: "Correct the API endpoint.")
            }
            if (draft.category == ApiProfileCategory.SAME_PHONE_SERVER) {
                if (draft.inputMode != RemoteInputMode.DIRECT_IMAGE) put(ModelProfileField.INPUT_MODE, "Same-phone server requires direct image input.")
                if (draft.structuredOutputFormat != RemoteStructuredOutputFormat.PROMPT_ONLY_JSON) put(ModelProfileField.OUTPUT_FORMAT, "Same-phone server requires Prompt-only JSON.")
                if (draft.showTailscaleToggle) put(ModelProfileField.TAILSCALE, "Same-phone server cannot enable Tailscale.")
            }
            if (modelId.isBlank()) put(ModelProfileField.MODEL_ID, "Model ID is required.")
        }
        return ModelProfileValidationResult(displayName, baseUrl, modelId, errors)
    }

    private fun isValidBaseUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
            !uri.host.isNullOrBlank() && uri.rawQuery == null && uri.rawFragment == null
    }.getOrDefault(false)
}

data class ModelProfileSelectorState(
    val selectedRemoteProfileId: String? = null,
    val remoteProvidersEnabled: Boolean = false,
)

interface ModelProfileRepository {
    fun observeProfiles(): kotlinx.coroutines.flow.Flow<List<ModelProfile>>
    fun observeSelectorState(): kotlinx.coroutines.flow.Flow<ModelProfileSelectorState>
    suspend fun getProfile(id: String): ModelProfile?
    suspend fun saveProfile(profile: ModelProfile)
    suspend fun saveProfiles(profiles: List<ModelProfile>)
    /** Atomically verify the source snapshot and insert without replacing any existing row. */
    suspend fun insertDuplicate(sourceSnapshot: ModelProfile, copy: ModelProfile) {
        throw UnsupportedOperationException("Duplicate insertion is unavailable.")
    }
    suspend fun publishDuplicate(sourceSnapshot: ModelProfile, copy: ModelProfile, stagingId: String) {
        insertDuplicate(sourceSnapshot, copy)
        removeJournal(stagingId)
    }
    suspend fun credentialAliasInUse(alias: String): Boolean {
        throw UnsupportedOperationException("Credential reference lookup is unavailable.")
    }
    suspend fun selectProfile(id: String?)
    suspend fun setRemoteProvidersEnabled(enabled: Boolean)
    suspend fun deleteProfile(id: String)
    suspend fun getSelectorState(): ModelProfileSelectorState = observeSelectorState().first()
    suspend fun journalEntries(): List<CredentialMutationJournal> = emptyList()
    suspend fun recordJournal(entry: CredentialMutationJournal) { throw UnsupportedOperationException("Credential journal unavailable.") }
    suspend fun removeJournal(id: String) { throw UnsupportedOperationException("Credential journal unavailable.") }
    suspend fun publishProfile(profile: ModelProfile, cleanup: CredentialMutationJournal?) {
        saveProfile(profile)
        cleanup?.let { recordJournal(it) }
    }
    suspend fun deleteProfileWithCleanup(id: String, cleanup: CredentialMutationJournal) {
        recordJournal(cleanup)
        deleteProfile(id)
    }
}

data class CredentialMutationJournal(val id: String, val profileId: String, val alias: String, val phase: String)

interface WritableApiKeyStore : ApiKeyStore {
    fun put(alias: String, value: String)
    fun remove(alias: String)
    /** A failed write may have reached storage and must be compensated by the caller. */
    fun putDurablyIfAbsent(alias: String, value: String): DurableCredentialWriteResult {
        throw UnsupportedOperationException("Durable credential writes are unavailable.")
    }
    fun removeDurably(alias: String): Boolean {
        throw UnsupportedOperationException("Durable credential removal is unavailable.")
    }
}

enum class DurableCredentialWriteResult { STORED, ALREADY_EXISTS, FAILED }
