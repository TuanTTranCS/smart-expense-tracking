package com.hugo.smartexpense.app.receiptexport

import android.app.Activity
import com.hugo.smartexpense.app.graphauth.GraphAccount
import com.hugo.smartexpense.app.graphauth.GraphAuthenticationClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MicrosoftGraphReceiptExportPublisherTest {
    @Test
    fun createsFoldersThenUploadsImageBeforeTemporaryAndFinalJson() = runTest {
        val requests = mutableListOf<GraphContentRequest>()
        val publisher = MicrosoftGraphReceiptExportPublisher(FakeAuthenticationClient()) { request ->
            requests += request
            GraphContentResponse(if (request.method == "POST") 201 else 200)
        }

        publisher.publish(sampleRecord(), byteArrayOf(1, 2, 3))

        assertEquals(listOf("POST", "POST", "PUT", "PUT", "PATCH"), requests.map { it.method })
        assertTrue(requests[2].url.contains("receipt_images/2026-09"))
        assertTrue(requests[3].url.endsWith(".json.uploading:/content"))
        assertTrue(requests[4].url.endsWith(".json.uploading:"))
        assertTrue(requests[3].body.decodeToString().contains("\"schemaVersion\":2"))
        assertFalse(requests.take(3).any { it.url.contains("expense_20260906") })
    }

    @Test
    fun mapsRateLimitWithoutLeakingToken() = runTest {
        val publisher = MicrosoftGraphReceiptExportPublisher(FakeAuthenticationClient()) {
            GraphContentResponse(429, "Bearer secret-token", mapOf("retry-after" to "30"))
        }

        val error = assertFailsWith<ReceiptExportException> {
            publisher.publish(sampleRecord(), byteArrayOf(1))
        }

        assertEquals(ReceiptExportFailure.RATE_LIMITED, error.failure)
        assertEquals(30, error.retryAfterSeconds)
        assertFalse(error.message.orEmpty().contains("secret-token"))
    }

    @Test
    fun treatsFolderAndFinalNameConflictsAsIdempotentSuccess() = runTest {
        val publisher = MicrosoftGraphReceiptExportPublisher(FakeAuthenticationClient()) { request ->
            when (request.method) {
                "POST", "PATCH" -> GraphContentResponse(409)
                "GET" -> GraphContentResponse(200, "{\"folder\":{}}")
                else -> GraphContentResponse(200)
            }
        }

        publisher.publish(sampleRecord(), byteArrayOf(1))
    }

    @Test
    fun rejectsAFileThatConflictsWithARequiredFolder() = runTest {
        val publisher = MicrosoftGraphReceiptExportPublisher(FakeAuthenticationClient()) { request ->
            if (request.method == "POST") GraphContentResponse(409) else GraphContentResponse(200, "{\"file\":{}}")
        }

        val error = assertFailsWith<ReceiptExportException> {
            publisher.publish(sampleRecord(), byteArrayOf(1))
        }

        assertEquals(ReceiptExportFailure.PATH_CONFLICT, error.failure)
    }

    private class FakeAuthenticationClient : GraphAuthenticationClient {
        override suspend fun loadCurrentAccount() = GraphAccount("id", "User")
        override suspend fun signIn(activity: Activity) = GraphAccount("id", "User")
        override suspend fun acquireAccessTokenSilently() = "secret-token"
        override suspend fun signOut() = Unit
    }
}
