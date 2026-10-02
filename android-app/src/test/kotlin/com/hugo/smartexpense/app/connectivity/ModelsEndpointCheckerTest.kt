package com.hugo.smartexpense.app.connectivity

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
class ModelsEndpointCheckerTest {
    @Test fun supportsCustomBasePathCredentialsTimeoutsAndReturnsModelIds() = runBlocking {
        val method = AtomicReference<String>()
        val body = AtomicReference<String>()
        val auth = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/custom/catalog") { exchange ->
            method.set(exchange.requestMethod)
            body.set(exchange.requestBody.bufferedReader().use { it.readText() })
            auth.set(exchange.requestHeaders.getFirst("Authorization"))
            val response = """{"data":[{"id":"first"},{"id":"second"}]}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val input = ModelsEndpointInput("http://127.0.0.1:${server.address.port}/custom/", "secret", "catalog", 1_000, 2_000)
            assertEquals(ModelsEndpointResult.Reachable(listOf("first", "second")),
                withTimeout(5_000) { HttpModelsEndpointChecker().check(input) })
            assertEquals("GET", method.get())
            assertEquals("", body.get())
            assertEquals("Bearer secret", auth.get())
            assertFalse(input.toString().contains("secret"))
        } finally { server.stop(0) }
    }

    @Test fun rejectsHttpErrorsRedirectsAndInvalidModelListsWithoutExposingResponseBody() = runBlocking {
        val response = AtomicReference(200 to """{"data":[]}""")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/models") { exchange ->
            val (status, text) = response.get()
            val bytes = text.toByteArray()
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${server.address.port}/redirect-target")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/redirect-target") { fail("Redirects must not forward credentials") }
        server.start()
        try {
            val checker = HttpModelsEndpointChecker()
            val input = ModelsEndpointInput("http://127.0.0.1:${server.address.port}/v1", "secret")
            assertEquals(ModelsEndpointResult.Reachable(emptyList()), checker.check(input))
            for (status in listOf(401, 403, 404, 500, 302)) {
                response.set(status to "secret error body")
                val result = assertIs<ModelsEndpointResult.Unavailable>(checker.check(input))
                assertEquals(status, result.httpStatus)
                assertFalse(result.reason.contains("secret"))
            }
            for (body in listOf("<html>ok</html>", "{}", """{"data":[{"id":23}]}""", """{"data":[{"id":""}]}""")) {
                response.set(200 to body)
                assertIs<ModelsEndpointResult.Unavailable>(checker.check(input))
            }
            response.set(200 to " ".repeat(1_048_577))
            assertEquals("Models response is too large.", assertIs<ModelsEndpointResult.Unavailable>(checker.check(input)).reason)
        } finally { server.stop(0) }
    }

    @Test fun validatesCustomInputsBeforeNetworking() = runBlocking {
        val checker = HttpModelsEndpointChecker()
        for (input in listOf(
            ModelsEndpointInput("file:///private"), ModelsEndpointInput("https://user:secret@example.test/v1"),
            ModelsEndpointInput("https://example.test/v1?key=secret"),
            ModelsEndpointInput("https://example.test/v1", modelsPath = "https://other.test/models"),
            ModelsEndpointInput("https://example.test/v1", modelsPath = "../models"),
            ModelsEndpointInput("https://example.test/v1", connectTimeoutMillis = 0),
            ModelsEndpointInput("https://example.test/v1", apiKey = "secret\r\ninjected"),
        )) {
            assertEquals("Invalid models endpoint inputs.", assertIs<ModelsEndpointResult.Unavailable>(checker.check(input)).reason)
        }
    }

    @Test fun customReadTimeoutBoundsUnresponsiveEndpoint(): Unit = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/models") { exchange ->
            Thread.sleep(500)
            exchange.close()
        }
        server.start()
        try {
            assertIs<ModelsEndpointResult.Unavailable>(withTimeout(3_000) {
                HttpModelsEndpointChecker().check(ModelsEndpointInput(
                    "http://127.0.0.1:${server.address.port}/v1", readTimeoutMillis = 100,
                ))
            })
        } finally { server.stop(0) }
    }
}
