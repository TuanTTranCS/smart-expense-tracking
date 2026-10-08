package com.hugo.smartexpense.app.receiptexport

import android.app.Activity
import com.hugo.smartexpense.app.ReceiptSourceType
import kotlinx.coroutines.CancellationException
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

    @Test
    fun typedPublicationUsesOnlyTemporaryJsonAndFinalCommit() = runTest {
        val requests = mutableListOf<GraphContentRequest>()
        val publisher = MicrosoftGraphReceiptExportPublisher(FakeAuthenticationClient()) { request ->
            requests += request
            GraphContentResponse(200)
        }
        publisher.publish(sampleTypedRecord(), null)
        assertEquals(listOf("PUT", "PATCH"), requests.map { it.method })
        assertFalse(requests.any { it.url.contains("receipt_images") })
        assertTrue(requests.first().body.decodeToString().contains("\"schemaVersion\":3"))
    }

    @Test
    fun inconsistentTypedImageContractIsRejectedBeforeAnyNetworkRequest() = runTest {
        val publisher = MicrosoftGraphReceiptExportPublisher(FakeAuthenticationClient()) { error("unused") }
        assertFailsWith<IllegalArgumentException> { publisher.publish(sampleTypedRecord().copy(
            receiptImageRelativePath = "placeholder.jpg"), null) }
        assertFailsWith<IllegalArgumentException> { publisher.publish(sampleTypedRecord(), byteArrayOf(1)) }
    }

    @Test
    fun transportCancellationIsNotConvertedToNetworkFailure() = runTest {
        val publisher = MicrosoftGraphReceiptExportPublisher(FakeAuthenticationClient()) {
            throw CancellationException("cancelled")
        }
        assertFailsWith<CancellationException> { publisher.publish(sampleTypedRecord(), null) }
    }

    @Test
    fun mixedBatchPublishesAllImagesThenOneJsonArrayWithUnchangedObjectShapes() = runTest {
        val requests = mutableListOf<GraphContentRequest>()
        val publisher = MicrosoftGraphReceiptExportPublisher(FakeAuthenticationClient()) { request ->
            requests += request
            GraphContentResponse(200)
        }
        val batch = ReceiptExportBatch("batch-id", sampleRecord().createdAt, listOf("image-1", "typed-1", "image-2"), "$HANDOFF_FOLDER/expense_batch_20261007_140507_abcd1234.json", "$HANDOFF_FOLDER/expense_batch_20261007_140507_abcd1234.json.uploading")
        val records = listOf(sampleRecord().copy(expenseId = "image-1"), sampleTypedRecord().copy(expenseId = "typed-1"), sampleRecord().copy(expenseId = "image-2", receiptImageRelativePath = "$RECEIPT_IMAGE_FOLDER/2026-09/second.jpg")).map {
            it.copy(batchId = batch.batchId, jsonRelativePath = batch.jsonRelativePath, temporaryJsonRelativePath = batch.temporaryJsonRelativePath)
        }
        publisher.publishBatch(batch, records.map { it to if (it.sourceType == ReceiptSourceType.IMAGE) byteArrayOf(1) else null })
        assertEquals(listOf("POST", "POST", "PUT", "POST", "POST", "PUT", "PUT", "PATCH"), requests.map { it.method })
        val json = requests[6].body.decodeToString()
        assertEquals(records.joinToString(prefix = "[", postfix = "]") { it.toHandoffJson() }, json)
        assertTrue(json.contains("\"schemaVersion\":3"))
        assertTrue(json.contains("\"receiptImageRelativePath\":null"))
        assertTrue(requests[6].url.contains("expense_batch_"))
    }

    @Test
    fun laterBatchImageFailurePreventsAnyJsonPublication() = runTest {
        val requests = mutableListOf<GraphContentRequest>()
        val publisher = MicrosoftGraphReceiptExportPublisher(FakeAuthenticationClient()) { request ->
            requests += request
            if (request.url.contains("second.jpg")) GraphContentResponse(429, headers = mapOf("Retry-After" to "9")) else GraphContentResponse(200)
        }
        val batch = ReceiptExportBatch("batch", sampleRecord().createdAt, listOf("first", "second"), "$HANDOFF_FOLDER/batch.json", "$HANDOFF_FOLDER/batch.json.uploading")
        val records = listOf(sampleRecord().copy(expenseId = "first"), sampleRecord().copy(expenseId = "second", receiptImageRelativePath = "$RECEIPT_IMAGE_FOLDER/2026-09/second.jpg")).map { it.copy(batchId = batch.batchId, jsonRelativePath = batch.jsonRelativePath) to byteArrayOf(1) }
        val error = assertFailsWith<ReceiptExportException> { publisher.publishBatch(batch, records) }
        assertEquals(ReceiptExportFailure.RATE_LIMITED, error.failure)
        assertEquals(9, error.retryAfterSeconds)
        assertFalse(requests.any { it.url.contains("batch.json") })
    }

    private class FakeAuthenticationClient : GraphAuthenticationClient {
        override suspend fun loadCurrentAccount() = GraphAccount("id", "User")
        override suspend fun signIn(activity: Activity) = GraphAccount("id", "User")
        override suspend fun acquireAccessTokenSilently() = "secret-token"
        override suspend fun signOut() = Unit
    }
}

internal fun sampleTypedRecord() = sampleRecord().copy(
    schemaVersion = 3, sourceType = ReceiptSourceType.TYPED, extractionStatus = "manual",
    sourceImageUri = null, receiptImageRelativePath = null, normalizedImageLocalPath = null,
    notes = "  Retain exact notes\n  ",
)
