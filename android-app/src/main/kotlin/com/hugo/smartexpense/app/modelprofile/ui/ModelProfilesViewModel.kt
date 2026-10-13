package com.hugo.smartexpense.app.modelprofile.ui

import androidx.lifecycle.ViewModel
import com.hugo.smartexpense.app.modelprofile.domain.AvailableProviderModel
import com.hugo.smartexpense.app.modelprofile.domain.ProviderModelCatalogService
import com.hugo.smartexpense.app.modelprofile.domain.ProviderModelCatalogResult
import com.hugo.smartexpense.app.modelprofile.domain.CompatibleProviderModelCatalogService
import com.hugo.smartexpense.app.modelprofile.domain.DuplicateModelProfileService
import com.hugo.smartexpense.app.modelprofile.domain.DuplicateModelProfileResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import com.hugo.smartexpense.app.connectivity.ModelsEndpointInput
import com.hugo.smartexpense.app.connectivity.modelsEndpointUrl
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hugo.smartexpense.app.modelprofile.domain.ProfileConfigurationCoordinator
import com.hugo.smartexpense.app.modelprofile.domain.ProviderConfigurationSnapshot
import com.hugo.smartexpense.app.modelprofile.domain.RemoteModelClientFactory
import com.hugo.smartexpense.app.modelprofile.domain.SelectedReceiptModelProviderResolver
import com.hugo.smartexpense.app.modelprofile.migration.LegacyMigrationResult
import com.hugo.smartexpense.app.modelprofile.migration.LegacyModelProfileMigrator
import com.hugo.smartexpense.app.modelprofile.transfer.ProviderConfigImportPreview
import com.hugo.smartexpense.app.modelprofile.transfer.ProviderConfigTransferService
import com.hugo.smartexpense.app.tailscale.TailscaleAccess
import com.hugo.smartexpense.app.tailscale.TailscaleAccessState
import com.hugo.smartexpense.app.tailscale.TailscaleController
import com.hugo.smartexpense.extraction.ApiKeyStore
import com.hugo.smartexpense.extraction.ModelProfile
import com.hugo.smartexpense.extraction.ApiProfileCategory
import com.hugo.smartexpense.extraction.ProviderCategory
import com.hugo.smartexpense.extraction.OperationCredentialSnapshot
import com.hugo.smartexpense.extraction.ApiEndpointValidator
import com.hugo.smartexpense.extraction.CredentialEdit
import com.hugo.smartexpense.extraction.RemoteInputMode
import com.hugo.smartexpense.extraction.RemoteStructuredOutputFormat
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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.coroutines.coroutineContext
import com.hugo.smartexpense.extraction.InferenceExecution

sealed interface ModelCatalogState {
    data object Idle : ModelCatalogState
    data class Loading(val endpoint: String) : ModelCatalogState
    data class Loaded(val models: List<AvailableProviderModel>) : ModelCatalogState
    data object Empty : ModelCatalogState
    data class Failed(val reason: String) : ModelCatalogState
    data object Cancelled : ModelCatalogState
}

data class ModelProfileEditorState(
    val draft: ModelProfileDraft,
    val originalDraft: ModelProfileDraft,
    val validationErrors: Map<ModelProfileField, String> = emptyMap(),
    val hasStoredCredential: Boolean = false,
    val testStatus: String? = null,
    val catalogState: ModelCatalogState = ModelCatalogState.Idle,
    val catalogEndpoint: String? = null,
    val modelPickerOpen: Boolean = false,
) {
    val hasUnsavedChanges: Boolean get() = draft != originalDraft
}

data class ModelProfilesUiState(
    val profiles: List<ModelProfile> = emptyList(),
    val selectorState: ModelProfileSelectorState = ModelProfileSelectorState(),
    val effectiveProviderSummary: String = "On-device Gemma will be used for the next extraction.",
    val editor: ModelProfileEditorState? = null,
    val importPreview: ProviderConfigImportPreview? = null,
    val verification: ProviderVerificationState? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val tailscale: TailscaleAccessState? = null,
)

data class ProviderVerificationState(
    val providerId: String,
    val busy: Boolean,
    val message: String,
)

fun interface ModelProfileTestService {
    /** null reads saved storage; empty explicitly suppresses any saved credential. */
    suspend fun test(profile: ModelProfile, apiKeyOverride: String?): ProviderTestResult
}

