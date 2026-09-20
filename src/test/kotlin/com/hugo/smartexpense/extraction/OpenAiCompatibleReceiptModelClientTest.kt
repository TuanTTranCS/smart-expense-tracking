package com.hugo.smartexpense.extraction

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
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
        assertEquals("""{"error":{"message":"invalid api key"}}""", error.rawResponseBody)
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
        assertEquals("""{"error":{"message":"too many requests"}}""", error.rawResponseBody)
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
        assertEquals(null, error.rawResponseBody)
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

    @Test
    fun receiptSchemaProducesValidJsonRequestsForImageAndOcrText() {
        for (inputMode in listOf(RemoteInputMode.DIRECT_IMAGE, RemoteInputMode.OCR_TEXT)) {
            val transport = FakeTransport(assistantResponse("""{"receipts":[]}"""))
            val client = OpenAiCompatibleReceiptModelClient(
                provider = remoteProvider(inputMode).copy(structuredOutputFormat = RemoteStructuredOutputFormat.JSON_SCHEMA),
                apiKeyStore = FakeApiKeyStore("expense-openai" to "super-secret"),
                transport = transport,
            )
            if (inputMode == RemoteInputMode.DIRECT_IMAGE) {
                client.extractFromReceiptImage(sampleImage)
            } else {
                client.extractFromReceiptText("TOTAL 42.35 CAD")
            }

            val request = JSONObject(requireNotNull(transport.lastRequest).body)
            assertHasNoTrailingJsonComma(requireNotNull(transport.lastRequest).body)
            val format = request.getJSONObject("response_format")
            assertEquals("json_schema", format.getString("type"))
            val wrapper = format.getJSONObject("json_schema")
            assertEquals("receipt_extraction", wrapper.getString("name"))
            val schema = wrapper.getJSONObject("schema")
            assertEquals("object", schema.getString("type"))
            assertEquals(false, schema.getBoolean("additionalProperties"))
            assertEquals("receipts", schema.getJSONArray("required").getString(0))
            val receipts = schema.getJSONObject("properties").getJSONObject("receipts")
            assertEquals("array", receipts.getString("type"))
            val item = receipts.getJSONObject("items")
            assertEquals("object", item.getString("type"))
            assertEquals(false, item.getBoolean("additionalProperties"))
            assertEquals("receiptDate", item.getJSONArray("required").getString(0))
            assertEquals(7, item.getJSONObject("properties").length())
        }
    }

    @Test
    fun retriesJsonSchemaExtractionWithoutResponseFormatAfterInvalidJsonResponse() {
        val transport = SequencedFakeTransport(
            OpenAiCompatibleApiResponse(
                statusCode = 400,
                body = """{"error":{"message":"Invalid body: failed to parse JSON value","code":"invalid_json"}}""",
            ),
            assistantResponse("""{"receipts":[]}"""),
        )
        val client = OpenAiCompatibleReceiptModelClient(
            provider = remoteProvider(RemoteInputMode.DIRECT_IMAGE).copy(
                structuredOutputFormat = RemoteStructuredOutputFormat.JSON_SCHEMA,
            ),
            apiKeyStore = FakeApiKeyStore("expense-openai" to "super-secret"),
            transport = transport,
        )

        assertEquals("""{"receipts":[]}""", client.extractFromReceiptImage(sampleImage))
        assertEquals(2, transport.requests.size)
        assertContains(transport.requests.first().body, "\"response_format\"")
        assertTrue(!transport.requests.last().body.contains("\"response_format\""))
        assertContains(transport.requests.last().body, "\"type\": \"image_url\"")
        assertHasNoTrailingJsonComma(transport.requests.last().body)
        JSONObject(transport.requests.last().body)
    }

    @Test
    fun doesNotRetryOtherProviderErrors() {
        val transport = SequencedFakeTransport(
            OpenAiCompatibleApiResponse(
                statusCode = 400,
                body = """{"error":{"message":"model not found","code":"model_not_found"}}""",
            ),
            assistantResponse("""{"receipts":[]}"""),
        )
        val client = OpenAiCompatibleReceiptModelClient(
            provider = remoteProvider(RemoteInputMode.DIRECT_IMAGE).copy(
                structuredOutputFormat = RemoteStructuredOutputFormat.JSON_SCHEMA,
            ),
            apiKeyStore = FakeApiKeyStore("expense-openai" to "super-secret"),
            transport = transport,
        )

        assertFailsWith<RemoteProviderException.UnexpectedResponse> {
            client.extractFromReceiptImage(sampleImage)
        }
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun jsonObjectRequestsRemainValidWithControlCharactersInOcrText() {
        val transport = FakeTransport(assistantResponse("{}"))
        val client = OpenAiCompatibleReceiptModelClient(
            provider = remoteProvider(RemoteInputMode.OCR_TEXT),
            apiKeyStore = FakeApiKeyStore("expense-openai" to "super-secret"),
            transport = transport,
        )
        client.extractFromReceiptText("line one\u000cline two\u0001")
        val request = JSONObject(requireNotNull(transport.lastRequest).body)
        assertEquals("json_object", request.getJSONObject("response_format").getString("type"))
        assertContains(request.getJSONArray("messages").getJSONObject(0).getString("content"), "line one\u000cline two\u0001")
    }

    @Test
    fun remoteImagePipelineParsesOneAndMultipleReceiptsWithEscapedMerchantName() {
        val receipt = """{"receiptDate":"2026-09-15","merchantName":"Hugo's \"Market\"","totalAmount":12.34,"currency":"CAD","extractionStatus":"confirmed","confidence":0.9,"merchantLocation":null}"""
        for (raw in listOf("""{"receipts":[$receipt]}""", """{"receipts":[$receipt,$receipt]}""")) {
            val client = OpenAiCompatibleReceiptModelClient(
                provider = remoteProvider(RemoteInputMode.DIRECT_IMAGE).copy(
                    structuredOutputFormat = RemoteStructuredOutputFormat.JSON_SCHEMA,
                ),
                apiKeyStore = FakeApiKeyStore("expense-openai" to "super-secret"),
                transport = FakeTransport(assistantResponse(raw)),
            )
            val result = assertIs<ReceiptExtractionPipelineResult.Success>(
                ReceiptExtractionPipeline(client).extract(sampleImage),
            )
            assertEquals(if (raw.contains(",$receipt")) 2 else 1, result.results.size)
            assertEquals("Hugo's \"Market\"", result.results.first().merchantName)
            assertEquals(raw, result.results.first().rawModelOutput)
        }
    }

    private fun assistantResponse(raw: String): OpenAiCompatibleApiResponse = OpenAiCompatibleApiResponse(
        statusCode = 200,
        body = JSONObject().put(
            "choices",
            JSONArray().put(JSONObject().put("message", JSONObject().put("content", raw))),
        ).toString(),
    )

    private fun assertHasNoTrailingJsonComma(json: String) {
        var inString = false
        var escaped = false
        json.forEachIndexed { index, character ->
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (character == '\\') {
                    escaped = true
                } else if (character == '"') {
                    inString = false
                }
            } else if (character == '"') {
                inString = true
            } else if (character == ',') {
                val next = json.drop(index + 1).firstOrNull { !it.isWhitespace() }
                assertTrue(next != '}' && next != ']', "Request JSON contains a trailing comma.")
            }
        }
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

private class SequencedFakeTransport(
    vararg responses: OpenAiCompatibleApiResponse,
) : OpenAiCompatibleApiTransport {
    private val remainingResponses = ArrayDeque(responses.toList())
    val requests = mutableListOf<OpenAiCompatibleApiRequest>()

    override fun send(request: OpenAiCompatibleApiRequest): OpenAiCompatibleApiResponse {
        requests += request
        return remainingResponses.removeFirst()
    }
}

private class FakeApiKeyStore(vararg entries: Pair<String, String>) : ApiKeyStore {
    private val values = entries.toMap()

    override fun get(alias: String): String? = values[alias]
}
