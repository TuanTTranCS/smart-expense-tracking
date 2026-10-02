package com.hugo.smartexpense.app.tailscale

import com.hugo.smartexpense.extraction.*
import com.hugo.smartexpense.app.connectivity.ModelsEndpointChecker
import com.hugo.smartexpense.app.connectivity.ModelsEndpointResult
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
class SavedProfileReadinessProbeTest {
    @Test fun cancellationReturnsPromptlyWhileTheEndpointIsNotResponding() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/models") { exchange ->
            started.complete(Unit)
            release.await(5, TimeUnit.SECONDS)
            exchange.close()
        }
        server.start()
        try {
            val profile = ModelProfile("saved", "Saved", "http://127.0.0.1:${server.address.port}/v1", "model",
                RemoteInputMode.OCR_TEXT, RemoteStructuredOutputFormat.JSON_SCHEMA, "alias", 1, 1, true)
            val job = launch { SavedProfileReadinessProbe(ApiKeyStore { "test-key" }).test(profile, null) }
            withTimeout(5_000) { started.await() }
            val before = System.nanoTime()
            job.cancelAndJoin()
            assertTrue((System.nanoTime() - before) / 1_000_000 < 2_000, "Cancellation must not wait for the socket timeout")
        } finally {
            release.countDown()
            server.stop(0)
        }
    }

    @Test fun usesSavedEndpointAndCredentialAndSendsOnlyAModelsGet() = runBlocking {
        val requestBody = AtomicReference<String>()
        val authorization = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/models") { exchange ->
            assertEquals("GET", exchange.requestMethod)
            requestBody.set(exchange.requestBody.bufferedReader().use { it.readText() })
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            val response = """{"data":[]}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val profile = ModelProfile("saved", "Saved", "http://127.0.0.1:${server.address.port}/v1", "saved-model",
                RemoteInputMode.DIRECT_IMAGE, RemoteStructuredOutputFormat.JSON_SCHEMA, "saved-alias", 1, 1, true)
            val probe = SavedProfileReadinessProbe(ApiKeyStore { alias ->
                assertEquals("saved-alias", alias)
                "saved-secret"
            })
            assertEquals(ProviderTestResult.Success, withTimeout(5_000) { probe.test(profile, null) })
            assertEquals("Bearer saved-secret", authorization.get())
            assertEquals("", requestBody.get())
        } finally {
            server.stop(0)
        }
    }

    @Test fun adapterPassesCustomCredentialAndMapsReusableCheckerResult() = runBlocking {
        val profile = ModelProfile("saved", "Saved", "https://custom.example/api/v1", "model",
            RemoteInputMode.DIRECT_IMAGE, RemoteStructuredOutputFormat.JSON_SCHEMA, "alias", 1, 1, true)
        val probe = SavedProfileReadinessProbe(ApiKeyStore { fail("Override should be used") },
            ModelsEndpointChecker { input ->
                assertEquals(profile.baseUrl, input.baseUrl)
                assertEquals("override", input.apiKey)
                ModelsEndpointResult.Unavailable("Denied", 401)
            })
        assertEquals(ProviderTestResult.Failed("Denied"), probe.test(profile, "override"))
    }
}
