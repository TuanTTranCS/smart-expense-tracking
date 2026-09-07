package com.hugo.smartexpense.app.receiptexport

import com.hugo.smartexpense.app.graphauth.GraphAuthenticationClient
import com.hugo.smartexpense.app.graphauth.GraphAuthenticationException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class GraphContentRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray = byteArrayOf(),
)

data class GraphContentResponse(
    val statusCode: Int,
    val body: String = "",
    val headers: Map<String, String> = emptyMap(),
)

fun interface GraphContentTransport {
    fun execute(request: GraphContentRequest): GraphContentResponse
}

class AndroidGraphContentTransport : GraphContentTransport {
    override fun execute(request: GraphContentRequest): GraphContentResponse {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            request.headers.forEach(connection::setRequestProperty)
            if (request.body.isNotEmpty()) {
                connection.doOutput = true
                connection.outputStream.use { it.write(request.body) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            return GraphContentResponse(
                statusCode = status,
                body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty(),
                headers = connection.headerFields.filterKeys { it != null }
                    .mapValues { (_, values) -> values.orEmpty().joinToString(",") },
            )
        } finally {
            connection.disconnect()
        }
    }
}

class MicrosoftGraphReceiptExportPublisher(
    private val authenticationClient: GraphAuthenticationClient,
    private val transport: GraphContentTransport = AndroidGraphContentTransport(),
) : ReceiptExportPublisher {
    override suspend fun publish(record: ReceiptExportRecord, imageBytes: ByteArray) {
        val token = try {
            authenticationClient.acquireAccessTokenSilently()
        } catch (_: GraphAuthenticationException.InteractionRequired) {
            throw ReceiptExportException(ReceiptExportFailure.RECONNECT_REQUIRED)
        } catch (_: GraphAuthenticationException.ConsentDeclined) {
            throw ReceiptExportException(ReceiptExportFailure.PERMISSION_DENIED)
        } catch (_: GraphAuthenticationException.NetworkUnavailable) {
            throw ReceiptExportException(ReceiptExportFailure.NETWORK_UNAVAILABLE)
        } catch (_: GraphAuthenticationException) {
            throw ReceiptExportException(ReceiptExportFailure.RECONNECT_REQUIRED)
        }

        ensureFolder(token, "Documents/2_Others/Expenses_finance", "receipt_images")
        ensureFolder(token, RECEIPT_IMAGE_FOLDER, record.receiptImageRelativePath.substringAfter("receipt_images/").substringBefore('/'))
        put(token, record.receiptImageRelativePath, "image/jpeg", imageBytes)
        put(token, record.temporaryJsonRelativePath, "application/json", record.toVersion2Json().toByteArray())
        commitJson(token, record.temporaryJsonRelativePath, record.jsonRelativePath.substringAfterLast('/'))
    }

    private fun ensureFolder(token: String, parentPath: String, name: String) {
        val body = """{"name":"${name.jsonEscape()}","folder":{},"@microsoft.graph.conflictBehavior":"fail"}"""
        val response = execute(
            token = token,
            method = "POST",
            url = "$GRAPH_ROOT/me/drive/root:/${encodePath(parentPath)}:/children",
            contentType = "application/json",
            body = body.toByteArray(),
        )
        if (response.statusCode == 409) {
            val existing = execute(
                token = token,
                method = "GET",
                url = "$GRAPH_ROOT/me/drive/root:/${encodePath("$parentPath/$name")}:?\$select=id,folder",
                contentType = "application/json",
                body = byteArrayOf(),
            )
            if (existing.statusCode !in 200..299 || !Regex("\"folder\"\\s*:\\s*\\{").containsMatchIn(existing.body)) {
                throw ReceiptExportException(ReceiptExportFailure.PATH_CONFLICT)
            }
        } else if (response.statusCode !in 200..299) {
            throw response.toExportException()
        }
    }

    private fun put(token: String, path: String, contentType: String, bytes: ByteArray) {
        val response = execute(
            token, "PUT", "$GRAPH_ROOT/me/drive/root:/${encodePath(path)}:/content", contentType, bytes,
        )
        if (response.statusCode !in 200..299) throw response.toExportException()
    }

    private fun commitJson(token: String, temporaryPath: String, finalName: String) {
        val body = """{"name":"${finalName.jsonEscape()}","@microsoft.graph.conflictBehavior":"replace"}"""
        val response = execute(
            token, "PATCH", "$GRAPH_ROOT/me/drive/root:/${encodePath(temporaryPath)}:", "application/json", body.toByteArray(),
        )
        // A conflict means the deterministic final commit marker already exists from an
        // earlier attempt whose local success update was interrupted.
        if (response.statusCode !in 200..299 && response.statusCode != 409) throw response.toExportException()
    }

    private fun execute(
        token: String,
        method: String,
        url: String,
        contentType: String,
        body: ByteArray,
    ): GraphContentResponse = try {
        transport.execute(
            GraphContentRequest(
                method, url,
                mapOf("Authorization" to "Bearer $token", "Accept" to "application/json", "Content-Type" to contentType),
                body,
            ),
        )
    } catch (_: Exception) {
        throw ReceiptExportException(ReceiptExportFailure.NETWORK_UNAVAILABLE)
    }

    private fun GraphContentResponse.toExportException(): ReceiptExportException = ReceiptExportException(
        failure = when (statusCode) {
            401 -> ReceiptExportFailure.RECONNECT_REQUIRED
            403 -> ReceiptExportFailure.PERMISSION_DENIED
            409 -> ReceiptExportFailure.PATH_CONFLICT
            429 -> ReceiptExportFailure.RATE_LIMITED
            507 -> ReceiptExportFailure.INSUFFICIENT_STORAGE
            else -> ReceiptExportFailure.SERVICE_FAILURE
        },
        retryAfterSeconds = if (statusCode == 429) headers.entries
            .firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
            ?.value?.trim()?.toLongOrNull() else null,
    )

    private fun encodePath(path: String): String = path.split('/').filter(String::isNotBlank).joinToString("/") {
        URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20")
    }

    private fun String.jsonEscape() = replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object { const val GRAPH_ROOT = "https://graph.microsoft.com/v1.0" }
}
