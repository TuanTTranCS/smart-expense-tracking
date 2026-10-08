package com.hugo.smartexpense.app

import com.hugo.smartexpense.extraction.OpenAiCompatibleApiRequest
import com.hugo.smartexpense.extraction.OpenAiCompatibleApiResponse
import com.hugo.smartexpense.extraction.OpenAiCompatibleApiTransport
import java.net.HttpURLConnection
import java.net.URL
import com.hugo.smartexpense.extraction.InferenceExecution
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.InternalCoroutinesApi

class AndroidOpenAiCompatibleApiTransport(
    private val connectTimeoutMillis: Int = CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = READ_TIMEOUT_MILLIS,
    private val onConnection: (HttpURLConnection) -> Unit = {},
) : OpenAiCompatibleApiTransport {
    @OptIn(InternalCoroutinesApi::class)
    override fun send(request: OpenAiCompatibleApiRequest): OpenAiCompatibleApiResponse {
        val connection = (URL(request.url).openConnection() as HttpURLConnection)
        val job = InferenceExecution.current.get()?.job
        val cancellation = job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) connection.disconnect()
        }
        try {
            job?.ensureActive()
            onConnection(connection)
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.doOutput = true
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.outputStream.use { output -> output.write(request.body.toByteArray(Charsets.UTF_8)) }

            val statusCode = connection.responseCode
            val responseStream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val responseBody = responseStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            job?.ensureActive()
            return OpenAiCompatibleApiResponse(statusCode, responseBody,
                connection.headerFields.filterKeys { it != null }.mapValues { it.value.firstOrNull().orEmpty() })
        } finally {
            cancellation?.dispose()
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 60_000
    }
}
