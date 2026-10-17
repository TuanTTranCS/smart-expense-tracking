package com.hugo.smartexpense.app.receiptexport

import android.app.Activity
import com.hugo.smartexpense.app.graphauth.GraphAccount
import com.hugo.smartexpense.app.graphauth.GraphAuthenticationClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.URLDecoder
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
class ReceiptCorrectionRemoteTest {
    @Test fun resolvesPendingAndProcessedSingletonsSeparatelyFromPublication() = runTest {
        val f = Fixture()
        val pending = f.remote.inspect(f.record)
        assertEquals(ReceiptRemoteStatus.PENDING, pending.status)
        assertTrue(pending.editable)
        assertEquals(receiptPayloadSha256(pending.payloadJson!!), pending.payloadFingerprint)
        f.moveTo("receipt_jsons_done")
        val done = f.remote.inspect(f.record)
        assertEquals(ReceiptRemoteStatus.PROCESSED, done.status)
        assertEquals(0, done.revision)
        assertTrue(done.editable)
    }
    @Test fun capabilityGateAndMissingFilesNeverProveProcessing() = runTest {
        val f = Fixture()
        f.files.remove(f.capability)
        assertEquals(ReceiptRemoteStatus.PENDING, f.remote.inspect(f.record).status)
        assertFalse(f.remote.inspect(f.record).editable)
        f.files.remove(f.path)
        f.files["$HANDOFF_FOLDER/receipt_batches_expanded/batch.json"] = "[]"
        assertEquals(ReceiptRemoteStatus.MISSING, f.remote.inspect(f.record).status)
    }
    @Test fun observesUnexpandedBatchButRejectsPartialExpansionAndConflictingFolders() = runTest {
        val f = Fixture()
        val batchPath = "$HANDOFF_FOLDER/expense_batch_20260906_140507_aaaaaaaa.json"
        val record = f.record.copy(batchId = "batch", jsonRelativePath = batchPath)
        val original = f.files.remove(f.path)!!
        f.files[batchPath] = "[$original,${JSONObject(original).put("expenseId", "other")}]"
        assertEquals(ReceiptRemoteStatus.PENDING, f.remote.inspect(record).status)
        f.files[f.path] = original
        assertFalse(f.remote.inspect(record).editable)
        f.files.remove(batchPath)
        f.files["$HANDOFF_FOLDER/receipt_jsons_done/${f.path.substringAfterLast('/')}"] = original
        assertFalse(f.remote.inspect(record).editable)
    }
    @Test fun reviewAndErrorRemainIneligibleAndAuthenticationFailuresRemainUnavailable() = runTest {
        for ((folder, status) in listOf("receipt_jsons_review" to ReceiptRemoteStatus.NEEDS_REVIEW,
            "receipt_jsons_error" to ReceiptRemoteStatus.ERROR)) {
            val f = Fixture(); f.moveTo(folder)
            assertEquals(status, f.remote.inspect(f.record).status)
            assertFalse(f.remote.inspect(f.record).editable)
        }
        for (code in listOf(401, 403, 429, 500)) {
            val f = Fixture(); f.failure = code
            assertEquals(ReceiptRemoteStatus.UNAVAILABLE, f.remote.inspect(f.record).status)
        }
    }
    @Test fun validatesIdentitySourceAndFreshETagAndBindsAccount() = runTest {
        for (field in listOf("expenseId", "createdAt", "sourceDeviceId", "receiptImageRelativePath", "itemType")) {
            val f = Fixture(); f.files[f.path] = JSONObject(f.files[f.path]!!).put(field, "wrong").toString()
            assertFalse(f.remote.inspect(f.record).editable, field)
        }
        val f = Fixture()
        assertFalse(f.remote.inspect(f.record.copy(exportAccountId = "other-account")).editable)
        f.changingETag = true
        assertFalse(f.remote.inspect(f.record).editable)
    }
    @Test fun consumerStateAndAcknowledgementControlRevisionAndFurtherEditing() = runTest {
        val f = Fixture(); f.moveTo("receipt_jsons_done")
        val id = "c1111111-1111-4111-8111-111111111111"
        val record = f.record.copy(lastCorrectionOperationId = id)
        assertFalse(f.remote.inspect(record).editable)
        f.files["$HANDOFF_FOLDER/receipt_correction_acks/$id.json"] = JSONObject().put("operationId", id)
            .put("expenseId", record.expenseId).put("status", "applied").put("correctionProtocolVersion", 1)
            .put("revision", 1).put("payload", JSONObject(record.toHandoffJson()).put("merchantName", "Corrected")).toString()
        f.files["$HANDOFF_FOLDER/receipt_correction_state/${record.expenseId}.json"] = JSONObject()
            .put("correctionProtocolVersion", 1).put("expenseId", record.expenseId).put("revision", 1)
            .put("status", "processed").put("payload", JSONObject(record.toHandoffJson()).put("merchantName", "Corrected")).toString()
        val observed = f.remote.inspect(record)
        assertEquals(1, observed.revision)
        assertEquals("Corrected", JSONObject(observed.payloadJson!!).getString("merchantName"))
        assertTrue(observed.editable)
        f.files["$HANDOFF_FOLDER/receipt_correction_acks/$id.json"] = JSONObject().put("operationId", id)
            .put("expenseId", record.expenseId).put("status", "conflict").put("reasonCode", "processing_changed").toString()
        val rejected = f.remote.inspect(record)
        assertEquals(ReceiptRemoteStatus.PROCESSED, rejected.status)
        assertTrue(rejected.correctionRejected)
        assertTrue(rejected.editable)
    }
    @Test fun publishesImmutableSidecarAndRetriesExactPayloadWithoutTouchingOriginalOrImage() = runTest {
        val f = Fixture()
        val operation = f.operation()
        val original = f.files[f.path]
        val result = f.remote.publish(operation)
        assertEquals(operation.operationId, result.operationId)
        assertEquals(original, f.files[f.path])
        val final = "$HANDOFF_FOLDER/expense_correction_${operation.operationId}.json"
        assertEquals(operation.payloadJson, f.files[final])
        val patch = f.requests.single { it.method == "PATCH" }
        assertNotNull(patch.headers["If-Match"])
        assertTrue(patch.body.decodeToString().contains("\"fail\""))
        assertFalse(f.requests.any { it.method == "PUT" && it.url.contains("receipt_images") })
        f.files.remove(f.path) // consumer can process/move it between a committed upload and retry
        val before = f.requests.count { it.method != "GET" }
        f.remote.publish(operation)
        assertEquals(before, f.requests.count { it.method != "GET" })
    }
    @Test fun changedStatusPayloadETagAccountAndMissingImageBlockPublication() = runTest {
        for (change in 0..4) {
            val f = Fixture(); val operation = f.operation()
            when (change) {
                0 -> f.moveTo("receipt_jsons_done")
                1 -> f.files[f.path] = JSONObject(f.files[f.path]!!).put("totalAmount", 99).toString()
                2 -> f.eTag = "changed"
                3 -> f.account = "different"
                4 -> f.imageExists = false
            }
            assertFailsWith<IllegalStateException> { f.remote.publish(operation) }
            assertFalse(f.requests.any { it.method == "PUT" })
        }
    }
    @Test fun exactArchivedOperationsConfirmPublicationButDoNotProveExcelApplication() = runTest {
        for (folder in listOf("receipt_jsons_done", "receipt_jsons_review", "receipt_jsons_error")) {
            val f = Fixture(); val operation = f.operation()
            f.files["$HANDOFF_FOLDER/$folder/expense_correction_${operation.operationId}.json"] = operation.payloadJson
            f.files.remove(f.path)
            assertEquals(operation.operationId, f.remote.publish(operation).operationId)
            assertFalse(f.requests.any { it.method != "GET" })
            assertFalse(f.remote.inspect(operation.corrected).editable)
            // The consumer can repair a missing acknowledgement from its durable ledger.
            f.appliedAck(operation)
            assertEquals(operation.operationId, f.remote.publish(operation).operationId)
            assertFalse(f.requests.any { it.method != "GET" })
        }
    }
    @Test fun differentArchivedOperationContentCannotConfirmPublication() = runTest {
        for (folder in listOf("receipt_jsons_done", "receipt_jsons_review", "receipt_jsons_error")) {
            val f = Fixture(); val operation = f.operation()
            f.files["$HANDOFF_FOLDER/$folder/expense_correction_${operation.operationId}.json"] =
                JSONObject(operation.payloadJson).put("operationId", "different-operation").toString()
            val error = assertFailsWith<IllegalStateException> { f.remote.publish(operation) }
            assertFalse(error is ReceiptCorrectionNotPublishedException)
            assertContains(error.message.orEmpty(), "different content")
            assertFalse(f.requests.any { it.method != "GET" })
        }
    }
    @Test fun stateIdentifiesOperationWhileArchiveLagsAndRetainsRetryUntilAckRepair() = runTest {
        val f = Fixture(); val operation = f.operation()
        f.moveTo("receipt_jsons_done")
        f.files["$HANDOFF_FOLDER/receipt_correction_state/${operation.expenseId}.json"] = JSONObject()
            .put("correctionProtocolVersion", 1).put("expenseId", operation.expenseId).put("operationId", operation.operationId)
            .put("revision", 1).put("status", "processed")
            .put("payload", JSONObject(operation.payloadJson).getJSONObject("payload")).toString()
        val error = assertFailsWith<IllegalStateException> { f.remote.publish(operation) }
        assertFalse(error is ReceiptCorrectionNotPublishedException)
        assertFalse(f.requests.any { it.method != "GET" })
        f.appliedAck(operation)
        assertEquals(operation.operationId, f.remote.publish(operation).operationId)
        assertFalse(f.requests.any { it.method != "GET" })
    }
    @Test fun conflictAndPreconditionFailureCannotCountAsSuccessWithDifferentContent() = runTest {
        for (status in listOf(409, 412)) {
            val f = Fixture(); val operation = f.operation(); f.commitFailure = status
            assertFailsWith<IllegalStateException> { f.remote.publish(operation) }
        }
        assertTrue(handoffJsonEquivalent("{\"a\":1.00,\"b\":[2]}", "{\"b\":[2.0],\"a\":1}"))
        assertFalse(handoffJsonEquivalent("[{\"id\":1},{\"id\":2}]", "[{\"id\":2},{\"id\":1}]"))
    }
    @Test fun cancellationPropagatesRatherThanBecomingUnavailable() = runTest {
        val f = Fixture(); f.cancelled = true
        assertFailsWith<CancellationException> { f.remote.inspect(f.record) }
    }
    @Test fun onlyAConfirmedConflictCanDiscardASavedOperation() = runTest {
        val f = Fixture(); val op = f.operation()
        val ackPath = "$HANDOFF_FOLDER/receipt_correction_acks/${op.operationId}.json"
        assertFalse(f.remote.canDiscard(op))
        for (status in listOf("deferred", "applied", "conflict")) {
            f.files[ackPath] = JSONObject().put("correctionProtocolVersion", 1).put("operationId", op.operationId)
                .put("expenseId", op.expenseId).put("status", status).toString()
            assertEquals(status == "conflict", f.remote.canDiscard(op))
        }
        f.account = "changed"
        assertFalse(f.remote.canDiscard(op))
    }
    @Test fun processingChangedAcknowledgementWithStillPendingCloudBytesWaitsForSync() = runTest {
        val f = Fixture(); val id = "c1111111-1111-4111-8111-111111111111"
        f.files["$HANDOFF_FOLDER/receipt_correction_acks/$id.json"] = JSONObject().put("operationId", id)
            .put("expenseId", f.record.expenseId).put("status", "conflict").put("reasonCode", "processing_changed").toString()
        val observation = f.remote.inspect(f.record.copy(lastCorrectionOperationId = id))
        assertEquals(ReceiptRemoteStatus.UNAVAILABLE, observation.status)
        assertFalse(observation.editable)
    }
    @Test fun delayedOrMismatchedAppliedAcknowledgementsCannotAuthorizeAnotherCorrection() = runTest {
        val f = Fixture(); val op = f.operation(); f.moveTo("receipt_jsons_done")
        f.files["$HANDOFF_FOLDER/receipt_correction_acks/${op.operationId}.json"] = JSONObject()
            .put("correctionProtocolVersion", 1).put("operationId", op.operationId).put("expenseId", op.expenseId)
            .put("status", "applied").put("revision", 1).put("payload", JSONObject(op.corrected.toHandoffJson()).put("merchantName", "Different")).toString()
        assertFalse(f.remote.inspect(op.corrected).editable) // public revision still missing
        assertFailsWith<IllegalStateException> { f.remote.publish(op) } // acknowledgement has wrong corrected payload
    }
    @Test fun typedSourceContractAndMalformedCurrentPayloadCannotAuthorizeEditing() = runTest {
        val f = Fixture()
        val typed = sampleTypedRecord().copy(status = ReceiptExportStatus.EXPORTED, exportAccountId = "account")
        f.files[f.path] = typed.toHandoffJson()
        assertTrue(f.remote.inspect(typed).editable)
        for (payload in listOf(JSONObject(typed.toHandoffJson()).put("hasReceiptImage", "false").toString(),
            JSONObject(typed.toHandoffJson()).put("receiptImageUri", "file://image").toString(),
            JSONObject(typed.toHandoffJson()).put("totalAmount", "12.34").toString(),
            typed.toHandoffJson() + " garbage")) {
            f.files[f.path] = payload
            assertFalse(f.remote.inspect(typed).editable)
        }
    }

