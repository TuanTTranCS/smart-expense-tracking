package com.hugo.smartexpense.app

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hugo.smartexpense.app.connectivity.*
import com.hugo.smartexpense.extraction.*
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android loopback HTTP against a controlled contract adapter; no native model claim. */
@RunWith(AndroidJUnit4::class)
class SamePhoneHttpIntegrationTest {
    private data class Call(val method: String, val path: String, val headers: Map<String, String>, val body: String)
    private class Server(val prefix: String = "/expense/v1", port: Int = 0, val key: String? = null) : AutoCloseable {
        val socket = ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"))
        val base = "http://127.0.0.1:${socket.localPort}$prefix"
        val calls = CopyOnWriteArrayList<Call>()
        val failures = CopyOnWriteArrayList<Throwable>()
        val instance = UUID.randomUUID().toString()
        @Volatile var delayMillis = 0L
        @Volatile var redirect = false
        @Volatile var responseContent = "{\"status\":\"ok\"}"
        @Volatile var hugeResponse = false
        private val workers = Executors.newCachedThreadPool()
        init {
            workers.submit {
                while (!socket.isClosed) try {
                    val accepted = socket.accept()
                    workers.submit { accepted.use { handle(it) } }
                } catch (e: Exception) { if (!socket.isClosed) failures += e }
            }
        }
        private fun line(s: Socket): String {
            val out = ByteArrayOutputStream()
            while (true) { val b = s.getInputStream().read(); require(b >= 0); if (b == 10) break; if (b != 13) out.write(b); require(out.size() < 16384) }
            return out.toString("UTF-8")
        }
        private fun handle(s: Socket) {
            try {
                s.soTimeout = 10000
                val start = line(s).split(' ')
                val headers = linkedMapOf<String, String>()
                while (true) { val l = line(s); if (l.isEmpty()) break; headers[l.substringBefore(':').lowercase()] = l.substringAfter(':').trim() }
                val data = ByteArray(headers["content-length"]?.toInt() ?: 0)
                java.io.DataInputStream(s.getInputStream()).readFully(data)
                val call = Call(start[0], start[1], headers, String(data, Charsets.UTF_8)); calls += call
                if (key != null && headers["authorization"] != "Bearer $key") {
                    reply(s, 401, "{\"error\":{\"code\":\"unauthorized\"}}", emptyMap()); return
                }
                when {
                    call.path == "$prefix/models" -> reply(s, 200, "{\"data\":[{\"id\":\"vision\"}]}", emptyMap())
                    call.path == "$prefix/requests" -> reply(s, 201, JSONObject().put("instanceId", instance)
                        .put("requestId", UUID.randomUUID().toString()).put("expiresInMillis", 180000).put("runtimeState", "available").toString(), emptyMap())
                    call.path.startsWith("$prefix/requests/") -> reply(s, 200, JSONObject().put("instanceId", instance)
                        .put("requestId", call.path.substringAfterLast('/')).put("state", "drained").toString(), emptyMap())
                    call.path == "$prefix/chat/completions" -> {
                        val body = JSONObject(call.body)
                        check(!body.has("response_format"))
                        check(headers["x-companion-instance"] == instance && !headers["x-request-id"].isNullOrBlank())
                        if (redirect) reply(s, 302, "", mapOf("Location" to "$base/redirected")) else {
                            Thread.sleep(delayMillis)
                            val envelope = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message",
                                JSONObject().put("role", "assistant").put("content", responseContent)))).toString()
                            reply(s, 200, if (hugeResponse) " ".repeat(1_048_577) else envelope,
                                mapOf("X-Companion-Instance" to instance, "X-Request-Id" to requireNotNull(headers["x-request-id"])))
                        }
                    }
                    else -> error("Unexpected route ${call.path}")
                }
            } catch (e: Exception) {
                // Cancellation deliberately closes the chat socket before the controlled reply.
                if (e !is java.io.IOException && e !is InterruptedException) failures += e
            }
        }
        private fun reply(s: Socket, status: Int, body: String, headers: Map<String, String>) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 $status OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n" +
                headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n"
            s.getOutputStream().write(head.toByteArray()); s.getOutputStream().write(bytes); s.getOutputStream().flush()
        }
        override fun close() { socket.close(); workers.shutdownNow() }
    }
    private fun client(server: Server, key: String? = null, transport: OpenAiCompatibleApiTransport = AndroidOpenAiCompatibleApiTransport()) =
        OpenAiCompatibleReceiptModelClient(OpenAiCompatibleProviderOption("phone", "Same-phone", server.base, "vision",
            RemoteInputMode.DIRECT_IMAGE, "alias", RemoteStructuredOutputFormat.PROMPT_ONLY_JSON,
            category = ApiProfileCategory.SAME_PHONE_SERVER), ApiKeyStore { key }, transport)
    private suspend fun <T> operation(key: String? = null, deadline: Long = 180000, action: () -> T): T {
        var value: Result<T>? = null
        BatchRequestScheduler().run(listOf(Unit), BatchProcessingPolicy(itemDeadlineMillis = deadline),
            category = ProviderCategory.SAME_PHONE_SERVER, credentials = OperationCredentialSnapshot(mapOf("alias" to key)),
            operation = { withContext(Dispatchers.IO) { action() } }, onResult = { _, result -> value = result })
        return requireNotNull(value).getOrThrow()
    }
    @Test fun discoveryAndVerificationUseDefaultAndCustomEndpointsWithOptionalAuth() = runBlocking {
        for ((prefix, port, key) in listOf(Triple("/v1", 8080, null), Triple("/expense/v1", 0, "device-test-key"))) {
            Server(prefix, port, key).use { server ->
                assertEquals(ModelsEndpointResult.Reachable(listOf("vision")), HttpModelsEndpointChecker().check(
                    ModelsEndpointInput(server.base, key, category = ApiProfileCategory.SAME_PHONE_SERVER)))
                assertEquals(ProviderTestResult.Success, operation(key) { client(server, key).testConfiguration() })
                assertEquals(listOf("GET", "POST", "POST"), server.calls.map { it.method })
                server.calls.forEach { assertEquals(key?.let { "Bearer $it" }, it.headers["authorization"]) }
                assertTrue(server.failures.toString(), server.failures.isEmpty())
            }
        }
    }
    @Test fun missingKeyIsActionableWithoutChatAndMatchingKeyRecovers() = runBlocking {
        Server(key = "device-test-key").use { server ->
            val failure = operation { client(server).testConfiguration() }
            assertTrue(failure is ProviderTestResult.Failed)
            assertTrue((failure as ProviderTestResult.Failed).reason.contains("keys"))
            assertEquals(1, server.calls.size)
            assertEquals(ProviderTestResult.Success, operation("device-test-key") { client(server, "device-test-key").testConfiguration() })
        }
    }
    @Test fun typedAndPhotoRequestsPreserveSourcesAndStrictMultiReceiptValidation() = runBlocking {
        Server().use { server ->
            val receipt = "{\"receiptDate\":\"2026-10-11\",\"merchantName\":\"Shop\",\"totalAmount\":10,\"currency\":\"CAD\",\"extractionStatus\":\"confirmed\",\"confidence\":1,\"merchantLocation\":null,\"issues\":[],\"suggestedCorrections\":[]}"
            server.responseContent = "{\"receipts\":[$receipt]}"
            val draft = TypedExpenseDraft(notes = "original narrative 15+50 CAD")
            val typed = operation { ReceiptExtractionPipeline(client(server)).reviewTypedExpense(draft) }
            assertTrue(typed is ReceiptExtractionPipelineResult.Success)
            assertEquals(draft.notes, (typed as ReceiptExtractionPipelineResult.Success).result.notes)
            val typedContent = JSONObject(server.calls.last().body).getJSONArray("messages").getJSONObject(0).get("content")
            assertTrue(typedContent is String && typedContent.contains(draft.notes))
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray(); bitmap.recycle()
            server.responseContent = "{\"receipts\":[$receipt,$receipt]}"
            val photo = operation { ReceiptExtractionPipeline(client(server)).extract(ReceiptImage("receipt.png", bytes, "image/png")) }
            assertEquals(2, (photo as ReceiptExtractionPipelineResult.Success).results.size)
            val dataUrl = JSONObject(server.calls.last().body).getJSONArray("messages").getJSONObject(0).getJSONArray("content")
                .getJSONObject(1).getJSONObject("image_url").getString("url")
            assertArrayEquals(bytes, java.util.Base64.getDecoder().decode(dataUrl.substringAfter(',')))
            server.responseContent = "prose, not JSON"
            assertTrue(operation { ReceiptExtractionPipeline(client(server)).extract(ReceiptImage("receipt.png", bytes, "image/png")) } is ReceiptExtractionPipelineResult.Failed)
        }
    }
    @Test fun redirectsNeverReachTargetAndControlDrainPrecedesNextInference() = runBlocking {
        Server(key = "device-test-key").use { server ->
            server.redirect = true
            assertTrue(operation("device-test-key") { client(server, "device-test-key").testConfiguration() } is ProviderTestResult.Failed)
            assertEquals(listOf("POST", "POST", "DELETE"), server.calls.map { it.method })
            assertFalse(server.calls.any { it.path.endsWith("redirected") })
            server.redirect = false
            assertEquals(ProviderTestResult.Success, operation("device-test-key") { client(server, "device-test-key").testConfiguration() })
            assertTrue(server.failures.toString(), server.failures.isEmpty())
        }
    }
    @Test fun deadlineDisconnectsSocketAndAcknowledgesCleanupBeforeRetry() = runBlocking {
        Server().use { server ->
            server.delayMillis = 2000
            val failed = runCatching { operation(deadline = 150) { client(server).extractFromReceiptText("expense") } }
            assertTrue(failed.isFailure)
            assertTrue(server.calls.any { it.method == "DELETE" })
            server.delayMillis = 0
            assertEquals(ProviderTestResult.Success, operation { client(server).testConfiguration() })
        }
    }
    @Test fun validResponseBeyondOldSixtySecondCutoffUsesOriginalDeadline() = runBlocking {
        Server().use { server ->
            server.delayMillis = 61000
            assertEquals(ProviderTestResult.Success, operation { client(server).testConfiguration() })
        }
    }
    @Test fun responseLargerThanOneMiBIsRejectedAndDrained() = runBlocking {
        Server().use { server ->
            server.hugeResponse = true
            assertTrue(runCatching { operation { client(server).extractFromReceiptText("expense") } }.isFailure)
            assertTrue(server.calls.any { it.method == "DELETE" })
        }
    }
}
