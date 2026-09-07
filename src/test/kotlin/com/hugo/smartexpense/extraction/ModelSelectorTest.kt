package com.hugo.smartexpense.extraction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelSelectorTest {
    private val localProvider = LocalModelProviderOption(
        id = "local-gemma",
        displayName = "Gemma 4 E2B",
        modelId = "gemma-4-e2b-it",
        supportsDirectImageInput = true,
    )

    @Test
    fun defaultsToLocalProviderWhenRemoteProvidersAreDisabled() {
        val settings = ModelSelectorSettings(
            localProvider = localProvider,
            openAiCompatibleProviders = listOf(remoteProvider()),
            selectedProviderId = "remote-openai",
            remoteProvidersEnabled = false,
        )

        val selected = ModelSelector().select(settings)

        assertEquals(ModelProviderType.LOCAL_ON_DEVICE, selected.type)
        assertEquals("local-gemma", selected.id)
        assertNull(selected.baseUrl)
        assertTrue(selected.supportsDirectImageInput)
    }

    @Test
    fun selectsConfiguredRemoteProviderWhenOptedIn() {
        val settings = ModelSelectorSettings(
            localProvider = localProvider,
            openAiCompatibleProviders = listOf(remoteProvider(baseUrl = "https://api.example.com/")),
            selectedProviderId = "remote-openai",
            remoteProvidersEnabled = true,
        )

        val selected = ModelSelector().select(settings)

        assertEquals(ModelProviderType.OPENAI_COMPATIBLE_API, selected.type)
        assertEquals("remote-openai", selected.id)
        assertEquals("https://api.example.com", selected.baseUrl)
        assertFalse(selected.supportsDirectImageInput)
    }

    @Test
    fun fallsBackToLocalProviderWhenSelectedRemoteProviderIsMissing() {
        val settings = ModelSelectorSettings(
            localProvider = localProvider,
            selectedProviderId = "missing",
            remoteProvidersEnabled = true,
        )

        val selected = ModelSelector().select(settings)

        assertEquals(ModelProviderType.LOCAL_ON_DEVICE, selected.type)
        assertEquals(localProvider.modelId, selected.modelId)
    }

    private fun remoteProvider(baseUrl: String = "https://api.example.com"): OpenAiCompatibleProviderOption =
        OpenAiCompatibleProviderOption(
            id = "remote-openai",
            displayName = "OpenAI Compatible",
            baseUrl = baseUrl,
            modelId = "gpt-4.1-mini",
            inputMode = RemoteInputMode.OCR_TEXT,
            apiKeyAlias = "expense-openai",
        )
}