class DefaultModelProfileTestService(
    private val credentialStore: ApiKeyStore,
    private val deadlineMillis: Long = 180_000,
    private val startSpacingMillis: Long = 3_500,
    private val clientFactory: (ApiKeyStore) -> RemoteModelClientFactory,
) : ModelProfileTestService {
    override suspend fun test(profile: ModelProfile, apiKeyOverride: String?): ProviderTestResult = try { withTimeout(deadlineMillis) {
        val credential = if (apiKeyOverride == null) credentialStore.get(profile.credentialAlias) else apiKeyOverride.takeIf(String::isNotBlank)
        val overlay = ApiKeyStore { alias -> if (alias == profile.credentialAlias) credential else null }
        val scope = InferenceExecution.Scope(coroutineContext.job, InferenceExecution.sharedGate,
            if (profile.category == ApiProfileCategory.SAME_PHONE_SERVER) 0 else startSpacingMillis,
            credentials = OperationCredentialSnapshot(mapOf(profile.credentialAlias to credential)),
            category = if (profile.category == ApiProfileCategory.SAME_PHONE_SERVER) ProviderCategory.SAME_PHONE_SERVER else ProviderCategory.REMOTE_API,
            explicitRecovery = true)
        withContext(Dispatchers.IO + InferenceExecution.current.asContextElement(scope)) {
            clientFactory(overlay).create(profile).testConfiguration()
        }
    } } catch (timeout: TimeoutCancellationException) {
        coroutineContext.ensureActive()
        ProviderTestResult.Failed("Provider verification timed out. Retry when the provider is ready.")
    }
}

