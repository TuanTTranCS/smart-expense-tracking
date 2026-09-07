package com.hugo.smartexpense.app.modelprofile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hugo.smartexpense.app.modelprofile.domain.RemoteModelClientFactory
import com.hugo.smartexpense.app.modelprofile.domain.SelectedReceiptModelProviderResolver
import com.hugo.smartexpense.app.modelprofile.migration.LegacyMigrationResult
import com.hugo.smartexpense.app.modelprofile.migration.LegacyModelProfileMigrator
import com.hugo.smartexpense.extraction.ApiKeyStore
import com.hugo.smartexpense.extraction.ModelProfile
import com.hugo.smartexpense.extraction.ModelProfileDraft
import com.hugo.smartexpense.extraction.ModelProfileField
import com.hugo.smartexpense.extraction.ModelProfileRepository
import com.hugo.smartexpense.extraction.ModelProfileSelectorState
import com.hugo.smartexpense.extraction.ModelProfileValidator
import com.hugo.smartexpense.extraction.ProviderTestResult
import com.hugo.smartexpense.extraction.WritableApiKeyStore
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ModelProfileEditorState(
    val draft: ModelProfileDraft,
    val originalDraft: ModelProfileDraft,
    val validationErrors: Map<ModelProfileField, String> = emptyMap(),
    val hasStoredCredential: Boolean = false,
    val testStatus: String? = null,
) {
    val hasUnsavedChanges: Boolean get() = draft != originalDraft
}

