package com.hugo.smartexpense.extraction

import kotlin.test.*

class ApiEndpointValidatorTest {
    @Test fun supportsDefaultCustomAndAsciiDotSegments() {
        listOf("http://127.0.0.1:8080/v1", "http://127.0.0.1:8091/expense/v1", "http://127.0.0.1:1/api.v1/").forEach {
            assertEquals(it.trimEnd('/'), ApiEndpointValidator.normalize(it, ApiProfileCategory.SAME_PHONE_SERVER))
        }
    }
    @Test fun rejectsUnsafeLexicalEndpoints() {
        listOf("https://127.0.0.1:8080/v1", "http://localhost:8080/v1", "http://127.1:8080/v1",
            "http://[::1]:8080/v1", "http://127.0.0.1/v1", "http://127.0.0.1:0/v1",
            "http://127.0.0.1:65536/v1", "http://127.0.0.1:8080/", "http://127.0.0.1:8080",
            "http://user@127.0.0.1:8080/v1", "http://127.0.0.1:8080/v1?key=x",
            "http://127.0.0.1:8080/v1#fragment", "http://127.0.0.1:8080/a/../v1",
            "http://127.0.0.1:8080/a/./v1", "http://127.0.0.1:8080/a//v1",
            "http://127.0.0.1:8080/%76%31", "http://127.0.0.1:8080/a\\v1",
            "http://127.0.0.1:8080/v1//", "http://10.0.0.1:8080/v1").forEach {
            assertFailsWith<IllegalArgumentException>(it) { ApiEndpointValidator.normalize(it, ApiProfileCategory.SAME_PHONE_SERVER) }
        }
    }
    @Test fun legacyLoopbackRemainsRemoteAndCapabilitiesAreExplicit() {
        val legacy = ModelProfileDraft(displayName = "Legacy", baseUrl = ApiEndpointValidator.SAME_PHONE_DEFAULT, modelId = "m")
        assertEquals(ApiProfileCategory.REMOTE_API, legacy.category)
        assertTrue(ModelProfileValidator().validate(legacy).isValid)
        val samePhone = legacy.copy(category = ApiProfileCategory.SAME_PHONE_SERVER)
        assertFalse(ModelProfileValidator().validate(samePhone).isValid)
        assertTrue(ModelProfileValidator().validate(samePhone.copy(structuredOutputFormat = RemoteStructuredOutputFormat.PROMPT_ONLY_JSON)).isValid)
        assertFalse(ModelProfileValidator().validate(samePhone.copy(structuredOutputFormat = RemoteStructuredOutputFormat.PROMPT_ONLY_JSON, showTailscaleToggle = true)).isValid)
    }
    @Test fun keyIntentRejectsUnsafeInputAndRedactsItsValue() {
        assertFailsWith<IllegalArgumentException> { CredentialEdit.Replace(" ") }
        assertFailsWith<IllegalArgumentException> { CredentialEdit.Replace("secret\nheader") }
        assertFalse(CredentialEdit.Replace("secret").toString().contains("secret"))
    }
}
