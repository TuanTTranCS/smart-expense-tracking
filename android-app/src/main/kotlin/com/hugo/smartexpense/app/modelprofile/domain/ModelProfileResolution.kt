package com.hugo.smartexpense.app.modelprofile.domain

import com.hugo.smartexpense.app.AndroidOpenAiCompatibleApiTransport
import com.hugo.smartexpense.extraction.ApiKeyStore
import com.hugo.smartexpense.extraction.LocalModelProviderOption
import com.hugo.smartexpense.extraction.ModelProfile
import com.hugo.smartexpense.extraction.ModelProfileRepository
import com.hugo.smartexpense.extraction.ModelProviderType
import com.hugo.smartexpense.extraction.ModelSelector
import com.hugo.smartexpense.extraction.ModelSelectorSettings
import com.hugo.smartexpense.extraction.OpenAiCompatibleApiTransport
import com.hugo.smartexpense.extraction.OpenAiCompatibleReceiptModelClient
import com.hugo.smartexpense.extraction.SelectedModelProvider
import kotlinx.coroutines.flow.first

data class ResolvedReceiptModelProvider(
    val selectedProvider: SelectedModelProvider,
    val remoteProfile: ModelProfile?,
)

class SelectedReceiptModelProviderResolver(
    private val repository: ModelProfileRepository,
    private val selector: ModelSelector = ModelSelector(),
    private val localProvider: LocalModelProviderOption = BUILT_IN_LOCAL_PROVIDER,
) {
    suspend fun resolve(): ResolvedReceiptModelProvider {
        val profiles = repository.observeProfiles().first()
        val state = repository.observeSelectorState().first()
        val selected = selector.select(
            ModelSelectorSettings(
                localProvider = localProvider,
                openAiCompatibleProviders = profiles.map(ModelProfile::asProviderOption),
                selectedProviderId = state.selectedRemoteProfileId,
                remoteProvidersEnabled = state.remoteProvidersEnabled,
            ),
        )
        return ResolvedReceiptModelProvider(
            selectedProvider = selected,
            remoteProfile = profiles.firstOrNull { it.id == selected.id }
                ?.takeIf { selected.type == ModelProviderType.OPENAI_COMPATIBLE_API },
        )
    }

    companion object {
        val BUILT_IN_LOCAL_PROVIDER = LocalModelProviderOption(
            id = "local-gemma-4-e2b",
            displayName = "On-device Gemma",
            modelId = "gemma-4-e2b-it-int4",
            supportsDirectImageInput = true,
        )
    }
}

class RemoteModelClientFactory(
    private val apiKeyStore: ApiKeyStore,
    private val transport: OpenAiCompatibleApiTransport = AndroidOpenAiCompatibleApiTransport(),
) {
    fun create(profile: ModelProfile): OpenAiCompatibleReceiptModelClient =
        OpenAiCompatibleReceiptModelClient(profile.asProviderOption(), apiKeyStore, transport)
}
