package com.hugo.smartexpense.extraction

import java.io.IOException
import java.util.Base64

class OpenAiCompatibleReceiptModelClient(
    private val provider: OpenAiCompatibleProviderOption,
    private val apiKeyStore: ApiKeyStore,
    private val transport: OpenAiCompatibleApiTransport,
) : ReceiptModelClient {
    override fun supportsDirectImageInput(): Boolean = provider.inputMode == RemoteInputMode.DIRECT_IMAGE

    override fun extractFromReceiptImage(receiptImage: ReceiptImage, prompt: String): String {
        require(supportsDirectImageInput()) {
            "${provider.displayName} is configured for OCR text input, not direct image input."
        }

        return execute(
            buildImageRequest(
                prompt = prompt,
                receiptImage = receiptImage,
            )
        )
    }

    override fun extractFromReceiptText(receiptText: String, prompt: String): String = execute(
        buildTextRequest(
            prompt = prompt,
            receiptText = receiptText,
        )
    )

    fun testConfiguration(): ProviderTestResult = try {
        val response = execute(
            buildTextRequest(
                prompt = """Return only this JSON object: {"status":"ok"}""",
                receiptText = "Provider connectivity test.",
                schemaName = "provider_connectivity",
                schema = providerTestSchema,
            )
        )
        if (response.trim() == "{\"status\":\"ok\"}") {
            ProviderTestResult.Success
        } else {
            ProviderTestResult.Failed("Provider test did not return the expected JSON payload.")
        }
    } catch (error: RemoteProviderException) {
        ProviderTestResult.Failed(error.message ?: "Provider test failed.")
    }

    private fun execute(request: OpenAiCompatibleApiRequest): String {
        try {
            val response = transport.send(request)
            return mapResponse(response)
        } catch (error: IOException) {
            throw RemoteProviderException.NetworkUnavailable(
                provider.displayName,
                "Network is unavailable for ${provider.displayName}.",
                error,
            )
        }
    }

    private fun mapResponse(response: OpenAiCompatibleApiResponse): String {
        return when (response.statusCode) {
            200 -> parseAssistantContent(response.body)
            401, 403 -> throw RemoteProviderException.AuthenticationFailed(
                provider.displayName,
                extractErrorMessage(response.body) ?: "Authentication failed for ${provider.displayName}.",
            )
            429 -> throw RemoteProviderException.RateLimited(
                provider.displayName,
                extractErrorMessage(response.body) ?: "${provider.displayName} rate limited the request.",
            )
            else -> throw RemoteProviderException.UnexpectedResponse(
                provider.displayName,
                extractErrorMessage(response.body)
                    ?: "Unexpected ${response.statusCode} response from ${provider.displayName}.",
            )
        }
    }

    private fun buildTextRequest(
        prompt: String,
        receiptText: String,
        schemaName: String = "receipt_extraction",
        schema: String = receiptExtractionSchema,
    ): OpenAiCompatibleApiRequest {
        val promptText = buildString {
            append(prompt)
            append("\n\nReceipt text:\n")
            append(receiptText)
        }
        val body = """
            {
              "model": ${jsonString(provider.modelId)},
              "response_format": ${structuredOutputFormat(schemaName, schema)},
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

    private fun buildImageRequest(prompt: String, receiptImage: ReceiptImage): OpenAiCompatibleApiRequest {
        val imageDataUrl = "data:${receiptImage.mimeType};base64," +
            Base64.getEncoder().encodeToString(receiptImage.bytes)
        val body = """
            {
              "model": ${jsonString(provider.modelId)},
              "response_format": ${structuredOutputFormat("receipt_extraction", receiptExtractionSchema)},
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

    private fun buildRequest(body: String): OpenAiCompatibleApiRequest {
        val apiKey = apiKeyStore.get(provider.apiKeyAlias)
            ?: throw RemoteProviderException.AuthenticationFailed(
                provider.displayName,
                "No API key is available for ${provider.displayName}.",
            )

        return OpenAiCompatibleApiRequest(
            url = "${provider.baseUrl.trimEnd('/')}/chat/completions",
            headers = mapOf(
                "Authorization" to "Bearer $apiKey",
                "Content-Type" to "application/json",
            ),
            body = body,
        )
    }

    private fun structuredOutputFormat(schemaName: String, schema: String): String = when (provider.structuredOutputFormat) {
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

    private fun parseAssistantContent(responseBody: String): String {
        val contentFieldIndex = responseBody.indexOf("\"content\"")
        if (contentFieldIndex >= 0) {
            val colonIndex = responseBody.indexOf(':', startIndex = contentFieldIndex + "\"content\"".length)
            if (colonIndex >= 0) {
                val firstValueIndex = responseBody.indexOfFirstNonWhitespace(startIndex = colonIndex + 1)
                if (firstValueIndex >= 0) {
                    return when (responseBody[firstValueIndex]) {
                        '"' -> readJsonString(responseBody, firstValueIndex) ?: unexpectedContent()
                        '[' -> {
                            val arraySlice = responseBody.substring(firstValueIndex)
                            findStringFieldAfter(arraySlice, "\"text\"") ?: unexpectedContent()
                        }
                        else -> unexpectedContent()
                    }
                }
            }
        }

        val textContent = findStringFieldAfter(responseBody, "\"text\"")
        if (textContent != null) {
            return textContent
        }

        throw RemoteProviderException.UnexpectedResponse(
            provider.displayName,
            "Provider response did not include assistant content.",
        )
    }

    private fun extractErrorMessage(responseBody: String): String? {
        return findStringFieldAfter(responseBody, "\"message\"")
            ?: findStringFieldAfter(responseBody, "\"error\"")
    }

    private fun findStringFieldAfter(body: String, fieldNameToken: String): String? {
        val fieldIndex = body.indexOf(fieldNameToken)
        if (fieldIndex < 0) {
            return null
        }

        val colonIndex = body.indexOf(':', startIndex = fieldIndex + fieldNameToken.length)
        if (colonIndex < 0) {
            return null
        }

        val firstQuoteIndex = body.indexOfFirstNonWhitespace(startIndex = colonIndex + 1)
        if (firstQuoteIndex < 0 || body[firstQuoteIndex] != '"') {
            return null
        }

        return readJsonString(body, firstQuoteIndex)
    }

    private fun readJsonString(body: String, openingQuoteIndex: Int): String? {
        val value = StringBuilder()
        var escaped = false
        for (index in (openingQuoteIndex + 1) until body.length) {
            val character = body[index]
            if (escaped) {
                value.append(
                    when (character) {
                        '\\', '/', '"' -> character
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        else -> character
                    }
                )
                escaped = false
                continue
            }

            when (character) {
                '\\' -> escaped = true
                '"' -> return value.toString()
                else -> value.append(character)
            }
        }

        return null
    }

    private fun String.indexOfFirstNonWhitespace(startIndex: Int): Int {
        for (index in startIndex until length) {
            if (!this[index].isWhitespace()) {
                return index
            }
        }
        return -1
    }

    private fun unexpectedContent(): Nothing = throw RemoteProviderException.UnexpectedResponse(
        provider.displayName,
        "Provider response did not include assistant content.",
    )

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }

    private companion object {
        val receiptExtractionSchema = """
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "receiptDate": {"type": "string"},
                "merchantName": {"type": "string"},
                "totalAmount": {"type": "number"},
                "currency": {"type": "string"},
                "extractionStatus": {"type": "string", "enum": ["confirmed", "low_confidence", "failed"]},
                "confidence": {"type": "number"},
                "merchantLocation": {"type": ["string", "null"]}
              },
              "required": ["receiptDate", "merchantName", "totalAmount", "currency", "extractionStatus", "confidence", "merchantLocation"]
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
)

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
) : RuntimeException(message, cause) {
    class AuthenticationFailed(providerDisplayName: String, message: String) :
        RemoteProviderException(providerDisplayName, message)

    class RateLimited(providerDisplayName: String, message: String) :
        RemoteProviderException(providerDisplayName, message)

    class NetworkUnavailable(providerDisplayName: String, message: String, cause: Throwable? = null) :
        RemoteProviderException(providerDisplayName, message, cause)

    class UnexpectedResponse(providerDisplayName: String, message: String) :
        RemoteProviderException(providerDisplayName, message)
}
