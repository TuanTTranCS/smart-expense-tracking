package com.hugo.smartexpense.app.graphauth

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class GraphHttpRequest(
    val url: String,
    val headers: Map<String, String>,
)

data class GraphHttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
)

fun interface GraphHttpTransport {
    fun get(request: GraphHttpRequest): GraphHttpResponse
}

class AndroidGraphHttpTransport : GraphHttpTransport {
    override fun get(request: GraphHttpRequest): GraphHttpResponse {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            request.headers.forEach(connection::setRequestProperty)
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val responseHeaders = connection.headerFields
                .filterKeys { it != null }
                .mapValues { (_, values) -> values.orEmpty().joinToString(",") }
            return GraphHttpResponse(statusCode, body, responseHeaders)
        } finally {
            connection.disconnect()
        }
    }
}

sealed interface OneDriveFolderVerificationResult {
    data class Verified(val folderName: String) : OneDriveFolderVerificationResult
    data class Failed(
        val reason: Failure,
        val retryAfterSeconds: Long? = null,
    ) : OneDriveFolderVerificationResult

    enum class Failure {
        RECONNECT_REQUIRED,
        PERMISSION_DENIED,
        FOLDER_NOT_FOUND,
        RATE_LIMITED,
        INVALID_RESPONSE,
        NETWORK_UNAVAILABLE,
        SERVICE_FAILURE,
    }
}

fun interface OneDriveFolderVerifier {
    suspend fun verify(accessToken: String): OneDriveFolderVerificationResult
}

class MicrosoftGraphOneDriveFolderVerifier(
    private val transport: GraphHttpTransport = AndroidGraphHttpTransport(),
    private val relativePath: String = DEFAULT_RELATIVE_PATH,
) : OneDriveFolderVerifier {
    override suspend fun verify(accessToken: String): OneDriveFolderVerificationResult {
        val request = GraphHttpRequest(
            url = buildUrl(relativePath),
            headers = mapOf(
                "Authorization" to "Bearer $accessToken",
                "Accept" to "application/json",
            ),
        )
        val response = try {
            transport.get(request)
        } catch (_: Exception) {
            return OneDriveFolderVerificationResult.Failed(
                OneDriveFolderVerificationResult.Failure.NETWORK_UNAVAILABLE,
            )
        }
        return when (response.statusCode) {
            in 200..299 -> parseSuccess(response.body)
            401 -> failure(OneDriveFolderVerificationResult.Failure.RECONNECT_REQUIRED)
            403 -> failure(OneDriveFolderVerificationResult.Failure.PERMISSION_DENIED)
            404 -> failure(OneDriveFolderVerificationResult.Failure.FOLDER_NOT_FOUND)
            429 -> OneDriveFolderVerificationResult.Failed(
                OneDriveFolderVerificationResult.Failure.RATE_LIMITED,
                response.retryAfterSeconds(),
            )
            else -> failure(OneDriveFolderVerificationResult.Failure.SERVICE_FAILURE)
        }
    }

    private fun parseSuccess(body: String): OneDriveFolderVerificationResult {
        if (!FOLDER_PATTERN.containsMatchIn(body)) {
            return failure(OneDriveFolderVerificationResult.Failure.INVALID_RESPONSE)
        }
        val name = NAME_PATTERN.find(body)?.groupValues?.get(1)
            ?: return failure(OneDriveFolderVerificationResult.Failure.INVALID_RESPONSE)
        return OneDriveFolderVerificationResult.Verified(name)
    }

    private fun buildUrl(path: String): String {
        val encodedPath = path.split('/')
            .filter(String::isNotBlank)
            .joinToString("/") { segment ->
                URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
            }
        return "$GRAPH_ROOT/me/drive/root:/$encodedPath?\$select=id,name,folder,parentReference"
    }

    private fun failure(reason: OneDriveFolderVerificationResult.Failure) =
        OneDriveFolderVerificationResult.Failed(reason)

    private fun GraphHttpResponse.retryAfterSeconds(): Long? = headers.entries
        .firstOrNull { (name, _) -> name.equals("Retry-After", ignoreCase = true) }
        ?.value
        ?.trim()
        ?.toLongOrNull()

    companion object {
        const val DEFAULT_RELATIVE_PATH = "Documents/2_Others/Expenses_finance/logs"
        private const val GRAPH_ROOT = "https://graph.microsoft.com/v1.0"
        private val FOLDER_PATTERN = Regex("\"folder\"\\s*:\\s*\\{")
        private val NAME_PATTERN = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")
    }
}