class ModelProfilesViewModel(
    private val repository: ModelProfileRepository,
    private val credentialStore: WritableApiKeyStore,
    private val resolver: SelectedReceiptModelProviderResolver,
    private val testService: ModelProfileTestService,
    private val migrator: LegacyModelProfileMigrator? = null,
    private val transferService: ProviderConfigTransferService = ProviderConfigTransferService(repository),
    private val validator: ModelProfileValidator = ModelProfileValidator(),
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
    tailscaleController: TailscaleController = TailscaleController { _, _ ->
        throw com.hugo.smartexpense.app.tailscale.TailscaleUnavailableException("Tailscale unavailable. Open Tailscale to check setup.")
    },
    tailscaleProbe: ModelProfileTestService = testService,
    vpnStatusSource: com.hugo.smartexpense.app.connectivity.VpnStatusSource = com.hugo.smartexpense.app.connectivity.UnknownVpnStatusSource,
    private val discoveryDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    private val catalogService: ProviderModelCatalogService = CompatibleProviderModelCatalogService(),
    private val duplicateService: DuplicateModelProfileService = DuplicateModelProfileService(repository, credentialStore, idFactory, clock),
    private val configurationCoordinator: ProfileConfigurationCoordinator = ProfileConfigurationCoordinator(repository, credentialStore),
) : ViewModel() {
    private val tailscaleAccess = TailscaleAccess(repository, resolver, tailscaleController, tailscaleProbe, viewModelScope,
        vpnStatusSource = vpnStatusSource)
    fun requestTailscale(connect: Boolean) = tailscaleAccess.request(connect)
    fun resetTailscaleStatus() = tailscaleAccess.resetStatus()
    fun setConnectivityMonitoring(active: Boolean) {
        tailscaleAccess.setMonitoring(active)
        if (!active) cancelModelDiscovery()
    }
    private var discoveryJob: Job? = null
    private var verificationJob: Job? = null
    private var verificationCategory: ApiProfileCategory? = null
    private var verificationGeneration = 0L
    private var discoveryToken = 0L
    private var editorGeneration = 0L
    private var credentialGeneration = 0L
    private val editor = MutableStateFlow<ModelProfileEditorState?>(null)
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val importPreview = MutableStateFlow<ProviderConfigImportPreview?>(null)
    private val verification = MutableStateFlow<ProviderVerificationState?>(null)

    private data class TransientState(
        val editor: ModelProfileEditorState?,
        val busy: Boolean,
        val message: String?,
        val importPreview: ProviderConfigImportPreview?,
        val verification: ProviderVerificationState?,
    )

    private val transientState = combine(editor, busy, message, importPreview, verification) {
            currentEditor, isBusy, currentMessage, currentImportPreview, currentVerification ->
        TransientState(currentEditor, isBusy, currentMessage, currentImportPreview, currentVerification)
    }

    val uiState = combine(
        repository.observeProfiles(), repository.observeSelectorState(), transientState,
    ) { profiles, selectorState, transient ->
        val resolved = resolver.resolve(profiles, selectorState)
        val summary = resolved.remoteProfile?.let {
            "${it.displayName} (${it.modelId}) will be used for the next extraction."
        } ?: "On-device Gemma will be used for the next extraction."
        ModelProfilesUiState(
            profiles = profiles,
            selectorState = selectorState,
            effectiveProviderSummary = summary,
            editor = transient.editor,
            importPreview = transient.importPreview,
            verification = transient.verification,
            busy = transient.busy,
            message = transient.message,
        )
    }.combine(tailscaleAccess.state) { state, tailscale ->
        val eligible = resolver.resolve(state.profiles, state.selectorState).tailscaleProfile
        state.copy(tailscale = tailscale?.takeIf { it.profile == eligible })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelProfilesUiState())

    init {
        viewModelScope.launch {
            try { configurationCoordinator.recover() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                message.value = "Credential cleanup needs recovery. ${error.safeMessage()}"
                return@launch
            }
            migrator?.let { legacyMigrator ->
                runCatching { configurationCoordinator.coordinated { legacyMigrator.migrate() } }
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
        if (busy.value) return
        invalidateDiscovery()
        verificationGeneration++
        verificationJob?.cancel()
        verification.value = null
        editorGeneration++
        credentialGeneration++
        val draft = ModelProfileDraft()
        editor.value = ModelProfileEditorState(draft, draft)
        message.value = null
    }

    fun editProfile(profile: ModelProfile) {
        if (busy.value) return
        invalidateDiscovery()
        verificationGeneration++
        verificationJob?.cancel()
        verification.value = null
        editorGeneration++
        credentialGeneration++
        val draft = ModelProfileDraft(
            id = profile.id,
            displayName = profile.displayName,
            baseUrl = profile.baseUrl,
            modelId = profile.modelId,
            inputMode = profile.inputMode,
            structuredOutputFormat = profile.structuredOutputFormat,
            showTailscaleToggle = profile.showTailscaleToggle,
            category = profile.category,
        )
        val hasCredential = try { credentialStore.get(profile.credentialAlias) != null } catch (_: Exception) {
            message.value = "The saved credential could not be read. Retry credential recovery before editing."
            return
        }
        editor.value = ModelProfileEditorState(
            draft = draft,
            originalDraft = draft,
            hasStoredCredential = hasCredential,
        )
        message.value = null
    }

    fun updateDraft(draft: ModelProfileDraft) {
        if (busy.value) return
        val previous = editor.value?.draft ?: return
        if (draft != previous) {
            credentialGeneration++
            verificationGeneration++
            verificationJob?.cancel()
            verification.value = null
            editorGeneration++
            if (draft.id != previous.id) editorGeneration++
            invalidateDiscovery()
        }
        editor.value = editor.value?.copy(
            draft = draft,
            validationErrors = validator.validate(draft).errors,
            testStatus = null,
        )
    }

    fun cancelEdit() {
        if (busy.value) return
        invalidateDiscovery()
        verificationGeneration++
        verificationJob?.cancel()
        verification.value = null
        editorGeneration++
        editor.value = null
    }

    /** Called when Settings is stopped or removed; unsaved edits remain intact. */
    fun cancelModelDiscovery() {
        invalidateDiscovery(cancelled = true)
        editorGeneration++
        verificationGeneration++
        verificationJob?.cancel()
        verification.value = null
        editor.value = editor.value?.copy(testStatus = null)
    }

    private fun invalidateDiscovery(cancelled: Boolean = false) {
        val previousState = editor.value?.catalogState
        val showCancelled = cancelled && (previousState is ModelCatalogState.Loading || previousState is ModelCatalogState.Cancelled)
        discoveryToken++
        discoveryJob?.cancel()
        discoveryJob = null
        editor.value = editor.value?.copy(
            catalogState = if (showCancelled) ModelCatalogState.Cancelled else ModelCatalogState.Idle,
            catalogEndpoint = null,
            modelPickerOpen = false,
        )
    }

    fun loadModels() {
        if (busy.value) return
        val current = editor.value ?: return
        invalidateDiscovery()
        val endpoint = modelsEndpointUrl(ModelsEndpointInput(current.draft.baseUrl, current.draft.apiKey, category = current.draft.category))
        if (endpoint == null || current.draft.apiKey.any { it == '\r' || it == '\n' }) {
            editor.value = editor.value?.copy(catalogState = ModelCatalogState.Failed(
                if (current.draft.category == ApiProfileCategory.SAME_PHONE_SERVER) "Use http://127.0.0.1:<port>/<API path>, matching the companion, and a header-safe optional key."
                else "Enter an HTTP(S) base URL without credentials, query or fragment, and a valid API key."))
            return
        }
        val requestToken = discoveryToken
        val generation = editorGeneration
        val keyGeneration = credentialGeneration
        val draft = current.draft
        editor.value = editor.value?.copy(catalogState = ModelCatalogState.Loading(endpoint), catalogEndpoint = endpoint)
        discoveryJob = viewModelScope.launch {
            val result = try {
                val snapshot = withContext(discoveryDispatcher) { captureEditor(draft, discovery = true) }
                catalogService.load(snapshot.profile.baseUrl, snapshot.apiKey, snapshot.profile.category)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ProviderModelCatalogResult.Failed("Models could not be loaded. Check the endpoint and stored credential, or enter a model ID manually.")
            }
            if (requestToken != discoveryToken || generation != editorGeneration || keyGeneration != credentialGeneration) return@launch
            val state = when (result) {
                is ProviderModelCatalogResult.Loaded -> {
                    val models = result.models.distinctBy { it.id }.sortedBy { it.id }
                    if (models.isEmpty()) ModelCatalogState.Empty else ModelCatalogState.Loaded(models)
                }
                is ProviderModelCatalogResult.Failed -> ModelCatalogState.Failed(result.reason)
            }
            editor.value = editor.value?.copy(catalogState = state, modelPickerOpen = state is ModelCatalogState.Loaded)
            discoveryJob = null
        }
    }

    fun selectCatalogModel(id: String) {
        if (busy.value) return
        val current = editor.value ?: return
        val models = (current.catalogState as? ModelCatalogState.Loaded)?.models ?: return
        if (models.none { it.id == id }) return
        val draft = current.draft.copy(modelId = id)
        updateDraft(draft)
    }

    fun dismissModelPicker() { editor.value = editor.value?.copy(modelPickerOpen = false) }
    fun openModelPicker() { editor.value = editor.value?.copy(modelPickerOpen = true) }

    fun duplicateProfile(sourceId: String) = launchOperation {
        if (editor.value != null) return@launchOperation
        when (val result = duplicateService.duplicate(sourceId)) {
            is DuplicateModelProfileResult.Success -> message.value = "Duplicated ${result.sourceName} as ${result.copy.displayName}."
            is DuplicateModelProfileResult.Failure -> message.value = result.message
        }
    }
    fun clearMessage() { message.value = null }

    fun createProviderConfigExportJson(): String = transferService.createExportJson(
        profiles = uiState.value.profiles,
        selectorState = uiState.value.selectorState,
    )

    fun loadProviderConfigImport(json: String) = launchOperation {
        importPreview.value = transferService.previewImport(json)
        message.value = null
    }

    fun cancelProviderConfigImport() {
        importPreview.value = null
    }

    fun confirmProviderConfigImport() = launchOperation {
        val preview = importPreview.value ?: return@launchOperation
        val result = transferService.confirmImport(preview)
        importPreview.value = null
        message.value = buildString {
            append("Imported ${result.importedCount} provider profile")
            if (result.importedCount != 1) append('s')
            append(" without credentials. Your current provider selection was kept.")
            if (result.copiedConflictCount > 0) {
                append(" ${result.copiedConflictCount} existing ID conflict")
                if (result.copiedConflictCount != 1) append('s')
                append(if (result.copiedConflictCount == 1) " was saved as a new copy." else " were saved as new copies.")
            }
        }
    }

    fun providerConfigExportCompleted(profileCount: Int) {
        message.value = "Exported $profileCount provider profile${if (profileCount == 1) "" else "s"} without credentials."
    }

    fun reportProviderConfigFailure(error: Throwable) {
        message.value = error.safeMessage()
    }

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
            showTailscaleToggle = currentEditor.draft.showTailscaleToggle,
            category = currentEditor.draft.category,
        )
        configurationCoordinator.save(profile, effectiveCredentialEdit(currentEditor.draft))
        editor.value = null
        message.value = "Saved ${profile.displayName}."
    }

    fun testProfile() {
        val currentEditor = editor.value ?: return
        if (busy.value) return
        val validation = validator.validate(currentEditor.draft)
        if (!validation.isValid) {
            editor.value = currentEditor.copy(validationErrors = validation.errors)
            return
        }
        verificationGeneration++
        verificationJob?.cancel()
        val generation = ++editorGeneration
        val verificationToken = verificationGeneration
        val draft = currentEditor.draft
        verificationCategory = draft.category
        editor.value = currentEditor.copy(testStatus = "Testing contacts ${validation.normalizedBaseUrl}…")
        verificationJob = viewModelScope.launch {
            val result = try {
                val snapshot = captureEditor(draft)
                testService.test(snapshot.profile, snapshot.apiKey ?: "")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ProviderTestResult.Failed("Check the profile configuration and credential, then retry.") }
            if (verificationToken != verificationGeneration || generation != editorGeneration || editor.value?.draft != draft) return@launch
            editor.value = editor.value?.copy(testStatus = when (result) {
                ProviderTestResult.Success -> "Connected. The provider returned the expected response."
                is ProviderTestResult.Failed -> "Test failed: ${result.reason}"
            })
        }
    }

    private fun effectiveCredentialEdit(draft: ModelProfileDraft): CredentialEdit =
        if (draft.apiKey.isNotBlank()) CredentialEdit.Replace(draft.apiKey) else draft.credentialEdit

    private suspend fun captureEditor(draft: ModelProfileDraft, discovery: Boolean = false): ProviderConfigurationSnapshot {
        val saved = draft.id?.let { repository.getProfile(it) }
        val profile = ModelProfile(
            id = draft.id ?: "unsaved-profile-test",
            displayName = draft.displayName.ifBlank { if (discovery) "Discovery" else "" },
            baseUrl = ApiEndpointValidator.normalize(draft.baseUrl, draft.category),
            modelId = draft.modelId.ifBlank { if (discovery) "manual-model" else "" },
            inputMode = draft.inputMode, structuredOutputFormat = draft.structuredOutputFormat,
            credentialAlias = saved?.credentialAlias ?: "remote-provider:unsaved-test",
            createdAtEpochMillis = 0, updatedAtEpochMillis = 0,
            showTailscaleToggle = draft.showTailscaleToggle, category = draft.category,
        )
        return configurationCoordinator.editorSnapshot(profile, effectiveCredentialEdit(draft))
    }

    fun recoverCredentials() = launchOperation {
        configurationCoordinator.recover()
        message.value = "Credential cleanup completed."
    }

    fun clearCredential() {
        if (busy.value) return
        val current = editor.value ?: return
        updateDraft(current.draft.copy(apiKey = "", credentialEdit = CredentialEdit.Clear))
    }

    fun keepCredential() {
        val current = editor.value ?: return
        updateDraft(current.draft.copy(apiKey = "", credentialEdit = CredentialEdit.Keep))
    }

    fun updateApiKey(value: String) {
        val current = editor.value ?: return
        val intent = if (value.isBlank()) CredentialEdit.Keep else runCatching { CredentialEdit.Replace(value) }.getOrNull() ?: return
        updateDraft(current.draft.copy(apiKey = value, credentialEdit = intent))
    }

    fun changeCategory(category: ApiProfileCategory) {
        val current = editor.value ?: return
        updateDraft(current.draft.copy(category = category,
            baseUrl = if (category == ApiProfileCategory.SAME_PHONE_SERVER) ApiEndpointValidator.SAME_PHONE_DEFAULT else current.draft.baseUrl,
            inputMode = RemoteInputMode.DIRECT_IMAGE,
            structuredOutputFormat = if (category == ApiProfileCategory.SAME_PHONE_SERVER) RemoteStructuredOutputFormat.PROMPT_ONLY_JSON else RemoteStructuredOutputFormat.JSON_SCHEMA,
            showTailscaleToggle = false))
    }

    fun selectProfile(profile: ModelProfile) = launchOperation {
        configurationCoordinator.coordinated {
            val saved = repository.getProfile(profile.id) ?: error("The profile no longer exists. Refresh Settings.")
            val selector = repository.getSelectorState()
            if (saved.category == ApiProfileCategory.REMOTE_API && !selector.remoteProvidersEnabled) {
                message.value = "Enable remote providers before selecting a remote profile."
            } else {
                repository.selectProfile(saved.id)
                message.value = "Selected ${saved.displayName} for the next extraction."
            }
        }
    }

    fun selectLocalProvider() = launchOperation {
        configurationCoordinator.coordinated { repository.selectProfile(null) }
        message.value = "Selected On-device Gemma for the next extraction."
    }

    fun verifySelectedProvider(localReadinessService: LocalModelReadinessService) {
        if (verification.value?.busy == true || busy.value) return
        val state = uiState.value
        val selected = state.profiles.firstOrNull {
            (it.category == ApiProfileCategory.SAME_PHONE_SERVER || state.selectorState.remoteProvidersEnabled) && it.id == state.selectorState.selectedRemoteProfileId
        }
        val providerId = selected?.id ?: SelectedReceiptModelProviderResolver.BUILT_IN_LOCAL_PROVIDER.id
        val verificationToken = verificationGeneration
        verificationCategory = selected?.category
        verification.value = ProviderVerificationState(providerId, true, "Checking provider readinessâ€¦")
        verificationJob = viewModelScope.launch {
            var capturedProviderId = providerId
            val resultMessage = runCatching {
                val snapshot = configurationCoordinator.selectedSnapshot().configuration
                verificationCategory = snapshot?.profile?.category
                capturedProviderId = snapshot?.profile?.id ?: SelectedReceiptModelProviderResolver.BUILT_IN_LOCAL_PROVIDER.id
                if (snapshot == null) {
                    when (val result = localReadinessService.check()) {
                        is LocalModelReadinessResult.Ready -> "Local model is ready (${result.sizeBytes / (1024 * 1024)} MB installed)."
                        is LocalModelReadinessResult.NotReady -> result.reason
                    }
                } else {
                    when (val result = testService.test(snapshot.profile, snapshot.apiKey ?: "")) {
                        ProviderTestResult.Success -> "Connected. The saved provider returned the expected response."
                        is ProviderTestResult.Failed -> "Verification failed: ${result.reason}"
                    }
                }
            }.getOrElse {
                if (it is CancellationException) throw it
                it.safeMessage()
            }
            if (verificationToken != verificationGeneration) return@launch
            verification.value = ProviderVerificationState(capturedProviderId, false, resultMessage)
        }
    }

    fun setRemoteProvidersEnabled(enabled: Boolean) = launchOperation(cancelInference = false) {
        if (!enabled) {
            if (editor.value?.draft?.category == ApiProfileCategory.REMOTE_API) invalidateDiscovery()
            if (verificationCategory == ApiProfileCategory.REMOTE_API) {
                verificationGeneration++
                verificationJob?.cancel()
                verification.value = null
                editorGeneration++
                editor.value = editor.value?.copy(testStatus = null)
            }
        }
        configurationCoordinator.coordinated { repository.setRemoteProvidersEnabled(enabled) }
        message.value = if (enabled) {
            "Remote providers are enabled. Select a saved profile to send receipt data remotely."
        } else {
            "Remote API providers are disabled. Built-in and Same-phone server selection remain available."
        }
    }

    fun deleteProfile(profile: ModelProfile) = launchOperation {
        configurationCoordinator.delete(profile.id)
        message.value = "Deleted ${profile.displayName}. The built-in provider is used if it was selected."
    }

    private fun launchOperation(cancelInference: Boolean = true, block: suspend () -> Unit) {
        if (busy.value) return
        if (cancelInference) {
            invalidateDiscovery()
            verificationGeneration++
            verificationJob?.cancel()
            verification.value = null
        }
        busy.value = true
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message.value = error.safeMessage()
            } finally {
                busy.value = false
            }
        }
    }

    private fun Throwable.safeMessage(): String {
        val text = message ?: "The operation failed."
        val secret = editor.value?.draft?.apiKey?.takeIf(String::isNotBlank)
        return (if (secret != null) text.replace(secret, "[redacted]") else text).take(240)
    }

    class Factory(
        private val create: () -> ModelProfilesViewModel,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }
}
