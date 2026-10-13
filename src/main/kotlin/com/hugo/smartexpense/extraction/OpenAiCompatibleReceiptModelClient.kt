package com.hugo.smartexpense.extraction

import java.io.IOException
import java.util.Base64

class OpenAiCompatibleReceiptModelClient(
    private val provider: OpenAiCompatibleProviderOption,
    private val apiKeyStore: ApiKeyStore,
    private val transport: OpenAiCompatibleApiTransport,
) : ReceiptModelClient {
    override fun reviewTypedExpense(draft: TypedExpenseDraft): String = executeWithInvalidJsonRecovery(
        buildTextRequest(TypedExpenseReviewPrompt.text, draft.modelInput(), "typed_expense_review", typedReviewSchema),
        if (provider.structuredOutputFormat == RemoteStructuredOutputFormat.JSON_SCHEMA)
            buildTextRequest(TypedExpenseReviewPrompt.text, draft.modelInput(), "typed_expense_review", typedReviewSchema, false)
        else null,
    )
    override fun supportsDirectImageInput(): Boolean = provider.inputMode == RemoteInputMode.DIRECT_IMAGE

    override fun extractFromReceiptImage(receiptImage: ReceiptImage, prompt: String): String {
        require(supportsDirectImageInput()) {
            "${provider.displayName} is configured for OCR text input, not direct image input."
        }

        return executeWithInvalidJsonRecovery(
            request = buildImageRequest(
                prompt = prompt,
                receiptImage = receiptImage,
            ),
            recoveryRequest = buildImageRequestWithoutResponseFormat(prompt, receiptImage),
        )
    }

    override fun extractFromReceiptText(receiptText: String, prompt: String): String = executeWithInvalidJsonRecovery(
        request = buildTextRequest(
            prompt = prompt,
            receiptText = receiptText,
        ),
        recoveryRequest = buildTextRequestWithoutResponseFormat(prompt, receiptText),
    )

    fun testConfiguration(): ProviderTestResult = try {
        val response = execute(
            buildUserTextRequest(
                promptText = """This is a provider connectivity test. Reply with exactly {"status":"ok"}. Do not include Markdown, explanations, or additional fields.""",
                schemaName = "provider_connectivity",
                schema = providerTestSchema,
            )
        )
        val payload = runCatching { StrictProtocolJson.objectValue(providerTestContent(response)) }.getOrNull()
        if (payload == mapOf("status" to "ok")) {
            ProviderTestResult.Success
        } else {
            val reason = when {
                response.isBlank() -> "Assistant content was empty."
                payload == null -> "Assistant content was not one valid JSON object."
                else -> "The JSON object did not match the connectivity test."
            }
            ProviderTestResult.Failed(
                "Provider test received HTTP 200 with assistant content, but verification failed. " +
                    "$reason Expected exactly {\"status\":\"ok\"}. Retry verification; if it persists, check the model's JSON output in the companion/provider."
            )
        }
    } catch (error: RemoteProviderException) {
        ProviderTestResult.Failed(error.message ?: "Provider test failed.")
    }

    // Prompt-only models can wrap JSON in a code block, as supported by receipt review.
    // Unwrap only a complete block; never search for JSON inside prose or hidden reasoning.
    private fun providerTestContent(response: String): String {
        val text = response.trim()
        if (provider.structuredOutputFormat != RemoteStructuredOutputFormat.PROMPT_ONLY_JSON) return text
        val firstLine = text.substringBefore('\n').removeSuffix("\r")
        if (firstLine != "```" && !firstLine.equals("```json", ignoreCase = true)) return text
        val closingLine = text.lastIndexOf('\n')
        if (closingLine <= text.indexOf('\n') || text.substring(closingLine + 1) != "```") return text
        return text.substring(text.indexOf('\n') + 1, closingLine).trim()
    }

    private fun execute(request: OpenAiCompatibleApiRequest): String {
        try {
            val response = send(request, verification = true)
            return mapResponse(response)
        } catch (error: IOException) {
            throw RemoteProviderException.NetworkUnavailable(
                provider.displayName,
                "Network is unavailable for ${provider.displayName}.",
                null,
            )
        }
    }

    private fun executeWithInvalidJsonRecovery(
        request: OpenAiCompatibleApiRequest,
        recoveryRequest: OpenAiCompatibleApiRequest?,
    ): String {
        try {
            val response = send(request)
            val finalResponse = if (recoveryRequest != null && response.isInvalidJsonRequest()) {
                send(recoveryRequest)
            } else {
                response
            }
            return mapResponse(finalResponse)
        } catch (error: IOException) {
            throw RemoteProviderException.NetworkUnavailable(
                provider.displayName,
                "Network is unavailable for ${provider.displayName}.",
                null,
            )
        }
    }

    private fun send(request: OpenAiCompatibleApiRequest, verification: Boolean = false): OpenAiCompatibleApiResponse =
        InferenceExecution.request(verification, if (provider.category == ApiProfileCategory.SAME_PHONE_SERVER)
            ProviderCategory.SAME_PHONE_SERVER else ProviderCategory.REMOTE_API) {
            if (provider.category == ApiProfileCategory.SAME_PHONE_SERVER) SamePhoneRequestLifetime.send(request, transport)
            else transport.send(request)
        }

    private fun OpenAiCompatibleApiResponse.isInvalidJsonRequest(): Boolean {
        if (statusCode != 400) return false
        return body.contains("\"code\":\"invalid_json\"", ignoreCase = true) ||
            body.contains("\"code\": \"invalid_json\"", ignoreCase = true) ||
            body.contains("failed to parse JSON", ignoreCase = true)
    }

    private fun effectiveKey(): String? {
        val scope = InferenceExecution.current.get()
        return if (scope == null) apiKeyStore.get(provider.apiKeyAlias) else scope.credentials.get(provider.apiKeyAlias, apiKeyStore)
    }

    private fun safeErrorBody(body: String): String? {
        if (provider.category == ApiProfileCategory.SAME_PHONE_SERVER) return null
        val key = effectiveKey()
        return if (key.isNullOrEmpty()) body else body.replace(key, "[REDACTED]")
    }

    private fun mapResponse(response: OpenAiCompatibleApiResponse): String {
        return when (response.statusCode) {
            200 -> parseAssistantContent(response.body).also { content ->
                val key = effectiveKey()
                if (!key.isNullOrEmpty() && content.contains(key)) throw RemoteProviderException.UnexpectedResponse(
                    provider.displayName, "Provider response contained credential material; output was rejected.")
            }
            401, 403 -> throw RemoteProviderException.AuthenticationFailed(
                provider.displayName,
                extractErrorMessage(response) ?: "Authentication failed for ${provider.displayName}.",
                safeErrorBody(response.body),
            )
            429 -> throw RemoteProviderException.RateLimited(
                provider.displayName,
                extractErrorMessage(response) ?: "${provider.displayName} rate limited the request.",
                safeErrorBody(response.body),
                retryAfterMillis = parseRetryAfter(response.headers.entries.firstOrNull { it.key.equals("Retry-After", true) }?.value),
            )
            else -> throw RemoteProviderException.UnexpectedResponse(
                provider.displayName,
                extractErrorMessage(response)
                    ?: "Unexpected ${response.statusCode} response from ${provider.displayName}.",
                safeErrorBody(response.body),
                httpStatusCode = response.statusCode,
            )
        }
    }

    private fun buildTextRequest(
        prompt: String,
        receiptText: String,
        schemaName: String = "receipt_extraction",
        schema: String = receiptExtractionSchema,
        includeResponseFormat: Boolean = true,
    ): OpenAiCompatibleApiRequest {
        val promptText = buildString {
            append(prompt)
            append("\n\nReceipt text:\n")
            append(receiptText)
        }
        return buildUserTextRequest(promptText, schemaName, schema, includeResponseFormat)
    }

    private fun buildUserTextRequest(
        promptText: String,
        schemaName: String,
        schema: String,
        includeResponseFormat: Boolean = true,
    ): OpenAiCompatibleApiRequest {
        val body = """
            {
              "model": ${jsonString(provider.modelId)},
              ${responseFormatField(schemaName, schema, includeResponseFormat)}
              "messages": [
                {
                  "role": "user",
                  "content": ${jsonString(promptText)}
                }
              ]
            }
        """.trimIndent()
        return buildRequest(body)
    }

    private fun buildTextRequestWithoutResponseFormat(
        prompt: String,
        receiptText: String,
    ): OpenAiCompatibleApiRequest? = if (provider.structuredOutputFormat == RemoteStructuredOutputFormat.JSON_SCHEMA) {
        buildTextRequest(prompt, receiptText, includeResponseFormat = false)
    } else {
        null
    }

    private fun buildImageRequest(
        prompt: String,
        receiptImage: ReceiptImage,
        includeResponseFormat: Boolean = true,
    ): OpenAiCompatibleApiRequest {
        if (provider.category == ApiProfileCategory.SAME_PHONE_SERVER) {
            require(receiptImage.mimeType in setOf("image/jpeg", "image/png")) { "Use a JPEG or PNG receipt." }
            require(receiptImage.bytes.size <= 8 * 1024 * 1024) { "Enable preprocessing or select a smaller receipt image." }
            ImageAdmission.validate(receiptImage)
        }
        val imageDataUrl = "data:${receiptImage.mimeType};base64," +
            Base64.getEncoder().encodeToString(receiptImage.bytes)
        val body = """
            {
              "model": ${jsonString(provider.modelId)},
              ${responseFormatField("receipt_extraction", receiptExtractionSchema, includeResponseFormat)}
              "messages": [
                {
                  "role": "user",
                  "content": [
                    {
                      "type": "text",
                      "text": ${jsonString(prompt)}
                    },
                    {
                      "type": "image_url",
                      "image_url": {
                        "url": ${jsonString(imageDataUrl)}
                      }
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        return buildRequest(body)
    }

    private fun buildImageRequestWithoutResponseFormat(
        prompt: String,
        receiptImage: ReceiptImage,
    ): OpenAiCompatibleApiRequest? = if (provider.structuredOutputFormat == RemoteStructuredOutputFormat.JSON_SCHEMA) {
        buildImageRequest(prompt, receiptImage, includeResponseFormat = false)
    } else {
        null
    }

    private fun buildRequest(body: String): OpenAiCompatibleApiRequest {
        val activeScope = InferenceExecution.current.get()
        val apiKey = if (activeScope == null) apiKeyStore.get(provider.apiKeyAlias)
            else activeScope.credentials.get(provider.apiKeyAlias, apiKeyStore)
        if (apiKey == null && provider.category == ApiProfileCategory.REMOTE_API)
            throw RemoteProviderException.AuthenticationFailed(provider.displayName, "No API key is available for ${provider.displayName}.")
        require(apiKey == null || (apiKey.isNotBlank() && apiKey.all { it.code in 32..126 })) { "API key must be nonblank and header-safe." }
        val base = ApiEndpointValidator.normalize(provider.baseUrl, provider.category)
        require(provider.category != ApiProfileCategory.SAME_PHONE_SERVER ||
            (provider.inputMode == RemoteInputMode.DIRECT_IMAGE && provider.structuredOutputFormat == RemoteStructuredOutputFormat.PROMPT_ONLY_JSON)) {
            "Same-phone server requires direct image input and Prompt-only JSON."
        }
        require(provider.category != ApiProfileCategory.SAME_PHONE_SERVER || body.toByteArray(Charsets.UTF_8).size <= 12 * 1024 * 1024) {
            "Request exceeds 12 MiB; reduce the receipt input."
        }
        return OpenAiCompatibleApiRequest(
            url = "$base/chat/completions",
            headers = buildMap {
                if (apiKey != null) put("Authorization", "Bearer $apiKey")
                put("Content-Type", "application/json")
            },
            body = body,
            category = provider.category,
            approvedBaseUrl = base,
        )
    }

    private fun structuredOutputFormat(schemaName: String, schema: String): String = when (provider.structuredOutputFormat) {
        RemoteStructuredOutputFormat.PROMPT_ONLY_JSON -> error("Prompt-only JSON has no response format.")
        RemoteStructuredOutputFormat.JSON_OBJECT -> "{\"type\": \"json_object\"}"
        RemoteStructuredOutputFormat.JSON_SCHEMA -> """
            {
              "type": "json_schema",
              "json_schema": {
                "name": ${jsonString(schemaName)},
                "strict": true,
                "schema": $schema
              }
            }
        """.trimIndent()
    }

    private fun responseFormatField(schemaName: String, schema: String, include: Boolean): String =
        if (include && provider.structuredOutputFormat != RemoteStructuredOutputFormat.PROMPT_ONLY_JSON) {
            "\"response_format\": ${structuredOutputFormat(schemaName, schema)},"
        } else {
            ""
        }

    private fun parseAssistantContent(responseBody: String): String {
        try {
            val envelope = StrictProtocolJson.objectValue(responseBody)
            val choices = envelope["choices"] as? List<*> ?: error("Missing choices.")
            val message = (choices.firstOrNull() as? Map<*, *>)?.get("message") as? Map<*, *> ?: error("Missing message.")
            // Preserve legacy remote providers that omit role; same-phone requires the contract role.
            require(message["role"] == "assistant" || (provider.category == ApiProfileCategory.REMOTE_API && !message.containsKey("role")))
            return when (val content = message["content"]) {
                is String -> content
                is List<*> -> {
                    require(provider.category == ApiProfileCategory.REMOTE_API && content.isNotEmpty())
                    content.joinToString("") { part ->
                        val textPart = part as? Map<*, *> ?: error("Invalid content part.")
                        require(textPart["type"] == "text")
                        textPart["text"] as? String ?: error("Missing text.")
                    }
                }
                else -> error("Unsupported assistant content.")
            }
        } catch (_: IllegalArgumentException) { unexpectedContent() }
        catch (_: IllegalStateException) { unexpectedContent() }
    }

    private fun extractErrorMessage(response: OpenAiCompatibleApiResponse): String? {
        if (provider.category == ApiProfileCategory.SAME_PHONE_SERVER) return SamePhoneRequestLifetime.guidance(response)
        val responseBody = response.body
        val message = runCatching { (StrictProtocolJson.objectValue(responseBody)["error"] as? Map<*, *>)?.get("message") as? String }.getOrNull()
        val key = effectiveKey()
        return message?.let { if (key.isNullOrEmpty()) it else it.replace(key, "[REDACTED]") }
    }
    private fun unexpectedContent(): Nothing = throw RemoteProviderException.UnexpectedResponse(
        provider.displayName, "Provider response did not include valid assistant content.")

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }

    private companion object {
        val typedReviewSchema get() = receiptExtractionSchema
            .replace("\"items\": {", "\"minItems\": 1, \"maxItems\": 1, \"items\": {")
            .replace("\"merchantLocation\": {\"type\": [\"string\", \"null\"]}",
                "\"merchantLocation\": {\"type\": [\"string\", \"null\"]}, \"issues\": {\"type\": \"array\", \"items\": {\"type\": \"string\"}}, \"suggestedCorrections\": {\"type\": \"array\", \"items\": {\"type\": \"string\"}}")
            .replace("\"confidence\", \"merchantLocation\"]", "\"confidence\", \"merchantLocation\", \"issues\", \"suggestedCorrections\"]")
        val receiptExtractionSchema = """
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "receipts": {"type": "array", "items": {
                  "type": "object", "additionalProperties": false, "properties": {
                "receiptDate": {"type": "string"},
                "merchantName": {"type": "string"},
                "totalAmount": {"type": "number"},
                "currency": {"type": "string"},
                "extractionStatus": {"type": "string", "enum": ["confirmed", "low_confidence", "failed"]},
                "confidence": {"type": "number"},
                "merchantLocation": {"type": ["string", "null"]}
                }, "required": ["receiptDate", "merchantName", "totalAmount", "currency", "extractionStatus", "confidence", "merchantLocation"]}
              }
              },
              "required": ["receipts"]
            }
        """.trimIndent()

        val providerTestSchema = """
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "status": {"type": "string", "enum": ["ok"]}
              },
              "required": ["status"]
            }
        """.trimIndent()
    }
}

data class OpenAiCompatibleApiRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: String,
    val method: String = "POST",
    val category: ApiProfileCategory = ApiProfileCategory.REMOTE_API,
    val approvedBaseUrl: String? = null,
    val cleanupDeadlineNanos: Long? = null,
) {
    fun redactForLogging(): RedactedApiRequest = RedactedApiRequest(
        url = url,
        headers = headers.mapValues { (key, value) ->
            if (key.equals("Authorization", ignoreCase = true)) "Bearer [REDACTED]" else value
        },
        bodyDescription = "[REDACTED request body: ${body.length} characters]",
    )
}

data class RedactedApiRequest(
    val url: String,
    val headers: Map<String, String>,
    val bodyDescription: String,
)

data class OpenAiCompatibleApiResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
    val companionRequestStage: CompanionRequestStage? = null,
)

enum class CompanionRequestStage(val description: String) {
    PREPARATION("Request preparation"), CHAT("Chat inference"),
}

interface OpenAiCompatibleApiTransport {
    fun send(request: OpenAiCompatibleApiRequest): OpenAiCompatibleApiResponse
}

fun interface ApiKeyStore {
    fun get(alias: String): String?
}

sealed class ProviderTestResult {
    data object Success : ProviderTestResult()
    data class Failed(val reason: String) : ProviderTestResult()
}

sealed class RemoteProviderException(
    val providerDisplayName: String,
    message: String,
    cause: Throwable? = null,
    val rawResponseBody: String? = null,
) : RuntimeException(message, cause) {
    class AuthenticationFailed(providerDisplayName: String, message: String, rawResponseBody: String? = null) :
        RemoteProviderException(providerDisplayName, message, rawResponseBody = rawResponseBody)

    class RateLimited(providerDisplayName: String, message: String, rawResponseBody: String? = null, val retryAfterMillis: Long? = null) :
        RemoteProviderException(providerDisplayName, message, rawResponseBody = rawResponseBody)

    class NetworkUnavailable(providerDisplayName: String, message: String, cause: Throwable? = null) :
        RemoteProviderException(providerDisplayName, message, cause)

    class UnexpectedResponse(providerDisplayName: String, message: String, rawResponseBody: String? = null, val httpStatusCode: Int? = null) :
        RemoteProviderException(providerDisplayName, message, rawResponseBody = rawResponseBody)
}

internal fun parseRetryAfter(value: String?, now: java.time.Instant = java.time.Instant.now()): Long? {
    if (value == null) return null
    value.trim().toLongOrNull()?.takeIf { it >= 0 && it <= Long.MAX_VALUE / 1000 }?.let { return it * 1000 }
    return runCatching {
        val time = java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        java.time.Duration.between(now, time).toMillis().coerceAtLeast(0)
    }.getOrNull()
}
