package com.hugo.smartexpense.extraction

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class ProviderConnectivityPayloadTest {
    private val instance = "11111111-1111-4111-8111-111111111111"
    private val requestId = "22222222-2222-4222-8222-222222222222"

    private fun verify(
        content: String,
        format: RemoteStructuredOutputFormat = RemoteStructuredOutputFormat.PROMPT_ONLY_JSON,
        requests: MutableList<OpenAiCompatibleApiRequest> = mutableListOf(),
    ): ProviderTestResult {
        val category = if (format == RemoteStructuredOutputFormat.PROMPT_ONLY_JSON)
            ApiProfileCategory.SAME_PHONE_SERVER else ApiProfileCategory.REMOTE_API
        val provider = OpenAiCompatibleProviderOption("provider", "Provider", "http://127.0.0.1:8080/v1",
            "model", RemoteInputMode.DIRECT_IMAGE, "credential", format, category = category)
        val transport = object : OpenAiCompatibleApiTransport {
            override fun send(request: OpenAiCompatibleApiRequest): OpenAiCompatibleApiResponse {
                requests += request
                if (request.url.endsWith("/requests")) return OpenAiCompatibleApiResponse(201,
                    """{"instanceId":"$instance","requestId":"$requestId","runtimeState":"available"}""")
                // Matches the companion's real envelope; only visible model text is variable.
                val envelope = JSONObject().put("id", "chatcmpl-test").put("object", "chat.completion")
                    .put("created", 1).put("model", "model")
                    .put("choices", JSONArray().put(JSONObject().put("index", 0).put("finish_reason", "stop")
                        .put("message", JSONObject().put("role", "assistant").put("content", content))))
                return OpenAiCompatibleApiResponse(200, envelope.toString(),
                    mapOf("X-Companion-Instance" to instance, "X-Request-Id" to requestId))
            }
        }
        return OpenAiCompatibleReceiptModelClient(provider, ApiKeyStore { "private-test-key" }, transport).testConfiguration()
    }

    @Test fun promptOnlyVerificationAcceptsBareAndCompletelyFencedJson() {
        for (content in listOf(" { \"status\" : \"ok\" } \n", "```json\n{\"status\":\"ok\"}\n```",
            "```\n{\"status\":\"ok\"}\n```", " \r\n```JSON\r\n{\"status\":\"ok\"}\r\n```\r\n")) {
            val requests = mutableListOf<OpenAiCompatibleApiRequest>()
            assertEquals(ProviderTestResult.Success, verify(content, requests = requests), content)
            assertEquals(2, requests.size)
            assertFalse(requests.last().body.contains("response_format"))
        }
    }

    @Test fun promptOnlyVerificationRejectsProseIncompleteFencesAndInvalidSemantics() {
        for (content in listOf("Here it is: {\"status\":\"ok\"}", "<think>reason</think>{\"status\":\"ok\"}",
            "```json\n{\"status\":\"ok\"}", "```json\n{\"status\":\"ok\"}\n```\nDone",
            "```javascript\n{\"status\":\"ok\"}\n```", "```json\n{\"status\":\"ok\"} trailing\n```",
            "```json\n{\"status\":\"ok\",\"status\":\"ok\"}\n```",
            "```json\n{\"status\":\"ok\",\"extra\":true}\n```", "```json\n{\"status\":true}\n```",
            "```json\n{\"status\":\"wrong\"}\n```", "```json\n[]\n```")) {
            assertIs<ProviderTestResult.Failed>(verify(content), content)
        }
    }

    @Test fun structuredModesStillRequireBareJson() {
        for (format in listOf(RemoteStructuredOutputFormat.JSON_SCHEMA, RemoteStructuredOutputFormat.JSON_OBJECT)) {
            assertEquals(ProviderTestResult.Success, verify("{\"status\":\"ok\"}", format))
            assertIs<ProviderTestResult.Failed>(verify("```json\n{\"status\":\"ok\"}\n```", format))
        }
    }

    @Test fun verificationUsesDedicatedConnectivityPromptWithoutReceiptInstructions() {
        val requests = mutableListOf<OpenAiCompatibleApiRequest>()
        assertEquals(ProviderTestResult.Success, verify("{\"status\":\"ok\"}", requests = requests))
        val prompt = JSONObject(requests.last().body).getJSONArray("messages").getJSONObject(0).getString("content")
        assertContains(prompt, "Reply with exactly {\"status\":\"ok\"}")
        assertContains(prompt, "Do not include Markdown")
        assertFalse(prompt.contains("Receipt text"))
    }

    @Test fun failuresDistinguishEmptyMalformedAndWrongObjectsWithoutEchoingModelOutput() {
        for ((content, reason) in listOf(" " to "Assistant content was empty.",
            "sensitive-user-text" to "Assistant content was not one valid JSON object.",
            "{\"status\":\"sensitive-user-text\"}" to "The JSON object did not match the connectivity test.")) {
            val failure = assertIs<ProviderTestResult.Failed>(verify(content))
            assertContains(failure.reason, "HTTP 200")
            assertContains(failure.reason, reason)
            assertFalse(failure.reason.contains("sensitive-user-text"))
            assertFalse(failure.reason.contains("private-test-key"))
        }
    }

    @Test fun credentialMaterialInAssistantContentIsStillRejected() {
        val failure = assertIs<ProviderTestResult.Failed>(verify("```json\n{\"status\":\"private-test-key\"}\n```"))
        assertContains(failure.reason, "credential material")
        assertFalse(failure.reason.contains("private-test-key"))
    }
}
