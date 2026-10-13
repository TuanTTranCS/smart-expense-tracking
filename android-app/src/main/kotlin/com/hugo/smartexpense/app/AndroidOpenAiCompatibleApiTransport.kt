package com.hugo.smartexpense.app

import com.hugo.smartexpense.extraction.OpenAiCompatibleApiRequest
import com.hugo.smartexpense.extraction.OpenAiCompatibleApiResponse
import com.hugo.smartexpense.extraction.OpenAiCompatibleApiTransport
import java.net.HttpURLConnection
import java.net.URL
import com.hugo.smartexpense.extraction.ApiProfileCategory
import com.hugo.smartexpense.extraction.ApiEndpointValidator
import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import com.hugo.smartexpense.extraction.InferenceExecution
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.InternalCoroutinesApi

class AndroidOpenAiCompatibleApiTransport(
    private val connectTimeoutMillis: Int = CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = READ_TIMEOUT_MILLIS,
    private val onConnection: (HttpURLConnection) -> Unit = {},
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : OpenAiCompatibleApiTransport {
    @OptIn(InternalCoroutinesApi::class)
    override fun send(request: OpenAiCompatibleApiRequest): OpenAiCompatibleApiResponse {
        val samePhone = request.category == ApiProfileCategory.SAME_PHONE_SERVER
        if (samePhone) {
            val base = ApiEndpointValidator.normalize(requireNotNull(request.approvedBaseUrl), request.category)
            require(request.url == "$base/chat/completions" || request.url == "$base/requests" ||
                Regex(Regex.escape(base) + "/requests/[0-9a-f-]{36}").matches(request.url)) { "Unapproved companion route." }
        }
        val deadline = request.cleanupDeadlineNanos ?: InferenceExecution.current.get()?.deadlineNanos
            ?: (System.nanoTime() + 180_000_000_000L)
        fun remaining(): Int {
            if (request.cleanupDeadlineNanos == null) InferenceExecution.current.get()?.job?.ensureActive()
            val millis = (deadline - System.nanoTime()) / 1_000_000
            if (millis <= 0) throw SocketTimeoutException("Request deadline exceeded.")
            return millis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        val connection = connectionFactory(URL(request.url))
        val deadlineExpired = AtomicBoolean(false)
        val watchdog = deadlineExecutor.schedule({
            deadlineExpired.set(true)
            connection.disconnect()
        }, remaining().toLong(), TimeUnit.MILLISECONDS)
        val job = if (request.cleanupDeadlineNanos == null) InferenceExecution.current.get()?.job else null
        val cancellation = job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) connection.disconnect()
        }
        try {
            job?.ensureActive()
            onConnection(connection)
            connection.requestMethod = request.method
            if (samePhone) connection.instanceFollowRedirects = false
            connection.connectTimeout = minOf(connectTimeoutMillis, remaining())
            connection.readTimeout = if (samePhone) remaining() else minOf(readTimeoutMillis, remaining())
            connection.doOutput = request.method == "POST"
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (connection.doOutput) {
                val bytes = request.body.toByteArray(Charsets.UTF_8)
                require(!samePhone || bytes.size <= 12 * 1024 * 1024) { "Request exceeds 12 MiB." }
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { output -> output.write(bytes) }
            }

            connection.readTimeout = if (samePhone) remaining() else minOf(readTimeoutMillis, remaining())
            val statusCode = connection.responseCode
            val responseStream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val responseBody = responseStream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    connection.readTimeout = if (samePhone) remaining() else minOf(readTimeoutMillis, remaining())
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 1_048_576) { "Response exceeds 1 MiB." }
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }.orEmpty()
            job?.ensureActive()
            if (deadlineExpired.get()) throw SocketTimeoutException("Request deadline exceeded.")
            remaining()
            return OpenAiCompatibleApiResponse(statusCode, responseBody,
                connection.headerFields.filterKeys { it != null }.mapValues { it.value.firstOrNull().orEmpty() })
        } finally {
            watchdog.cancel(false)
            cancellation?.dispose()
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 60_000
        val deadlineExecutor = Executors.newScheduledThreadPool(3) { task ->
            Thread(task, "inference-http-deadline").apply { isDaemon = true }
        }
    }
}