    private class Fixture {
        val record = sampleRecord().copy(status = ReceiptExportStatus.EXPORTED, exportAccountId = "account")
        val path = record.jsonRelativePath
        val capability = "$HANDOFF_FOLDER/receipt-correction-capabilities.json"
        val files = mutableMapOf(path to record.toHandoffJson(), capability to
            "{\"correctionProtocolVersion\":1,\"enabled\":true,\"acknowledgements\":true}")
        val requests = mutableListOf<GraphContentRequest>()
        var account = "account"; var failure: Int? = null; var eTag = "tag"; var changingETag = false
        var imageExists = true; var commitFailure: Int? = null; var cancelled = false; var counter = 0
        val remote = MicrosoftGraphReceiptCorrectionRemote(object : GraphAuthenticationClient {
            override suspend fun loadCurrentAccount() = GraphAccount(account, "User")
            override suspend fun signIn(activity: Activity) = GraphAccount(account, "User")
            override suspend fun acquireAccessTokenSilently() = "secret-token"
            override suspend fun signOut() = Unit
        }, GraphContentTransport { request ->
            requests += request
            if (cancelled) throw CancellationException()
            failure?.let { return@GraphContentTransport GraphContentResponse(it) }
            if (request.url.contains("/me/drive?")) return@GraphContentTransport GraphContentResponse(200, "{\"id\":\"drive\"}")
            val filePath = if (request.url.contains("/root:/"))
                URLDecoder.decode(request.url.substringAfter("/root:/").substringBefore(":"), "UTF-8")
                else URLDecoder.decode(request.url.substringAfter("/items/").substringBefore("/content"), "UTF-8")
            if (request.method == "PATCH") {
                commitFailure?.let { return@GraphContentTransport GraphContentResponse(it) }
                val finalPath = "$HANDOFF_FOLDER/${JSONObject(request.body.decodeToString()).getString("name")}"
                files[finalPath] = files.remove(filePath)!!
                return@GraphContentTransport GraphContentResponse(200)
            }
            if (request.method == "PUT") {
                files[filePath] = request.body.decodeToString()
                return@GraphContentTransport GraphContentResponse(200)
            }
            if (filePath.contains("receipt_images") && imageExists)
                return@GraphContentTransport GraphContentResponse(200, "{\"id\":\"image\",\"file\":{}}")
            val content = files[filePath] ?: return@GraphContentTransport GraphContentResponse(404)
            if (request.url.endsWith("/content")) GraphContentResponse(200, content)
            else GraphContentResponse(200, JSONObject().put("id", filePath).put("file", JSONObject())
                .put("eTag", if (changingETag) "tag${counter++}" else eTag).toString())
        }, now = { "2026-10-16T12:00:00-07:00" })
        fun moveTo(folder: String) { files["$HANDOFF_FOLDER/$folder/${path.substringAfterLast('/')}"] = files.remove(path)!! }
        fun appliedAck(operation: ReceiptCorrectionOperation) {
            files["$HANDOFF_FOLDER/receipt_correction_acks/${operation.operationId}.json"] = JSONObject()
                .put("correctionProtocolVersion", 1).put("operationId", operation.operationId).put("expenseId", operation.expenseId)
                .put("status", "applied").put("revision", operation.expectedRevision + 1)
                .put("payload", JSONObject(operation.payloadJson).getJSONObject("payload")).toString()
        }
        suspend fun operation(): ReceiptCorrectionOperation {
            val observed = remote.inspect(record)
            val id = "c1111111-1111-4111-8111-111111111111"
            val corrected = record.copy(merchantName = "Updated", extractionStatus = "manual", lastCorrectionOperationId = id)
            val envelope = JSONObject().put("correctionProtocolVersion", 1).put("operationId", id)
                .put("expenseId", record.expenseId).put("expectedRevision", 0)
                .put("expectedPayloadJson", observed.payloadJson).put("expectedPayloadSha256", observed.payloadFingerprint)
                .put("payload", JSONObject(corrected.toHandoffJson())).toString()
            return ReceiptCorrectionOperation(id, record.expenseId, observed.payloadFingerprint!!, "pending", envelope,
                account, "2026-10-16", corrected, observed, observed.payloadJson!!, 0)
        }
    }
}
