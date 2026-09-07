package com.hugo.smartexpense.extraction

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenAiCompatibleReceiptModelClientTest {
    private val sampleImage = ReceiptImage(
        sourceName = "receipt.jpg",
        bytes = byteArrayOf(1, 2, 3, 4),
        mimeType = "image/jpeg",
    )

    @Test
    fun buildsTextRequestForOcrModeWithJsonOutputContract() {
        val transport = FakeTransport(
            OpenAiCompatibleApiResponse(
                statusCode = 200,
                body = """{"choices":[{"message":{"content":"{\"receiptDate\":\"2026-06-26\",\"merchantName\":\"Example Shop\",\"totalAmount\":42.35,\"currency\":\"CAD\",\"extractionStatus\":\"confirmed\"}"}}]}""",
            )
        )
        val client = OpenAiCompatibleReceiptModelClient(
            provider = remoteProvider(inputMode = RemoteInputMode.OCR_TEXT),
            apiKeyStore = FakeApiKeyStore("expense-openai" to "super-secret"),
            transport = transport,
        )

        val response = client.extractFromReceiptText("TOTAL 42.35 CAD")

        assertContains(response, "\"merchantName\":\"Example Shop\"")
        assertEquals("https://api.example.com/chat/completions", transport.lastRequest?.url)
        assertEquals("Bearer super-secret", transport.lastRequest?.headers?.get("Authorization"))
        assertContains(transport.lastRequest?.body.orEmpty(), "\"response_format\": {\"type\": \"json_object\"}")
        assertContains(transport.lastRequest?.body.orEmpty(), "Receipt text:")
        assertContains(transport.lastRequest?.body.orEmpty(), "TOTAL 42.35 CAD")
    }

    @Test
    fun buildsImageRequestWhenProviderSupportsDirectImageInput() {
        val transport = FakeTransport(
            OpenAiCompatibleApiResponse(
                statusCode = 200,
                body = """{"choices":[{"message":{"content":[{"type":"text","text":"{\"receiptDate\":\"2026-06-26\",\"merchantName\":\"Vision Shop\",\"totalAmount\":42.35,\"currency\":\"CAD\",\"extractionStatus\":\"confirmed\"}"}]}}]}""",
            )
        )
        val client = OpenAiCompatibleReceiptModelClient(
            provider = remoteProvider(inputMode = RemoteInputMode.DIRECT_IMAGE),
            apiKeyStore = FakeApiKeyStore("expense-openai" to "super-secret"),
            transport = transport,
        )

        val response = client.extractFromReceiptImage(sampleImage)

        assertContains(response, "Vision Shop")
        assertTrue(client.supportsDirectImageInput())
        assertContains(transport.lastRequest?.body.orEmpty(), "\"type\": \"image_url\"")
        assertContains(transport.lastRequest?.body.orEmpty(), "data:image/jpeg;base64,")
    }

    @Test
    fun redactsAuthorizationHeaderForLogging() {
        val request = OpenAiCompatibleApiRequest(
            url = "https://api.example.com/chat/completions",
            headers = mapOf(
                "Authorization" to "Bearer super-secret",
                "Content-Type" to "application/json",
            ),
            body = "{}",
        )

        val redacted = request.redactForLogging()

        assertEquals("Bearer [REDACTED]", redacted.headers["Authorization"])
        assertEquals("application/json", redacted.headers["Content-Type"])
        assertTrue(redacted.bodyDescription.startsWith("[REDACTED request body:"))
    }

    @Test
    fun mapsAuthenticationFailure() {
        val client = OpenAiCompatibleReceiptModelClient(
            provider = remoteProvider(),
            apiKeyStore = FakeApiKeyStore("expense-openai" to "super-secret"),
            transport = FakeTransport(
                OpenAiCompatibleApiResponse(
                    statusCode = 401,
                    body = """{"error":{"message":"invalid api key"}}""",
                )
            ),
        )

        val error = assertFailsWith<RemoteProviderException.AuthenticationFailed> {
            client.extractFromReceiptText("TOTAL 42.35 CAD")
        }

        assertContains(error.message.orEmpty(), "invalid api key")
    }

    @Test
    fun mapsRateLimitFailure() {
        val client = OpenAiCompatibleReceiptModelClient(
            provider = remoteProvider(),
            apiKeyStore = FakeApiKeyStore("expense-openai" to "super-secret"),
            transport = FakeTransport(
                OpenAiCompatibleApiResponse(
                    statusCode = 429,
                    body = """{"error":{"message":"too many requests"}}""",
                )
            ),
        )

        val error = assertFailsWith<RemoteProviderException.RateLimited> {
            client.extractFromReceiptText("TOTAL 42.35 CAD")
        }

        assertContains(error.message.orEmpty(), "too many requests")
    }

    @Test
    fun mapsNetworkFailure() {
        val client = OpenAiCompatibleReceiptModelClient(
            provider = remoteProvider(),
            apiKeyStore = FakeApiKeyStore("expense-openai" to "super-secret"),
            transport = object : OpenAiCompatibleApiTransport {
                override fun send(request: OpenAiCompatibleApiRequest): OpenAiCompatibleApiResponse {
                    throw IOException("offline")
                }
            },
        )

        val error = assertFailsWith<RemoteProviderException.NetworkUnavailable> {
            client.extractFromReceiptText("TOTAL 42.35 CAD")
        }

        assertContains(error.message.orEmpty(), "Network is unavailable")
    }

    @Test
    fun testsProviderConfigurationWithProbeRequest() {
        val client = OpenAiCompatibleReceiptModelClient(
            provider = remoteProvider(),
            apiKeyStore = FakeApiKeyStore("expense-openai" to "super-secret"),
            transport = FakeTransport(
                OpenAiCompatibleApiResponse(
                    statusCode = 200,
                    body = """{"choices":[{"message":{"content":"{\"status\":\"ok\"}"}}]}""",
                )
            ),
        )

        val result = client.testConfiguration()

        assertIs<ProviderTestResult.Success>(result)
    }

    @Test
    fun usesJsonSchemaForLmStudioCompatibleProviders() {
        val transport = FakeTransport(
            OpenAiCompatibleApiResponse(
                statusCode = 200,
                body = """{"choices":[{"message":{"content":"{\"status\":\"ok\"}"}}]}""",
            )
        )
        val client = OpenAiCompatibleReceiptModelClient(
            provider = remoteProvider().copy(structuredOutputFormat = RemoteStructuredOutputFormat.JSON_SCHEMA),
            apiKeyStore = FakeApiKeyStore("expense-openai" to "super-secret"),
            transport = transport,
        )

        client.testConfiguration()

        assertContains(transport.lastRequest?.body.orEmpty(), "\"type\": \"json_schema\"")
        assertContains(transport.lastRequest?.body.orEmpty(), "\"name\": \"provider_connectivity\"")
    }

    private fun remoteProvider(inputMode: RemoteInputMode = RemoteInputMode.OCR_TEXT): OpenAiCompatibleProviderOption =
        OpenAiCompatibleProviderOption(
            id = "remote-openai",
            displayName = "OpenAI Compatible",
            baseUrl = "https://api.example.com",
            modelId = "gpt-4.1-mini",
            inputMode = inputMode,
            apiKeyAlias = "expense-openai",
        )
}

private class FakeTransport(
    private val response: OpenAiCompatibleApiResponse,
) : OpenAiCompatibleApiTransport {
    var lastRequest: OpenAiCompatibleApiRequest? = null
        private set

    override fun send(request: OpenAiCompatibleApiRequest): OpenAiCompatibleApiResponse {
        lastRequest = request
        return response
    }
}

private class FakeApiKeyStore(vararg entries: Pair<String, String>) : ApiKeyStore {
    private val values = entries.toMap()

    override fun get(alias: String): String? = values[alias]
}
