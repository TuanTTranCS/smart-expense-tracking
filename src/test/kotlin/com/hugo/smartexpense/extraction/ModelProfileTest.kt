package com.hugo.smartexpense.extraction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelProfileTest {
    @Test fun tailscaleDefaultsOffAndCopiesAndProviderSelectionPreservePermission() {
        val profile = ModelProfile("remote", "Remote", "https://example.test/v1", "model",
            RemoteInputMode.DIRECT_IMAGE, RemoteStructuredOutputFormat.JSON_SCHEMA, "alias", 1, 1)
        assertFalse(profile.showTailscaleToggle)
        assertFalse(ModelProfileDraft().showTailscaleToggle)
        val optedIn = profile.copy(showTailscaleToggle = true)
        assertTrue(optedIn.copy(displayName = "Renamed").showTailscaleToggle)
        assertTrue(optedIn.asProviderOption().showTailscaleToggle)
        val settings = ModelSelectorSettings(
            LocalModelProviderOption("local", "Local", "local-model", true),
            listOf(optedIn.asProviderOption()), "remote", true,
        )
        assertTrue(ModelSelector().select(settings).showTailscaleToggle)
        assertFalse(ModelSelector().select(settings.copy(remoteProvidersEnabled = false)).showTailscaleToggle)
        assertFalse(ModelSelector().select(settings.copy(selectedProviderId = null)).showTailscaleToggle)
        assertFalse(ModelSelector().select(settings.copy(selectedProviderId = "missing")).showTailscaleToggle)
    }
    private val validator = ModelProfileValidator()

    @Test
    fun normalizesValidFields() {
        val result = validator.validate(
            ModelProfileDraft(displayName = "  Home server ", baseUrl = " https://example.test/v1/ ", modelId = " model-a "),
        )
        assertTrue(result.isValid)
        assertEquals("Home server", result.normalizedDisplayName)
        assertEquals("https://example.test/v1", result.normalizedBaseUrl)
        assertEquals("model-a", result.normalizedModelId)
    }

    @Test
    fun rejectsMissingFieldsAndNonHttpOrRelativeUrls() {
        val missing = validator.validate(ModelProfileDraft())
        assertFalse(missing.isValid)
        assertEquals(3, missing.errors.size)
        assertFalse(validator.validate(ModelProfileDraft("", "Name", "example.test/v1", "model")).isValid)
        assertFalse(validator.validate(ModelProfileDraft("", "Name", "file://example/model", "model")).isValid)
    }

    @Test
    fun providerConversionPreservesStableIdentityAndAlias() {
        val profile = ModelProfile(
            id = "profile-1", displayName = "Provider", baseUrl = "https://example.test/v1",
            modelId = "model", inputMode = RemoteInputMode.OCR_TEXT,
            structuredOutputFormat = RemoteStructuredOutputFormat.JSON_OBJECT,
            credentialAlias = ModelProfile.credentialAlias("profile-1"), createdAtEpochMillis = 1, updatedAtEpochMillis = 2,
        )
        val provider = profile.asProviderOption()
        assertEquals("profile-1", provider.id)
        assertEquals("remote-provider:profile-1", provider.apiKeyAlias)
        assertEquals(RemoteInputMode.OCR_TEXT, provider.inputMode)
    }
}
