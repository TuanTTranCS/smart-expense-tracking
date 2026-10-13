package com.hugo.smartexpense.extraction

import java.util.UUID

/** Quarantine is process-wide: separate profiles cannot bypass uncertain native ownership. */
internal object SamePhoneRequestLifetime {
    private var uncertainInstance: String? = null

    fun send(request: OpenAiCompatibleApiRequest, transport: OpenAiCompatibleApiTransport): OpenAiCompatibleApiResponse {
        val base = ApiEndpointValidator.normalize(requireNotNull(request.approvedBaseUrl), ApiProfileCategory.SAME_PHONE_SERVER)
        check(uncertainInstance == null || InferenceExecution.current.get()?.explicitRecovery == true) {
            "Same-phone cleanup is unconfirmed. Stop/restart the companion, then explicitly retry."
        }
        val prepared = transport.send(request.copy(url = "$base/requests", body = "{}"))
            .copy(companionRequestStage = CompanionRequestStage.PREPARATION)
        if (prepared.statusCode != 201) return prepared
        val record = StrictProtocolJson.objectValue(prepared.body)
        val instance = identity(record["instanceId"])
        val id = identity(record["requestId"])
        var drained = false
        val headers = request.headers + mapOf("X-Companion-Instance" to instance, "X-Request-Id" to id)
        val control = request.copy(url = "$base/requests/$id", headers = headers, body = "")
        try {
            check(record["runtimeState"] == "available") { "Companion runtime is unavailable; stop/restart it." }
            if (uncertainInstance != null) {
                check(instance != uncertainInstance) { "Restart the companion before explicitly retrying unconfirmed cleanup." }
                uncertainInstance = null
            }
            InferenceExecution.remainingMillis()
            val response = transport.send(request.copy(headers = headers))
                .copy(companionRequestStage = CompanionRequestStage.CHAT)
            if (response.statusCode == 200) {
                check(response.header("X-Companion-Instance") == instance && response.header("X-Request-Id") == id) {
                    "Companion returned mismatched request identity."
                }
                drained = true
            }
            return response
        } finally {
            if (!drained && !drain(control, transport, instance, id)) uncertainInstance = instance
        }
    }

    private fun drain(control: OpenAiCompatibleApiRequest, transport: OpenAiCompatibleApiTransport, instance: String, id: String): Boolean {
        val deadline = System.nanoTime() + 10_000_000_000L
        var method = "DELETE"
        while (System.nanoTime() < deadline) {
            val response = runCatching {
                transport.send(control.copy(method = method, cleanupDeadlineNanos = deadline))
            }.getOrNull() ?: return false
            if (response.statusCode !in setOf(200, 202)) return false
            val record = runCatching { StrictProtocolJson.objectValue(response.body) }.getOrNull() ?: return false
            if (record["instanceId"] != instance || record["requestId"] != id) return false
            if (record["state"] == "drained") return true
            method = "GET"
            val remaining = (deadline - System.nanoTime()) / 1_000_000
            if (remaining <= 0) break
            try { Thread.sleep(minOf(100, remaining)) } catch (_: InterruptedException) { return false }
        }
        return false
    }
    private fun identity(value: Any?): String {
        val text = value as? String ?: error("Missing companion identity.")
        require(UUID.fromString(text).toString() == text) { "Invalid companion identity." }
        return text
    }
    private fun OpenAiCompatibleApiResponse.header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
    fun guidance(body: String): String {
        val code = runCatching { (StrictProtocolJson.objectValue(body)["error"] as? Map<*, *>)?.get("code") }.getOrNull()
        return when (code) {
            "unauthorized", "authentication_failed" -> "Align the optional API keys in both apps, or explicitly clear both keys."
            "model_not_ready", "model_not_loaded", "model_not_found", "vision_unavailable", "vision_not_supported", "non_vision_model" -> "Load or reinitialize a compatible vision model in the companion."
            "inference_busy", "busy", "runtime_busy" -> "Finish or stop the companion's current work, then explicitly retry."
            "request_too_large", "image_too_large", "payload_too_large" -> "Enable preprocessing or select a smaller receipt input."
            "inference_timeout", "timeout", "request_expired" -> "The companion timed out; explicitly retry."
            "runtime_unavailable", "request_not_active", "instance_mismatch" -> "Stop/restart the companion, then explicitly retry."
            else -> "Check the companion endpoint, model and optional key, then explicitly retry."
        }
    }
    /** Only local stage labels and known protocol codes may enter user-visible diagnostics. */
    fun guidance(response: OpenAiCompatibleApiResponse): String {
        val code = runCatching { (StrictProtocolJson.objectValue(response.body)["error"] as? Map<*, *>)?.get("code") }.getOrNull()
        val safeCode = (code as? String)?.takeIf { it in diagnosticCodes } ?: "unrecognized_error"
        val stage = response.companionRequestStage?.description ?: "Companion request"
        return "${guidance(response.body)} ($stage: HTTP ${response.statusCode}, $safeCode.)"
    }
    private val diagnosticCodes = setOf(
        "authentication_failed", "model_not_ready", "vision_unavailable", "inference_busy",
        "payload_too_large", "output_too_large", "inference_timeout", "runtime_unavailable",
        "request_not_active", "instance_mismatch", "request_not_found", "request_capacity",
        "invalid_request", "invalid_image", "empty_input", "unsupported_response_format",
        "unsupported_tools", "unsupported_parameters", "unsupported_stream", "unsupported_role",
        "unsupported_content",
    )
}

/** Read image dimensions without decoding pixels or allocating base64. */
internal object ImageAdmission {
    fun validate(image: ReceiptImage) {
        val b = image.bytes
        fun u(i: Int) = b[i].toInt() and 255
        val dimensions = if (image.mimeType == "image/png") {
            require(b.size >= 24 && b.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) { "Invalid PNG receipt." }
            fun integer(i: Int) = (u(i).toLong() shl 24) or (u(i + 1).toLong() shl 16) or (u(i + 2).toLong() shl 8) or u(i + 3).toLong()
            integer(16) to integer(20)
        } else {
            require(b.size >= 4 && u(0) == 255 && u(1) == 216) { "Invalid JPEG receipt." }
            var i = 2
            var found: Pair<Long, Long>? = null
            while (i + 3 < b.size) {
                require(u(i) == 255) { "Invalid JPEG receipt." }
                while (i < b.size && u(i) == 255) i++
                require(i + 2 < b.size)
                val marker = u(i++)
                if (marker == 218 || marker == 217) break
                if (marker in 208..215 || marker == 1) continue
                val length = (u(i) shl 8) or u(i + 1)
                require(length >= 2 && i + length <= b.size)
                if (marker in setOf(192, 193, 194, 195, 197, 198, 199, 201, 202, 203, 205, 206, 207)) {
                    require(length >= 8)
                    found = (((u(i + 5) shl 8) or u(i + 6)).toLong() to ((u(i + 3) shl 8) or u(i + 4)).toLong()); break
                }
                i += length
            }
            requireNotNull(found) { "JPEG receipt has no dimensions." }
        }
        require(dimensions.first in 1..8192 && dimensions.second in 1..8192 && dimensions.first * dimensions.second <= 16_000_000) {
            "Enable preprocessing or select a smaller receipt image (maximum 16 megapixels and 8192 pixels)."
        }
    }
}
