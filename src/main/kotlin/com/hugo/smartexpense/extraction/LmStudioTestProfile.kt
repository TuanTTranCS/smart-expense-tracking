package com.hugo.smartexpense.extraction

object LmStudioTestProfile {
    const val providerId = "lm-studio-gemma"
    const val displayName = "LM Studio (Gemma 4 E2B)"
    const val baseUrl = "http://10.0.0.207:1234/v1"
    const val modelId = "google/gemma-4-e2b"
    const val apiKeyAlias = "lm-studio-api-key"
    const val placeholderApiKey = "lm-studio"

    fun provider(inputMode: RemoteInputMode = RemoteInputMode.DIRECT_IMAGE): OpenAiCompatibleProviderOption =
        OpenAiCompatibleProviderOption(
            id = providerId,
            displayName = displayName,
            baseUrl = baseUrl,
            modelId = modelId,
            inputMode = inputMode,
            apiKeyAlias = apiKeyAlias,
            structuredOutputFormat = RemoteStructuredOutputFormat.JSON_SCHEMA,
        )
}
