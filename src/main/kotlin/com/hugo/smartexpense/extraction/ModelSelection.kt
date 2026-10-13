package com.hugo.smartexpense.extraction

enum class ModelProviderType {
    LOCAL_ON_DEVICE,
    OPENAI_COMPATIBLE_API,
}

enum class ApiProfileCategory { REMOTE_API, SAME_PHONE_SERVER }
enum class ProviderCategory { BUILT_IN_ON_DEVICE, REMOTE_API, SAME_PHONE_SERVER }

enum class RemoteInputMode {
    DIRECT_IMAGE,
    OCR_TEXT,
}

enum class RemoteStructuredOutputFormat {
    JSON_OBJECT,
    JSON_SCHEMA,
    PROMPT_ONLY_JSON,
}

data class LocalModelProviderOption(
    val id: String,
    val displayName: String,
    val modelId: String,
    val supportsDirectImageInput: Boolean,
)

data class OpenAiCompatibleProviderOption(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val modelId: String,
    val inputMode: RemoteInputMode,
    val apiKeyAlias: String,
    val structuredOutputFormat: RemoteStructuredOutputFormat = RemoteStructuredOutputFormat.JSON_OBJECT,
    val showTailscaleToggle: Boolean = false,
    val category: ApiProfileCategory = ApiProfileCategory.REMOTE_API,
    val configurationError: String? = null,
)

data class ModelSelectorSettings(
    val localProvider: LocalModelProviderOption,
    val openAiCompatibleProviders: List<OpenAiCompatibleProviderOption> = emptyList(),
    val selectedProviderId: String? = null,
    val remoteProvidersEnabled: Boolean = false,
)

data class SelectedModelProvider(
    val id: String,
    val type: ModelProviderType,
    val displayName: String,
    val modelId: String,
    val supportsDirectImageInput: Boolean,
    val baseUrl: String? = null,
    val showTailscaleToggle: Boolean = false,
    val category: ProviderCategory = if (type == ModelProviderType.LOCAL_ON_DEVICE)
        ProviderCategory.BUILT_IN_ON_DEVICE else ProviderCategory.REMOTE_API,
)

class ModelSelector {
    fun select(settings: ModelSelectorSettings): SelectedModelProvider {
        val requestedProviderId = settings.selectedProviderId
        val selectedRemoteProvider = settings.openAiCompatibleProviders.firstOrNull { it.id == requestedProviderId }

        if (selectedRemoteProvider != null && selectedRemoteProvider.configurationError == null && (settings.remoteProvidersEnabled ||
                selectedRemoteProvider.category == ApiProfileCategory.SAME_PHONE_SERVER)) {
            return SelectedModelProvider(
                id = selectedRemoteProvider.id,
                type = ModelProviderType.OPENAI_COMPATIBLE_API,
                displayName = selectedRemoteProvider.displayName,
                modelId = selectedRemoteProvider.modelId,
                supportsDirectImageInput = selectedRemoteProvider.inputMode == RemoteInputMode.DIRECT_IMAGE,
                baseUrl = normalizeBaseUrl(selectedRemoteProvider.baseUrl),
                showTailscaleToggle = selectedRemoteProvider.category == ApiProfileCategory.REMOTE_API && selectedRemoteProvider.showTailscaleToggle,
                category = ProviderCategory.valueOf(selectedRemoteProvider.category.name),
            )
        }

        return SelectedModelProvider(
            id = settings.localProvider.id,
            type = ModelProviderType.LOCAL_ON_DEVICE,
            displayName = settings.localProvider.displayName,
            modelId = settings.localProvider.modelId,
            supportsDirectImageInput = settings.localProvider.supportsDirectImageInput,
        )
    }

    private fun normalizeBaseUrl(baseUrl: String): String = baseUrl.trim().trimEnd('/')
}
