package com.hugo.smartexpense.app.receiptexport

import com.sun.net.httpserver.HttpServer
import org.junit.Test
import java.net.InetSocketAddress
import kotlin.test.*

class AndroidGraphContentTransportTest {
    private fun withServer(action: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/bytes") { exchange ->
            val body = byteArrayOf(0, 1, -1, 127, 0)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/json") { exchange ->
            val body = "{\"id\":\"drive\"}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${server.address.port}/bytes")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.start()
        try { action("http://127.0.0.1:${server.address.port}") } finally { server.stop(0) }
    }
    @Test fun binaryTransportPreservesBytesAndBoundsResponses() = withServer { root ->
        val transport = AndroidGraphContentTransport()
        val response = transport.execute(GraphContentRequest("GET", "$root/bytes", emptyMap(), binaryResponse = true, maxResponseBytes = 5))
        assertContentEquals(byteArrayOf(0, 1, -1, 127, 0), response.bodyBytes)
        assertEquals("", response.body)
        assertFailsWith<IllegalArgumentException> {
            transport.execute(GraphContentRequest("GET", "$root/bytes", emptyMap(), binaryResponse = true, maxResponseBytes = 4))
        }
    }
    @Test fun existingJsonResponsesRemainStringsAndBinaryRedirectRemainsExplicit() = withServer { root ->
        val transport = AndroidGraphContentTransport()
        assertEquals("{\"id\":\"drive\"}", transport.execute(GraphContentRequest("GET", "$root/json", emptyMap())).body)
        assertEquals(302, transport.execute(GraphContentRequest("GET", "$root/redirect", emptyMap(), binaryResponse = true)).statusCode)
        assertFailsWith<IllegalArgumentException> {
            transport.execute(GraphContentRequest("GET", "$root/redirect", mapOf("Authorization" to "Bearer private")))
        }
    }
}
