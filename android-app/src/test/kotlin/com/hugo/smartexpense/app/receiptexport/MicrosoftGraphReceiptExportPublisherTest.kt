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
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MicrosoftGraphReceiptExportPublisherTest {
    @Test fun boundDriveMismatchPreventsAnyUpload() = runTest {
        val requests = mutableListOf<GraphContentRequest>()
        val publisher = MicrosoftGraphReceiptExportPublisher(FakeAuthenticationClient()) {
            requests += it
            GraphContentResponse(200, "{\"id\":\"different-drive\"}")
        }
        assertEquals(ReceiptExportFailure.RECONNECT_REQUIRED, assertFailsWith<ReceiptExportException> {
            publisher.publish(sampleRecord().copy(exportAccountId = "id", exportDriveId = "original-drive"), byteArrayOf(1))
        }.failure)
        assertEquals(listOf("GET"), requests.map { it.method })
    }

    @Test fun matchingBoundDriveIsVerifiedBeforeImageOrJsonUpload() = runTest {
        val requests = mutableListOf<GraphContentRequest>()
        val publisher = MicrosoftGraphReceiptExportPublisher(FakeAuthenticationClient()) {
            requests += it
            GraphContentResponse(200, if (it.method == "GET") "{\"id\":\"drive\"}" else "")
        }
        publisher.publish(sampleTypedRecord().copy(exportAccountId = "id", exportDriveId = "drive"), null)
        assertEquals(listOf("GET", "PUT", "PATCH"), requests.map { it.method })
    }

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
    fun acceptsFinalNameConflictOnlyAfterVerifyingTheSamePayload() = runTest {
        val publisher = MicrosoftGraphReceiptExportPublisher(FakeAuthenticationClient()) { request ->
            when (request.method) {
                "POST", "PATCH" -> GraphContentResponse(409)
                "GET" -> GraphContentResponse(200, if (request.url.endsWith("/content")) sampleRecord().toHandoffJson() else "{\"folder\":{}}")
                else -> GraphContentResponse(200)
            }
        }

        publisher.publish(sampleRecord(), byteArrayOf(1))
    }

    @Test
    fun rejectsDifferentOrMissingPayloadOnFinalNameConflict() = runTest {
        for (response in listOf(GraphContentResponse(404), GraphContentResponse(200, sampleTypedRecord().toHandoffJson()))) {
            val publisher = MicrosoftGraphReceiptExportPublisher(FakeAuthenticationClient()) { request ->
                when (request.method) { "PATCH" -> GraphContentResponse(409); "GET" -> response; else -> GraphContentResponse(200) }
            }
            assertEquals(ReceiptExportFailure.PATH_CONFLICT,
                assertFailsWith<ReceiptExportException> { publisher.publish(sampleTypedRecord().copy(merchantName = "Other"), null) }.failure)
        }
    }

    @Test fun malformedExistingJsonNeverReconcilesAnExportConflict() = runTest {
        val expected = sampleTypedRecord().toHandoffJson()
        for (invalid in listOf(expected + " trailing", expected + "{}", expected.dropLast(1) + ",}",
            expected.replace("\"schemaVersion\"", "'schemaVersion'"))) {
            val publisher = MicrosoftGraphReceiptExportPublisher(FakeAuthenticationClient()) { request ->
                when (request.method) {
                    "PATCH" -> GraphContentResponse(409)
                    "GET" -> GraphContentResponse(200, invalid)
                    else -> GraphContentResponse(200)
                }
            }
            assertEquals(ReceiptExportFailure.PATH_CONFLICT,
                assertFailsWith<ReceiptExportException> { publisher.publish(sampleTypedRecord(), null) }.failure)
        }
    }

    @Test fun strictHandoffParserRejectsPermissiveJsonAndKeepsStructuralComparison() {
        val valid = """{"a":1,"b":[true,null,"value"]}"""
        assertTrue(handoffJsonEquivalent(valid, """ {"b":[true,null,"value"],"a":1.00} """))
        assertTrue(handoffJsonEquivalent("[{\"a\":1},2]", "[ {\"a\":1.0},2.0 ]"))
        for (invalid in listOf(valid + "x", valid + "{}", "{'a':1}", "{a:1}", "{\"a\":1,}",
            "{\"a\":[1,]}", "{\"a\":NaN}", "{\"a\":1,\"a\":1}", "{\"a\":/*comment*/1}",
            "[1,]", "null", "true", "[] garbage", "[".repeat(66) + "0" + "]".repeat(66),
            "{\"a\":\"" + "x".repeat(1_048_576) + "\"}")) {
            assertFalse(handoffJsonEquivalent(invalid, invalid), invalid.take(100))
            assertFails { parseHandoffJson(invalid) }
        }
    }

    @Test fun singletonAccountBindingRejectsChangedOrMissingAccountBeforeAnyUpload() = runTest {
        for (account in listOf("different-account", null)) {
            val authentication = FakeAuthenticationClient(account)
            val requests = mutableListOf<GraphContentRequest>()
            val publisher = MicrosoftGraphReceiptExportPublisher(authentication) { request ->
                requests += request; GraphContentResponse(200)
            }
            val error = assertFailsWith<ReceiptExportException> {
                publisher.publish(sampleRecord().copy(exportAccountId = "original-account"), byteArrayOf(1))
            }
            assertEquals(ReceiptExportFailure.RECONNECT_REQUIRED, error.failure)
            assertEquals(0, authentication.tokenCalls)
            assertTrue(requests.isEmpty())
        }
    }

    @Test fun singletonAccountBindingRechecksAccountAfterTokenAcquisition() = runTest {
        val authentication = FakeAuthenticationClient("original-account", "different-account")
        val publisher = MicrosoftGraphReceiptExportPublisher(authentication) { error("Account changed; no upload allowed") }
        assertEquals(ReceiptExportFailure.RECONNECT_REQUIRED, assertFailsWith<ReceiptExportException> {
            publisher.publish(sampleTypedRecord().copy(exportAccountId = "original-account"), null)
        }.failure)
        assertEquals(1, authentication.tokenCalls)
    }

    @Test fun batchAccountBindingRejectsChangedAndMixedAccountsBeforeUploadingImages() = runTest {
        val batch = ReceiptExportBatch("batch", sampleRecord().createdAt, listOf("first", "second"),
            "$HANDOFF_FOLDER/batch.json", "$HANDOFF_FOLDER/batch.json.uploading")
        for (bindings in listOf(listOf("original", "original"), listOf("current", "original"))) {
            val authentication = FakeAuthenticationClient("current")
            val requests = mutableListOf<GraphContentRequest>()
            val publisher = MicrosoftGraphReceiptExportPublisher(authentication) { request ->
                requests += request; GraphContentResponse(200)
            }
            val records = batch.expenseIds.mapIndexed { index, id ->
                sampleRecord().copy(expenseId = id, batchId = batch.batchId, jsonRelativePath = batch.jsonRelativePath,
                    exportAccountId = bindings[index]) to byteArrayOf(1)
            }
            assertEquals(ReceiptExportFailure.RECONNECT_REQUIRED, assertFailsWith<ReceiptExportException> {
                publisher.publishBatch(batch, records)
            }.failure)
            assertEquals(0, authentication.tokenCalls)
            assertTrue(requests.isEmpty())
        }
    }

    @Test fun boundMatchingAccountAndLegacyUnboundSnapshotsRemainPublishable() = runTest {
        for (binding in listOf("id", null)) {
            val requests = mutableListOf<GraphContentRequest>()
            val publisher = MicrosoftGraphReceiptExportPublisher(FakeAuthenticationClient()) { request ->
                requests += request; GraphContentResponse(200)
            }
            publisher.publish(sampleTypedRecord().copy(exportAccountId = binding), null)
            assertEquals(listOf("PUT", "PATCH"), requests.map { it.method })
        }
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

    private class FakeAuthenticationClient(
        private var accountId: String? = "id",
        private val accountAfterToken: String? = accountId,
    ) : GraphAuthenticationClient {
        var tokenCalls = 0
        override suspend fun loadCurrentAccount() = accountId?.let { GraphAccount(it, "User") }
        override suspend fun signIn(activity: Activity) = GraphAccount("id", "User")
        override suspend fun acquireAccessTokenSilently(): String {
            tokenCalls++
            accountId = accountAfterToken
            return "secret-token"
        }
        override suspend fun signOut() = Unit
    }
}

internal fun sampleTypedRecord() = sampleRecord().copy(
    schemaVersion = 3, sourceType = ReceiptSourceType.TYPED, extractionStatus = "manual",
    sourceImageUri = null, receiptImageRelativePath = null, normalizedImageLocalPath = null,
    notes = "  Retain exact notes\n  ",
)
