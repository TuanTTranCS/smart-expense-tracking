package com.hugo.smartexpense.app

import com.hugo.smartexpense.extraction.*
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.Test

class AndroidInferenceTransportTest {
    private class StalledConnection(url: URL, private val stallWrite: Boolean) : HttpURLConnection(url) {
        val disconnected = CountDownLatch(1)
        override fun disconnect() { disconnected.countDown() }
        override fun usingProxy() = false
        override fun connect() {}
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(value: Int) {}
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                if (stallWrite) {
                    check(disconnected.await(2, TimeUnit.SECONDS)) { "Absolute watchdog did not disconnect upload" }
                    throw IOException("Disconnected")
                }
            }
        }
        override fun getResponseCode(): Int {
            // Simulates time spent connecting before the blocking response-header read.
            Thread.sleep(60)
            check(disconnected.await(2, TimeUnit.SECONDS)) { "Absolute watchdog did not disconnect headers" }
            throw IOException("Disconnected")
        }
    }
    @Test fun cleanupAbsoluteDeadlineDisconnectsStalledHeadersAndUpload() {
        for (stallWrite in listOf(false, true)) {
            var connection: StalledConnection? = null
            val transport = AndroidOpenAiCompatibleApiTransport(connectionFactory = { url ->
                StalledConnection(url, stallWrite).also { connection = it }
            })
            val started = System.nanoTime()
            val request = OpenAiCompatibleApiRequest("http://127.0.0.1:8080/v1/requests",
                emptyMap(), "{}", category = ApiProfileCategory.SAME_PHONE_SERVER,
                approvedBaseUrl = "http://127.0.0.1:8080/v1", cleanupDeadlineNanos = started + 150_000_000)
            assertFailsWith<IOException> { transport.send(request) }
            assertEquals(0L, requireNotNull(connection).disconnected.count)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 1000, "Cleanup exceeded absolute budget")
        }
    }
    @Test fun samePhoneUsesRemainingDeadlineInsteadOfLegacyReadTimeoutAndRejectsRedirects() {
        var observed: HttpURLConnection? = null
        val transport = AndroidOpenAiCompatibleApiTransport(readTimeoutMillis = 10, connectionFactory = { url ->
            object : HttpURLConnection(url) {
                override fun disconnect() {}
                override fun usingProxy() = false
                override fun connect() {}
                override fun getResponseCode() = 302
            }.also { observed = it }
        })
        val response = transport.send(OpenAiCompatibleApiRequest("http://127.0.0.1:8080/v1/requests",
            emptyMap(), "", method = "GET", category = ApiProfileCategory.SAME_PHONE_SERVER,
            approvedBaseUrl = "http://127.0.0.1:8080/v1"))
        assertEquals(302, response.statusCode)
        assertFalse(requireNotNull(observed).instanceFollowRedirects)
        assertTrue(requireNotNull(observed).readTimeout > 60000)
    }
}
