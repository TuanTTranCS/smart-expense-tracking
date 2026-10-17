package com.hugo.smartexpense.app.receiptexport

import com.hugo.smartexpense.app.graphauth.GraphAuthenticationClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.ZonedDateTime

/** Immutable sidecars avoid overwriting a queue file already read by the Windows consumer. */
class MicrosoftGraphReceiptCorrectionRemote(
    private val authentication: GraphAuthenticationClient,
    private val transport: GraphContentTransport = AndroidGraphContentTransport(),
    private val now: () -> String = { ZonedDateTime.now().toString() },
) : ReceiptCorrectionRemote {
    override suspend fun inspect(record: ReceiptExportRecord): ReceiptRemoteObservation = withContext(Dispatchers.IO) {
        try { inspectChecked(record) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { ReceiptRemoteObservation(ReceiptRemoteStatus.UNAVAILABLE, now(),
            message = "OneDrive status is unavailable. Reconnect or refresh; your edit is retained.") }
    }

    private suspend fun inspectChecked(record: ReceiptExportRecord): ReceiptRemoteObservation {
        val account = requireNotNull(authentication.loadCurrentAccount()) { "Reconnect OneDrive." }
        if (record.exportAccountId != null && record.exportAccountId != account.id) {
            return ReceiptRemoteObservation(ReceiptRemoteStatus.UNAVAILABLE, now(), accountId = account.id,
                message = "Reconnect the OneDrive account used for this export.")
        }
        val token = authentication.acquireAccessTokenSilently()
        check(authentication.loadCurrentAccount()?.id == account.id) { "OneDrive account changed during status refresh." }
        val drive = jsonObject(request(token, "GET", "$ROOT/me/drive?\$select=id").requireSuccess().body).getString("id")
        val capability = readContent(token, "$HANDOFF_FOLDER/receipt-correction-capabilities.json")?.let {
            jsonObject(it).let { json -> json.optInt("correctionProtocolVersion") == 1 &&
                json.optBoolean("enabled") && json.optBoolean("acknowledgements") }
        } ?: false
        fun observation(status: ReceiptRemoteStatus, message: String, candidate: Candidate? = null,
            payload: JSONObject? = candidate?.payload, revision: Int = 0, enabled: Boolean = capability): ReceiptRemoteObservation {
            val json = payload?.toString()
            return ReceiptRemoteObservation(status, now(), account.id, drive, candidate?.id, candidate?.eTag,
                json?.let(::receiptPayloadSha256), message, json, revision, enabled)
        }
        // Once published, a correction remains ineligible for another edit until Windows acknowledges it.
        val previousOperation = record.lastCorrectionOperationId
        var correctionRejected = false
        var appliedAck: JSONObject? = null
        if (previousOperation != null) {
            val ack = readContent(token, "$HANDOFF_FOLDER/receipt_correction_acks/$previousOperation.json")
                ?.let(::jsonObject)
            if (ack == null) return observation(ReceiptRemoteStatus.UNAVAILABLE,
                "Correction JSON published; awaiting Excel acknowledgement. Refresh later.", enabled = false)
            require(ack.getString("operationId") == previousOperation && ack.getString("expenseId") == record.expenseId)
            if (ack.getString("status") == "conflict" && ack.optString("reasonCode") == "processing_changed") {
                correctionRejected = true
            } else if (ack.getString("status") != "applied") return observation(ReceiptRemoteStatus.NEEDS_REVIEW,
                "Excel correction ${ack.optString("status")}: ${ack.optString("reasonCode")}. Your correction needs review.", enabled = false)
            else {
                require(ack.getInt("correctionProtocolVersion") == 1 && ack.getInt("revision") > 0)
                validateIdentity(ack.getJSONObject("payload"), record)
                appliedAck = ack
            }
        }
        val canonical = ReceiptExportPaths.create(record.expenseId, ZonedDateTime.parse(record.createdAt)).jsonRelativePath
        val locations = linkedMapOf<String, ReceiptRemoteStatus>()
        locations[record.jsonRelativePath] = ReceiptRemoteStatus.PENDING
        locations[canonical] = ReceiptRemoteStatus.PENDING
        val name = canonical.substringAfterLast('/')
        locations["$HANDOFF_FOLDER/receipt_jsons_done/$name"] = ReceiptRemoteStatus.PROCESSED
        locations["$HANDOFF_FOLDER/receipt_jsons_review/$name"] = ReceiptRemoteStatus.NEEDS_REVIEW
        locations["$HANDOFF_FOLDER/receipt_jsons_error/$name"] = ReceiptRemoteStatus.ERROR
        val found = locations.mapNotNull { (path, status) -> readCandidate(token, path, status, record) }
        if (found.size > 1) return observation(ReceiptRemoteStatus.UNAVAILABLE,
            "Conflicting or partially expanded handoff locations. Refresh after processing completes.", enabled = false)
        val candidate = found.singleOrNull() ?: return observation(ReceiptRemoteStatus.MISSING,
            "No verified expense handoff was found. A missing file does not confirm Excel processing.", enabled = false)
        if (correctionRejected && candidate.status != ReceiptRemoteStatus.PROCESSED) return observation(
            ReceiptRemoteStatus.UNAVAILABLE, "Processing changed; awaiting synchronized done-file evidence. Refresh later.", enabled = false)
        val state = readContent(token, "$HANDOFF_FOLDER/receipt_correction_state/${record.expenseId}.json")?.let(::jsonObject)
        val revision = state?.optInt("revision", 0) ?: 0
        if (appliedAck != null && (state == null || revision < appliedAck.getInt("revision"))) {
            return observation(ReceiptRemoteStatus.UNAVAILABLE,
                "Excel acknowledged the correction; awaiting synchronized revision details. Refresh later.", enabled = false)
        }
        val payload = if (state != null) {
            require(state.getInt("correctionProtocolVersion") == 1 && state.getString("expenseId") == record.expenseId)
            require(revision >= 0)
            require(candidate.status == ReceiptRemoteStatus.PROCESSED && state.getString("status") == "processed")
            if (appliedAck != null && revision == appliedAck.getInt("revision")) require(
                handoffJsonEquivalent(state.getJSONObject("payload").toString(), appliedAck.getJSONObject("payload").toString()))
            state.getJSONObject("payload").also { validateIdentity(it, record) }
        } else candidate.payload
        val status = candidate.status
        return observation(status, when {
            correctionRejected -> "The pending correction was not applied because processing changed. Edit and confirm again as a processed correction."
            !capability -> "${status.displayName()}. Re-export requires the compatible Windows consumer to be enabled."
            previousOperation != null -> "Excel correction applied."
            else -> status.displayName()
        }, candidate, payload, revision).copy(correctionRejected = correctionRejected)
    }

    override suspend fun canDiscard(operation: ReceiptCorrectionOperation): Boolean = withContext(Dispatchers.IO) {
        try {
            if (authentication.loadCurrentAccount()?.id != operation.accountId) return@withContext false
            val token = authentication.acquireAccessTokenSilently()
            if (authentication.loadCurrentAccount()?.id != operation.accountId) return@withContext false
            val drive = jsonObject(request(token, "GET", "$ROOT/me/drive?\$select=id").requireSuccess().body).getString("id")
            if (drive != operation.observation.driveId) return@withContext false
            val ack = readContent(token, "$HANDOFF_FOLDER/receipt_correction_acks/${operation.operationId}.json")
                ?.let(::jsonObject) ?: return@withContext false
            ack.getInt("correctionProtocolVersion") == 1 && ack.getString("operationId") == operation.operationId && ack.getString("expenseId") == operation.expenseId &&
                ack.getString("status") == "conflict"
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { false }
    }

    override suspend fun publish(operation: ReceiptCorrectionOperation): ReceiptCorrectionAcknowledgement = withContext(Dispatchers.IO) {
        val account = requireNotNull(authentication.loadCurrentAccount())
        check(account.id == operation.accountId) { "Reconnect the account used to confirm this correction." }
        val token = authentication.acquireAccessTokenSilently()
        check(authentication.loadCurrentAccount()?.id == operation.accountId) { "OneDrive account changed during authorization." }
        val drive = jsonObject(request(token, "GET", "$ROOT/me/drive?\$select=id").requireSuccess().body).getString("id")
        check(drive == operation.observation.driveId) { "OneDrive identity changed. Refresh before re-exporting." }
        require(operation.operationId.matches(Regex("[0-9a-fA-F-]{36}")))
        val path = "$HANDOFF_FOLDER/expense_correction_${operation.operationId}.json"
        val ack = readContent(token, "$HANDOFF_FOLDER/receipt_correction_acks/${operation.operationId}.json")?.let(::jsonObject)
        if (ack != null) {
            require(ack.getString("operationId") == operation.operationId && ack.getString("expenseId") == operation.expenseId)
            check(ack.getString("status") == "applied") { "Correction requires review: ${ack.optString("reasonCode")}." }
            check(ack.getInt("correctionProtocolVersion") == 1 && ack.getInt("revision") == operation.expectedRevision + 1 &&
                handoffJsonEquivalent(ack.getJSONObject("payload").toString(), jsonObject(operation.payloadJson).getJSONObject("payload").toString())) {
                "The consumer acknowledgement does not match this correction."
            }
            return@withContext ReceiptCorrectionAcknowledgement(operation.operationId, now())
        }
        // Reconcile a timeout after server commit before checking a source that may since have moved.
        readContent(token, path)?.let { existing ->
            check(handoffJsonEquivalent(existing, operation.payloadJson)) { "The operation identity has different content." }
            return@withContext ReceiptCorrectionAcknowledgement(operation.operationId, now())
        }
        // Matching archived operation content proves publication, just like the inbox sidecar. It does
        // not prove Excel application; inspect still requires the consumer acknowledgement.
        for (folder in listOf("receipt_jsons_done", "receipt_jsons_review", "receipt_jsons_error")) {
            readContent(token, "$HANDOFF_FOLDER/$folder/${path.substringAfterLast('/')}")?.let { archived ->
                check(handoffJsonEquivalent(archived, operation.payloadJson)) { "The archived operation identity has different content." }
                return@withContext ReceiptCorrectionAcknowledgement(operation.operationId, now())
            }
        }
        val state = readContent(token, "$HANDOFF_FOLDER/receipt_correction_state/${operation.expenseId}.json")?.let(::jsonObject)
        if (state?.optString("operationId") == operation.operationId) {
            // The archive itself can lag its move. Do not discard a retry for an
            // operation already identified by the consumer's public projection.
            error("The consumer recorded this correction; awaiting its acknowledgement. Refresh or retry later.")
        }
        val current = inspectChecked(operation.corrected.copy(lastCorrectionOperationId = null))
        if (!(current.editable && current.accountId == operation.accountId && current.driveId == operation.observation.driveId &&
            current.payloadFingerprint == operation.baseFingerprint && current.revision == operation.expectedRevision &&
            current.status == operation.observation.status && current.itemId == operation.observation.itemId &&
            current.eTag == operation.observation.eTag)) throw ReceiptCorrectionNotPublishedException(
                "Processing or the handoff changed. Refresh and confirm the correction again.")
        operation.corrected.receiptImageRelativePath?.let { image ->
            val response = request(token, "GET", itemUrl(image) + "?\$select=id,file")
            check(response.statusCode in 200..299 && jsonObject(response.body).has("file")) {
                "The original normalized receipt image is unavailable."
            }
        }
        val temporary = "$path.uploading"
        check(authentication.loadCurrentAccount()?.id == operation.accountId) { "OneDrive account changed before publication." }
        request(token, "PUT", itemUrl(temporary) + "/content", operation.payloadJson).requireSuccess()
        val metadata = request(token, "GET", itemUrl(temporary) + "?\$select=id,eTag").requireSuccess().body.let(::jsonObject)
        val eTag = metadata.getString("eTag")
        val commit = request(token, "PATCH", "$ROOT/drives/${encode(drive)}/items/${encode(metadata.getString("id"))}",
            JSONObject().put("name", path.substringAfterLast('/')).put("@microsoft.graph.conflictBehavior", "fail").toString(),
            mapOf("If-Match" to eTag))
        if (commit.statusCode !in 200..299) {
            val existing = readContent(token, path)
            check(existing != null && handoffJsonEquivalent(existing, operation.payloadJson)) {
                "Correction commit was not confirmed (${commit.statusCode}). Refresh or retry the saved operation."
            }
        }
        ReceiptCorrectionAcknowledgement(operation.operationId, now())
    }

    private data class Candidate(val status: ReceiptRemoteStatus, val id: String, val eTag: String, val payload: JSONObject)
    private fun readCandidate(token: String, path: String, status: ReceiptRemoteStatus, record: ReceiptExportRecord): Candidate? {
        val metadata = request(token, "GET", itemUrl(path) + "?\$select=id,eTag,parentReference,file")
        if (metadata.statusCode == 404) return null
        val before = jsonObject(metadata.requireSuccess().body)
        require(before.has("file"))
        val id = before.getString("id")
        val eTag = before.getString("eTag")
        val content = request(token, "GET", "$ROOT/me/drive/items/${encode(id)}/content").requireSuccess().body
        val value = parseHandoffJson(content)
        val payload = when (value) {
            is JSONObject -> value
            is JSONArray -> (0 until value.length()).map { value.getJSONObject(it) }
                .filter { it.optString("expenseId") == record.expenseId }.single()
            else -> error("Invalid handoff.")
        }
        validateIdentity(payload, record)
        val after = request(token, "GET", itemUrl(path) + "?\$select=id,eTag,parentReference").requireSuccess().body.let(::jsonObject)
        check(after.getString("id") == id && after.getString("eTag") == eTag) { "The handoff changed during refresh." }
        return Candidate(status, id, eTag, payload)
    }

    private fun validateIdentity(payload: JSONObject, record: ReceiptExportRecord) {
        fun string(name: String) = payload.get(name).also { require(it is String) } as String
        require(string("expenseId") == record.expenseId && string("createdAt") == record.createdAt &&
            string("sourceDeviceId") == record.sourceDeviceId)
        require(payload.get("schemaVersion") is Number && payload.get("schemaVersion").toString().toBigDecimal()
            .compareTo(record.schemaVersion.toBigDecimal()) == 0)
        require(!payload.has("itemType") || string("itemType") in setOf("pending", "processed"))
        require(string("extractionStatus") in setOf("manual", "confirmed", "low_confidence"))
        require(string("merchantName").isNotBlank() && string("currency").matches(Regex("[A-Z]{3}")))
        require(string("receiptDate").matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
        java.time.LocalDate.parse(string("receiptDate"))
        require(payload.get("totalAmount") is Number && payload.get("totalAmount").toString().toBigDecimal().signum() >= 0)
        if (record.schemaVersion == 3) {
            require(string("inputSource") == "typed" && payload.get("hasReceiptImage") == false &&
                payload.has("receiptImageRelativePath") && payload.isNull("receiptImageRelativePath") && string("extractionStatus") == "manual")
            for (key in listOf("receiptImageUri", "originalImageFileName", "receiptPhotoLink")) require(!payload.has(key) || payload.isNull(key))
        } else require(string("receiptImageRelativePath") == record.receiptImageRelativePath)
    }
    private fun readContent(token: String, path: String): String? {
        val response = request(token, "GET", itemUrl(path) + "/content")
        return if (response.statusCode == 404) null else response.requireSuccess().body
    }
    private fun jsonObject(content: String): JSONObject = parseHandoffJson(content) as JSONObject
    private fun request(token: String, method: String, url: String, body: String = "", headers: Map<String, String> = emptyMap()) =
        transport.execute(GraphContentRequest(method, url, mapOf("Authorization" to "Bearer $token", "Accept" to "application/json",
            "Content-Type" to "application/json") + headers, body.toByteArray(Charsets.UTF_8)))
    private fun GraphContentResponse.requireSuccess(): GraphContentResponse = apply {
        check(statusCode in 200..299) { "OneDrive request failed ($statusCode)." }
    }
    private fun itemUrl(path: String) = "$ROOT/me/drive/root:/${path.split('/').joinToString("/") { encode(it) }}:"
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun ReceiptRemoteStatus.displayName() = name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
    private companion object { const val ROOT = "https://graph.microsoft.com/v1.0" }
}

internal fun receiptPayloadSha256(json: String): String = MessageDigest.getInstance("SHA-256")
    .digest(json.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** One strict bounded JSON document; Android's JSONTokener also accepts malformed JSON. */
internal fun parseHandoffJson(json: String): Any {
    // Android's collection constructors stringify BigDecimal through JSONObject.wrap().
    // Insert decoded numbers directly so structural comparison keeps numeric semantics.
    fun convert(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().apply { value.forEach { (key, item) -> put(key as String, convert(item)) } }
        is List<*> -> JSONArray().apply { value.forEach { put(convert(it)) } }
        else -> value
    }
    val decoded = com.hugo.smartexpense.extraction.StrictProtocolJson.value(json)
    require(decoded is Map<*, *> || decoded is List<*>) { "Expected a JSON object or array." }
    return convert(decoded)
}

/** Structural comparison tolerates whitespace/key order, never changes array membership/order. */
internal fun handoffJsonEquivalent(first: String, second: String): Boolean = runCatching {
    fun equivalent(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject -> a.keys().asSequence().toSet().let { keys ->
            keys == b.keys().asSequence().toSet() && keys.all { equivalent(a.get(it), b.get(it)) } }
        a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { equivalent(a.get(it), b.get(it)) }
        a is Number && b is Number -> BigDecimal(a.toString()).compareTo(BigDecimal(b.toString())) == 0
        else -> a == b
    }
    equivalent(parseHandoffJson(first), parseHandoffJson(second))
}.getOrDefault(false)
