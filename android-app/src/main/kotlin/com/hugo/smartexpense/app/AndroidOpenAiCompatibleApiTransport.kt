package com.hugo.smartexpense.app

import com.hugo.smartexpense.extraction.OpenAiCompatibleApiRequest
import com.hugo.smartexpense.extraction.OpenAiCompatibleApiResponse
import com.hugo.smartexpense.extraction.OpenAiCompatibleApiTransport
import java.net.HttpURLConnection
import java.net.URL

class AndroidOpenAiCompatibleApiTransport : OpenAiCompatibleApiTransport {
    override fun send(request: OpenAiCompatibleApiRequest): OpenAiCompatibleApiResponse {
        val connection = (URL(request.url).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.doOutput = true
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.outputStream.use { output -> output.write(request.body.toByteArray(Charsets.UTF_8)) }

            val statusCode = connection.responseCode
            val responseStream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val responseBody = responseStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return OpenAiCompatibleApiResponse(statusCode, responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 60_000
    }
}
