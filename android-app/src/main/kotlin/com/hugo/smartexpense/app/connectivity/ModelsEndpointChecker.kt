package com.hugo.smartexpense.app.connectivity

import java.net.HttpURLConnection
import java.net.URI
import java.net.SocketTimeoutException
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject

/** Independent of saved profiles and Tailscale. Never log credentials. */
class ModelsEndpointInput(
    val baseUrl: String,
    val apiKey: String? = null,
    val modelsPath: String = "models",
    val connectTimeoutMillis: Int = 5_000,
    val readTimeoutMillis: Int = 5_000,
) {
    override fun toString(): String = "ModelsEndpointInput(credentials redacted)"
}

sealed interface ModelsEndpointResult {
    /** An empty list is reachable too; this does not verify model loading or inference. */
    data class Reachable(val modelIds: List<String>) : ModelsEndpointResult
    data class Unavailable(
        val reason: String,
        val httpStatus: Int? = null,
        val kind: ModelsEndpointFailure = ModelsEndpointFailure.NETWORK,
    ) : ModelsEndpointResult
}

enum class ModelsEndpointFailure { INVALID_INPUT, INVALID_LIST, TOO_LARGE, TIMEOUT, NETWORK, HTTP }

/** Shared discovery validation; returns only a credential-free endpoint safe for display. */
fun modelsEndpointUrl(input: ModelsEndpointInput): String? = runCatching {
    require(input.connectTimeoutMillis > 0 && input.readTimeoutMillis > 0)
    require(input.modelsPath.isNotBlank() && !input.modelsPath.startsWith("/"))
    val base = URI(input.baseUrl.trim().trimEnd('/') + "/")
    require(base.scheme in listOf("http", "https") && base.host != null)
    require(base.rawUserInfo == null && base.rawQuery == null && base.rawFragment == null)
    val resolved = base.resolve(input.modelsPath)
    require(resolved.scheme == base.scheme && resolved.authority == base.authority)
    require(resolved.path.startsWith(base.path) && resolved.rawFragment == null && resolved.rawQuery == null)
    require(input.apiKey?.any { it.code < 32 || it.code == 127 } != true)
    resolved.toURL().toString()
}.getOrNull()

fun interface ModelsEndpointChecker {
    suspend fun check(input: ModelsEndpointInput): ModelsEndpointResult
}

/** Cancellable, bounded GET of an OpenAI-compatible model list. No prompts or receipt input. */
class HttpModelsEndpointChecker : ModelsEndpointChecker {
    override suspend fun check(input: ModelsEndpointInput): ModelsEndpointResult {
        val url = modelsEndpointUrl(input)?.let { URI(it).toURL() }
            ?: return ModelsEndpointResult.Unavailable("Invalid models endpoint inputs.", kind = ModelsEndpointFailure.INVALID_INPUT)
        return suspendCancellableCoroutine { continuation ->
            val connection = AtomicReference<HttpURLConnection?>()
            val task = executor.submit {
                var opened: HttpURLConnection? = null
                val result = try {
                    opened = url.openConnection() as HttpURLConnection
                    connection.set(opened)
                    if (!continuation.isActive) return@submit opened.disconnect()
                    opened.requestMethod = "GET"
                    opened.instanceFollowRedirects = false
                    opened.connectTimeout = input.connectTimeoutMillis
                    opened.readTimeout = input.readTimeoutMillis
                    opened.setRequestProperty("Accept", "application/json")
                    input.apiKey?.takeIf { it.isNotBlank() }?.let {
                        opened.setRequestProperty("Authorization", "Bearer $it")
                    }
                    val status = opened.responseCode
                    if (status !in 200..299) {
                        ModelsEndpointResult.Unavailable(
                            if (status == 401 || status == 403) "Models endpoint denied access. Check credentials."
                            else "Models endpoint returned HTTP $status.", status, ModelsEndpointFailure.HTTP,
                        )
                    } else {
                        val body = opened.inputStream.use { stream ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8_192)
                            while (output.size() <= MAX_RESPONSE_BYTES) {
                                val count = stream.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES + 1 - output.size()))
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                        if (body.size > MAX_RESPONSE_BYTES) ModelsEndpointResult.Unavailable("Models response is too large.", kind = ModelsEndpointFailure.TOO_LARGE)
                        else parseModels(body.toString(Charsets.UTF_8))
                    }
                } catch (_: SocketTimeoutException) {
                    ModelsEndpointResult.Unavailable("Models endpoint timed out.", kind = ModelsEndpointFailure.TIMEOUT)
                } catch (_: Exception) {
                    ModelsEndpointResult.Unavailable("Models endpoint unavailable. Check the URL and network connection.")
                } finally {
                    connection.set(null)
                    opened?.disconnect()
                }
                continuation.resume(result)
            }
            continuation.invokeOnCancellation {
                connection.getAndSet(null)?.disconnect()
                task.cancel(true)
            }
        }
    }

    private fun parseModels(body: String): ModelsEndpointResult = try {
        val data = JSONObject(body).getJSONArray("data")
        val ids = (0 until data.length()).map { index ->
            val id = data.getJSONObject(index).get("id")
            require(id is String && id.isNotBlank())
            id
        }
        ModelsEndpointResult.Reachable(ids)
    } catch (_: Exception) {
        ModelsEndpointResult.Unavailable("Endpoint did not return a valid models list.", kind = ModelsEndpointFailure.INVALID_LIST)
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 1_048_576
        val executor = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "models-endpoint-check").apply { isDaemon = true }
        }
    }
}
