package com.hugo.smartexpense.app.receiptexport

import com.hugo.smartexpense.app.graphauth.GraphAuthenticationClient
import com.hugo.smartexpense.app.graphauth.GraphAuthenticationException
import com.hugo.smartexpense.app.ReceiptSourceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class GraphContentRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray = byteArrayOf(),
    val binaryResponse: Boolean = false,
    val maxResponseBytes: Int = 4 * 1024 * 1024,
)

data class GraphContentResponse(
    val statusCode: Int,
    val body: String = "",
    val headers: Map<String, String> = emptyMap(),
    val bodyBytes: ByteArray? = null,
)

fun interface GraphContentTransport {
    fun execute(request: GraphContentRequest): GraphContentResponse
}

class AndroidGraphContentTransport : GraphContentTransport {
    override fun execute(request: GraphContentRequest): GraphContentResponse {
        var current = request
        var response = executeOnce(current)
        if (!request.binaryResponse && request.method == "GET") repeat(3) {
            if (response.statusCode in listOf(301, 302, 303, 307, 308)) {
                val location = response.headers.entries.firstOrNull { it.key.equals("Location", true) }?.value
                    ?: return response
                val uri = java.net.URI(location)
                require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) { "Unsafe Graph redirect." }
                current = current.copy(url = location, headers = current.headers.filterKeys {
                    !it.equals("Authorization", true) && !it.equals("Cookie", true)
                })
                response = executeOnce(current)
            }
        }
        return response
    }

    private fun executeOnce(request: GraphContentRequest): GraphContentResponse {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
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
            val bytes = stream?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= request.maxResponseBytes) { "Graph response exceeds size limit." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: byteArrayOf()
            return GraphContentResponse(
                statusCode = status,
                body = if (request.binaryResponse) "" else bytes.toString(Charsets.UTF_8),
                bodyBytes = bytes.takeIf { request.binaryResponse },
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
    override suspend fun publish(record: ReceiptExportRecord, imageBytes: ByteArray?) {
        record.validateSourceContract()
        if (record.sourceType == ReceiptSourceType.IMAGE) require(imageBytes != null && imageBytes.isNotEmpty())
        else require(imageBytes == null)
        val token = accessToken(setOfNotNull(record.exportAccountId))
        verifyDrive(token, setOfNotNull(record.exportDriveId), setOfNotNull(record.exportAccountId))

        if (record.sourceType == ReceiptSourceType.IMAGE) {
            val imagePath = requireNotNull(record.receiptImageRelativePath)
            ensureFolder(token, "Documents/2_Others/Expenses_finance", "receipt_images")
            ensureFolder(token, RECEIPT_IMAGE_FOLDER, imagePath.substringAfter("receipt_images/").substringBefore('/'))
            put(token, imagePath, "image/jpeg", requireNotNull(imageBytes))
        }
        put(token, record.temporaryJsonRelativePath, "application/json", record.toHandoffJson().toByteArray())
        commitJson(token, record.temporaryJsonRelativePath, record.jsonRelativePath, record.toHandoffJson())
    }

    override suspend fun publishBatch(batch: ReceiptExportBatch, records: List<Pair<ReceiptExportRecord, ByteArray?>>) {
        require(records.map { it.first.expenseId } == batch.expenseIds)
        records.forEach { (record, bytes) ->
            record.validateSourceContract()
            require(record.batchId == batch.batchId && record.jsonRelativePath == batch.jsonRelativePath)
            if (record.sourceType == ReceiptSourceType.IMAGE) require(bytes != null && bytes.size in 1 until 200 * 1024)
            else require(bytes == null)
        }
        val token = accessToken(records.mapNotNull { it.first.exportAccountId }.toSet())
        verifyDrive(token, records.mapNotNull { it.first.exportDriveId }.toSet(), records.mapNotNull { it.first.exportAccountId }.toSet())
        for ((record, bytes) in records) {
            coroutineContext.ensureActive()
            if (record.sourceType == ReceiptSourceType.IMAGE) {
                val imagePath = requireNotNull(record.receiptImageRelativePath)
                ensureFolder(token, "Documents/2_Others/Expenses_finance", "receipt_images")
                ensureFolder(token, RECEIPT_IMAGE_FOLDER, imagePath.substringAfter("receipt_images/").substringBefore('/'))
                coroutineContext.ensureActive()
                put(token, imagePath, "image/jpeg", requireNotNull(bytes))
            }
        }
        coroutineContext.ensureActive()
        val json = records.joinToString(prefix = "[", postfix = "]") { it.first.toHandoffJson() }
        put(token, batch.temporaryJsonRelativePath, "application/json", json.toByteArray(Charsets.UTF_8))
        coroutineContext.ensureActive()
        commitJson(token, batch.temporaryJsonRelativePath, batch.jsonRelativePath, json)
    }

    suspend fun resolveCurrentDriveId(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val account = authenticationClient.loadCurrentAccount()?.id
            ?: throw ReceiptExportException(ReceiptExportFailure.RECONNECT_REQUIRED)
        val token = accessToken(setOf(account))
        val drive = readDrive(token)
        if (authenticationClient.loadCurrentAccount()?.id != account)
            throw ReceiptExportException(ReceiptExportFailure.RECONNECT_REQUIRED)
        drive
    }

    private suspend fun verifyDrive(token: String, expected: Set<String>, accounts: Set<String>) {
        if (expected.isEmpty()) return
        if (expected.size != 1 || readDrive(token) !in expected || accounts.size != 1 ||
            authenticationClient.loadCurrentAccount()?.id !in accounts)
            throw ReceiptExportException(ReceiptExportFailure.RECONNECT_REQUIRED)
        coroutineContext.ensureActive()
    }

    private fun readDrive(token: String): String {
        val response = execute(token, "GET", "$GRAPH_ROOT/me/drive?\$select=id", "application/json", byteArrayOf())
        if (response.statusCode !in 200..299) throw response.toExportException()
        return org.json.JSONObject(response.body).getString("id").also { require(it.isNotBlank()) }
    }

    private suspend fun accessToken(expectedAccountIds: Set<String>): String = try {
            // Legacy snapshots have no account binding and retain their existing recovery behavior.
            // New snapshots must never be retried into a different signed-in user's drive.
            if (expectedAccountIds.isNotEmpty()) {
                if (expectedAccountIds.size != 1 || authenticationClient.loadCurrentAccount()?.id !in expectedAccountIds)
                    throw ReceiptExportException(ReceiptExportFailure.RECONNECT_REQUIRED)
            }
            val token = authenticationClient.acquireAccessTokenSilently()
            if (expectedAccountIds.isNotEmpty() && authenticationClient.loadCurrentAccount()?.id !in expectedAccountIds)
                throw ReceiptExportException(ReceiptExportFailure.RECONNECT_REQUIRED)
            token
        } catch (_: GraphAuthenticationException.InteractionRequired) {
            throw ReceiptExportException(ReceiptExportFailure.RECONNECT_REQUIRED)
        } catch (_: GraphAuthenticationException.ConsentDeclined) {
            throw ReceiptExportException(ReceiptExportFailure.PERMISSION_DENIED)
        } catch (_: GraphAuthenticationException.NetworkUnavailable) {
            throw ReceiptExportException(ReceiptExportFailure.NETWORK_UNAVAILABLE)
        } catch (_: GraphAuthenticationException) {
            throw ReceiptExportException(ReceiptExportFailure.RECONNECT_REQUIRED)
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

    private fun commitJson(token: String, temporaryPath: String, finalPath: String, expectedJson: String) {
        val finalName = finalPath.substringAfterLast('/')
        val body = """{"name":"${finalName.jsonEscape()}","@microsoft.graph.conflictBehavior":"fail"}"""
        val response = execute(
            token, "PATCH", "$GRAPH_ROOT/me/drive/root:/${encodePath(temporaryPath)}:", "application/json", body.toByteArray(),
        )
        if (response.statusCode == 409) {
            // A deterministic name alone does not prove that our payload was committed.
            val existing = execute(token, "GET", "$GRAPH_ROOT/me/drive/root:/${encodePath(finalPath)}:/content",
                "application/json", byteArrayOf())
            if (existing.statusCode !in 200..299 || !handoffJsonEquivalent(existing.body, expectedJson)) {
                throw ReceiptExportException(ReceiptExportFailure.PATH_CONFLICT)
            }
        } else if (response.statusCode !in 200..299) throw response.toExportException()
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
    } catch (error: CancellationException) {
        throw error
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