data class ModelProfilesUiState(
    val profiles: List<ModelProfile> = emptyList(),
    val selectorState: ModelProfileSelectorState = ModelProfileSelectorState(),
    val effectiveProviderSummary: String = "On-device Gemma will be used for the next extraction.",
    val editor: ModelProfileEditorState? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

fun interface ModelProfileTestService {
    suspend fun test(profile: ModelProfile, apiKeyOverride: String?): ProviderTestResult
}

class DefaultModelProfileTestService(
    private val credentialStore: ApiKeyStore,
    private val clientFactory: (ApiKeyStore) -> RemoteModelClientFactory,
) : ModelProfileTestService {
    override suspend fun test(profile: ModelProfile, apiKeyOverride: String?): ProviderTestResult = withContext(Dispatchers.IO) {
        val overlay = ApiKeyStore { alias ->
            if (alias == profile.credentialAlias && !apiKeyOverride.isNullOrBlank()) apiKeyOverride else credentialStore.get(alias)
        }
        clientFactory(overlay).create(profile).testConfiguration()
    }
}

class ModelProfilesViewModel(
    private val repository: ModelProfileRepository,
    private val credentialStore: WritableApiKeyStore,
    private val resolver: SelectedReceiptModelProviderResolver,
    private val testService: ModelProfileTestService,
    private val migrator: LegacyModelProfileMigrator? = null,
    private val validator: ModelProfileValidator = ModelProfileValidator(),
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val editor = MutableStateFlow<ModelProfileEditorState?>(null)
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    val uiState = combine(
        repository.observeProfiles(), repository.observeSelectorState(), editor, busy, message,
    ) { profiles, selectorState, currentEditor, isBusy, currentMessage ->
        val selected = profiles.firstOrNull { it.id == selectorState.selectedRemoteProfileId }
        val summary = if (selectorState.remoteProvidersEnabled && selected != null) {
            "${selected.displayName} (${selected.modelId}) will be used for the next extraction."
        } else {
            "On-device Gemma will be used for the next extraction."
        }
        ModelProfilesUiState(profiles, selectorState, summary, currentEditor, isBusy, currentMessage)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelProfilesUiState())

    init {
        migrator?.let { legacyMigrator ->
            viewModelScope.launch {
                runCatching { legacyMigrator.migrate() }
                    .onSuccess { result ->
                        if (result is LegacyMigrationResult.InvalidLegacyData) {
                            message.value = "The previous provider settings need review. ${result.reason}"
                        }
                    }
                    .onFailure { message.value = "The previous provider settings could not be migrated: ${it.safeMessage()}" }
            }
        }
    }

    fun addProfile() {
        val draft = ModelProfileDraft()
        editor.value = ModelProfileEditorState(draft, draft)
        message.value = null
    }

    fun editProfile(profile: ModelProfile) {
        val draft = ModelProfileDraft(
            id = profile.id,
            displayName = profile.displayName,
            baseUrl = profile.baseUrl,
            modelId = profile.modelId,
            inputMode = profile.inputMode,
            structuredOutputFormat = profile.structuredOutputFormat,
        )
        editor.value = ModelProfileEditorState(
            draft = draft,
            originalDraft = draft,
            hasStoredCredential = credentialStore.get(profile.credentialAlias) != null,
        )
        message.value = null
    }

    fun updateDraft(draft: ModelProfileDraft) {
        editor.value = editor.value?.copy(
            draft = draft,
            validationErrors = validator.validate(draft).errors,
            testStatus = null,
        )
    }

    fun cancelEdit() { editor.value = null }
    fun clearMessage() { message.value = null }

    fun saveProfile(selectAfterSave: Boolean = false) = launchOperation {
        val currentEditor = editor.value ?: return@launchOperation
        val validation = validator.validate(currentEditor.draft)
        if (!validation.isValid) {
            editor.value = currentEditor.copy(validationErrors = validation.errors)
            return@launchOperation
        }
        val id = currentEditor.draft.id ?: idFactory()
        val existing = repository.getProfile(id)
        val now = clock()
        val profile = ModelProfile(
            id = id,
            displayName = validation.normalizedDisplayName,
            baseUrl = validation.normalizedBaseUrl,
            modelId = validation.normalizedModelId,
            inputMode = currentEditor.draft.inputMode,
            structuredOutputFormat = currentEditor.draft.structuredOutputFormat,
            credentialAlias = existing?.credentialAlias ?: ModelProfile.credentialAlias(id),
            createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
            updatedAtEpochMillis = now,
        )
        if (currentEditor.draft.apiKey.isNotBlank()) {
            credentialStore.put(profile.credentialAlias, currentEditor.draft.apiKey)
        }
        repository.saveProfile(profile)
        if (selectAfterSave) {
            repository.setRemoteProvidersEnabled(true)
            repository.selectProfile(profile.id)
        }
        editor.value = null
        message.value = if (selectAfterSave) "Saved and selected ${profile.displayName}." else "Saved ${profile.displayName}."
    }

    fun testProfile() = launchOperation {
        val currentEditor = editor.value ?: return@launchOperation
        val validation = validator.validate(currentEditor.draft)
        if (!validation.isValid) {
            editor.value = currentEditor.copy(validationErrors = validation.errors)
            return@launchOperation
        }
        val id = currentEditor.draft.id ?: "unsaved-profile-test"
        val profile = ModelProfile(
            id, validation.normalizedDisplayName, validation.normalizedBaseUrl, validation.normalizedModelId,
            currentEditor.draft.inputMode, currentEditor.draft.structuredOutputFormat,
            currentEditor.draft.id?.let(ModelProfile::credentialAlias) ?: "remote-provider:unsaved-test",
            0, 0,
        )
        editor.value = currentEditor.copy(testStatus = "Testing contacts ${validation.normalizedBaseUrl}…")
        val result = testService.test(profile, currentEditor.draft.apiKey.takeIf(String::isNotBlank))
        editor.value = editor.value?.copy(
            testStatus = when (result) {
                ProviderTestResult.Success -> "Connected. The provider returned the expected response."
                is ProviderTestResult.Failed -> "Test failed: ${result.reason}"
            },
        )
    }

    fun clearCredential() = launchOperation {
        val currentEditor = editor.value ?: return@launchOperation
        val id = currentEditor.draft.id ?: return@launchOperation
        credentialStore.remove(ModelProfile.credentialAlias(id))
        editor.value = currentEditor.copy(hasStoredCredential = false, draft = currentEditor.draft.copy(apiKey = ""))
        message.value = "Credential cleared."
    }

    fun selectProfile(profile: ModelProfile) = launchOperation {
        if (!uiState.value.selectorState.remoteProvidersEnabled) {
            message.value = "Enable remote providers before selecting a remote profile."
            return@launchOperation
        }
        repository.selectProfile(profile.id)
        message.value = "Selected ${profile.displayName} for the next extraction."
    }

    fun setRemoteProvidersEnabled(enabled: Boolean) = launchOperation {
        repository.setRemoteProvidersEnabled(enabled)
        message.value = if (enabled) {
            "Remote providers are enabled. Select a saved profile to send receipt data remotely."
        } else {
            "Remote providers are disabled. The next extraction will stay on device."
        }
    }

    fun deleteProfile(profile: ModelProfile) = launchOperation {
        repository.deleteProfile(profile.id)
        runCatching { credentialStore.remove(profile.credentialAlias) }
            .onFailure { message.value = "${profile.displayName} was deleted, but its credential could not be cleaned up." }
            .onSuccess { message.value = "Deleted ${profile.displayName}. The local provider is used if it was selected." }
    }

    private fun launchOperation(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            try {
                block()
            } catch (error: Exception) {
                message.value = error.safeMessage()
            } finally {
                busy.value = false
            }
        }
    }

    private fun Throwable.safeMessage(): String = message?.take(240) ?: "The operation failed."

    class Factory(
        private val create: () -> ModelProfilesViewModel,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }
}
