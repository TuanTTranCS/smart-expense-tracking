package com.hugo.smartexpense.extraction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelProfileTest {
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
