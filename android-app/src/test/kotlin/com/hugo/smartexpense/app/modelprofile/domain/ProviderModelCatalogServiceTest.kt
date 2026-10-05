package com.hugo.smartexpense.app.modelprofile.domain

import com.hugo.smartexpense.app.connectivity.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
class ProviderModelCatalogServiceTest {
    @Test fun normalizesExactIdsAndKeepsEmptyReachable() = runTest {
        val result = AtomicReference<ModelsEndpointResult>(ModelsEndpointResult.Reachable(listOf("z:latest", "Org/Model-V2", "z:latest", "org/model-v2")))
        val service = CompatibleProviderModelCatalogService { result.get() }
        assertEquals(listOf("Org/Model-V2", "org/model-v2", "z:latest"), assertIs<ProviderModelCatalogResult.Loaded>(service.load("https://example.test/api/v1/", null)).models.map { it.id })
        result.set(ModelsEndpointResult.Reachable(emptyList()))
        assertEquals(emptyList(), assertIs<ProviderModelCatalogResult.Loaded>(service.load("https://example.test/v1", null)).models)
    }

    @Test fun validatesOnlyDiscoveryInputsAndNeverLeaksFailures() = runTest {
        var calls = 0
        val service = CompatibleProviderModelCatalogService { calls++; error("secret provider error") }
        for ((url, key) in listOf(
            "https://user:secret@example.test/v1" to null,
            "https://example.test/v1?key=secret" to null,
            "https://example.test/v1#secret" to null,
            "file:///secret" to null,
            "https://example.test/v1" to "secret\r\nheader",
            "https://example.test/v1" to "secret\u0000header",
        )) {
            val failure = assertIs<ProviderModelCatalogResult.Failed>(service.load(url, key))
            assertEquals(CatalogFailure.INVALID_INPUT, failure.kind)
            assertFalse(failure.toString().contains("secret"))
        }
        assertEquals(0, calls)
        assertEquals(CatalogFailure.NETWORK, assertIs<ProviderModelCatalogResult.Failed>(service.load("https://example.test/v1", null)).kind)
        assertEquals(1, calls)
    }

    @Test fun wholeRequestDeadlineAndCallerCancellation() = runTest {
        var cancellations = 0
        val service = CompatibleProviderModelCatalogService {
            try { delay(20_000); ModelsEndpointResult.Reachable(emptyList()) }
            finally { cancellations++ }
        }
        assertEquals(CatalogFailure.TIMEOUT, assertIs<ProviderModelCatalogResult.Failed>(service.load("https://example.test/v1", null)).kind)
        assertEquals(1, cancellations)
        assertFailsWith<CancellationException> { withTimeout(100) { service.load("https://example.test/v1", null) } }
        assertEquals(2, cancellations)
    }

    @Test fun mapsHttpAndParserFailuresToSafeRecovery() = runBlocking {
        val response = AtomicReference(200 to """{"data":[]}""")
        val requestedPath = AtomicReference<String>()
        val authorization = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/models") { exchange ->
            requestedPath.set(exchange.requestURI.path)
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            assertEquals("GET", exchange.requestMethod)
            assertEquals("", exchange.requestBody.bufferedReader().use { it.readText() })
            val (status, body) = response.get()
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Location", "/never-follow")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val service = CompatibleProviderModelCatalogService()
            val prefix = "http://127.0.0.1:${server.address.port}/api/v1/"
            for (body in listOf(
                """{"data":[{"id":"google/gemma-4-e2b","object":"model","owned_by":"lmstudio"}]}""",
                """{"data":[{"id":"google/gemma-4-e2b","name":"Gemma","architecture":{"input_modalities":["image","text"]},"supported_parameters":["response_format"]}]}""",
            )) {
                response.set(200 to body)
                assertEquals(listOf(AvailableProviderModel("google/gemma-4-e2b")), assertIs<ProviderModelCatalogResult.Loaded>(service.load(prefix, "secret")).models)
                assertEquals("Bearer secret", authorization.get())
                assertEquals("/api/v1/models", requestedPath.get())
            }
            service.load(prefix, null)
            assertNull(authorization.get())
            for ((status, expected) in listOf(401 to CatalogFailure.ACCESS_DENIED, 403 to CatalogFailure.ACCESS_DENIED,
                404 to CatalogFailure.UNSUPPORTED, 429 to CatalogFailure.RATE_LIMITED, 500 to CatalogFailure.SERVER,
                503 to CatalogFailure.SERVER, 302 to CatalogFailure.REDIRECT)) {
                response.set(status to "secret response body")
                val failed = assertIs<ProviderModelCatalogResult.Failed>(service.load(prefix, "secret"))
                assertEquals(expected, failed.kind)
                assertFalse(failed.toString().contains("secret"))
            }
            for (body in listOf("invalid JSON", "{}", """{"data":{}}""", """{"data":[{}]}""", """{"data":[{"id":23}]}""", """{"data":[{"id":" "}]}""")) {
                response.set(200 to body)
                assertEquals(CatalogFailure.INVALID_LIST, assertIs<ProviderModelCatalogResult.Failed>(service.load(prefix, null)).kind)
            }
            response.set(200 to " ".repeat(1_048_577))
            assertEquals(CatalogFailure.TOO_LARGE, assertIs<ProviderModelCatalogResult.Failed>(service.load(prefix, null)).kind)
        } finally { server.stop(0) }
    }
}
