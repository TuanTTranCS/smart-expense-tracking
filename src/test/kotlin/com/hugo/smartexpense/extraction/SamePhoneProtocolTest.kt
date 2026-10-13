package com.hugo.smartexpense.extraction

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlin.test.*

class SamePhoneProtocolTest {
    private val instance = "11111111-1111-4111-8111-111111111111"
    private val id = "22222222-2222-4222-8222-222222222222"
    private fun fixture(name: String) = Files.readString(Path.of("fixtures/mobile-server-contract-v1/$name-response.json"))
    private fun provider() = OpenAiCompatibleProviderOption("phone", "Same phone", "http://127.0.0.1:8091/expense/v1", "vision",
        RemoteInputMode.DIRECT_IMAGE, "phone-key", RemoteStructuredOutputFormat.PROMPT_ONLY_JSON, category = ApiProfileCategory.SAME_PHONE_SERVER)
    private fun client(key: String? = null, send: (OpenAiCompatibleApiRequest) -> OpenAiCompatibleApiResponse) =
        OpenAiCompatibleReceiptModelClient(provider(), ApiKeyStore { key }, object : OpenAiCompatibleApiTransport {
            override fun send(request: OpenAiCompatibleApiRequest) = send(request)
        })
    private fun response(content: String) = OpenAiCompatibleApiResponse(200,
        """{"choices":[{"message":{"role":"assistant","content":$content}}]}""",
        mapOf("X-Companion-Instance" to instance, "X-Request-Id" to id))

    @Test fun promptOnlyUsesPreparedFixtureAndOptionalAuthAcrossEveryRoute() {
        for (key in listOf(null, "test-key")) {
            val requests = mutableListOf<OpenAiCompatibleApiRequest>()
            val c = client(key) { request ->
                requests += request
                if (request.url.endsWith("/requests")) OpenAiCompatibleApiResponse(201, fixture("prepared"))
                else response("\"{\\\"status\\\": \\\"ok\\\"}\"")
            }
            assertIs<ProviderTestResult.Success>(c.testConfiguration())
            assertEquals(2, requests.size)
            assertEquals("http://127.0.0.1:8091/expense/v1/requests", requests.first().url)
            assertFalse(requests.last().body.contains("response_format"))
            assertEquals(id, requests.last().headers["X-Request-Id"])
            requests.forEach { assertEquals(key?.let { "Bearer $it" }, it.headers["Authorization"]) }
        }
    }
    @Test fun invalidJsonErrorDoesNotRetryAndDrainsPreparedRequest() {
        val requests = mutableListOf<OpenAiCompatibleApiRequest>()
        val c = client { request ->
            requests += request
            when {
                request.url.endsWith("/requests") -> OpenAiCompatibleApiResponse(201, fixture("prepared"))
                request.method == "DELETE" -> OpenAiCompatibleApiResponse(200, fixture("drained"))
                else -> OpenAiCompatibleApiResponse(400, """{"error":{"code":"invalid_json","message":"secret hostile text"}}""")
            }
        }
        val failure = assertFailsWith<RemoteProviderException.UnexpectedResponse> { c.extractFromReceiptText("TOTAL 1 CAD") }
        assertNull(failure.rawResponseBody)
        assertFalse(failure.message.orEmpty().contains("hostile"))
        assertEquals(listOf("POST", "POST", "DELETE"), requests.map { it.method })
    }
    @Test fun mismatchedIdentityCannotPublishAndRequiresMatchingDrain() {
        var deleted = false
        val c = client { request -> when {
            request.url.endsWith("/requests") -> OpenAiCompatibleApiResponse(201, fixture("prepared"))
            request.method == "DELETE" -> { deleted = true; OpenAiCompatibleApiResponse(200, fixture("drained")) }
            else -> response("\"{}\"").copy(headers = emptyMap())
        } }
        assertFailsWith<IllegalStateException> { c.extractFromReceiptText("expense") }
        assertTrue(deleted)
    }
    @Test fun semanticVerificationRejectsExtraFieldsWrongTypesArraysAndTrailingGarbage() {
        for (content in listOf("{\"status\":\"wrong\"}", "{\"status\":true}", "{\"status\":\"ok\",\"extra\":1}", "[]", "{\"status\":\"ok\"} x")) {
            val quoted = "\"" + content.replace("\"", "\\\"") + "\""
            val c = client { if (it.url.endsWith("/requests")) OpenAiCompatibleApiResponse(201, fixture("prepared")) else response(quoted) }
            assertIs<ProviderTestResult.Failed>(c.testConfiguration())
        }
    }
    @Test fun strictEnvelopeIgnoresUnrelatedContentAndRejectsMalformedDocuments() {
        val c = client { if (it.url.endsWith("/requests")) OpenAiCompatibleApiResponse(201, fixture("prepared")) else
            response("\"{}\"").copy(body = """{"content":"attacker","choices":[{"message":{"role":"assistant","content":"{}"}}]}""") }
        assertEquals("{}", c.extractFromReceiptText("expense"))
        for (body in listOf("{}", "{\"choices\":[]} ", "{\"choices\":[{\"message\":{\"role\":\"user\",\"content\":\"{}\"}}]}", fixture("chat") + "x")) {
            val bad = client { if (it.url.endsWith("/requests")) OpenAiCompatibleApiResponse(201, fixture("prepared")) else response("\"{}\"").copy(body = body) }
            assertFailsWith<RemoteProviderException.UnexpectedResponse> { bad.extractFromReceiptText("expense") }
        }
    }
    @Test fun jsonDecoderRejectsDuplicatesInvalidEscapesAndTrailingCommas() {
        for (raw in listOf("{\"x\":1,\"x\":2}", "{\"x\":\"\\q\"}", "{\"x\":1,}", "{\"x\":01}", "{\"x\":[1,]}")) {
            assertFails { StrictProtocolJson.objectValue(raw) }
        }
        for (raw in listOf("""{"status":"\u+06fk"}""", """{"status":"\u-001"}""", """{"x":"\u00g0"}""")) {
            assertFails { StrictProtocolJson.objectValue(raw) }
        }
    }
    @Test fun companionContractCodesGiveSpecificSafeRecoveryGuidance() {
        for ((code, expected) in listOf("model_not_ready" to "Load", "inference_busy" to "Finish", "inference_timeout" to "timed out")) {
            assertContains(SamePhoneRequestLifetime.guidance("""{"error":{"code":"$code","message":"hostile key text"}}"""), expected)
        }
    }
    @Test fun verificationDistinguishesPreparationFailureFromChatFailureWithoutExposingBody() {
        for ((stage, status, code) in listOf(
            Triple(CompanionRequestStage.PREPARATION, 503, "runtime_unavailable"),
            Triple(CompanionRequestStage.CHAT, 503, "runtime_unavailable"),
            Triple(CompanionRequestStage.CHAT, 409, "request_not_active"),
            Triple(CompanionRequestStage.CHAT, 409, "instance_mismatch"),
        )) {
            val requests = mutableListOf<OpenAiCompatibleApiRequest>()
            val c = client("private-api-key") { request ->
                requests += request
                when {
                    request.method == "DELETE" -> OpenAiCompatibleApiResponse(200, fixture("drained"))
                    request.url.endsWith("/requests") && stage == CompanionRequestStage.CHAT ->
                        OpenAiCompatibleApiResponse(201, fixture("prepared"))
                    else -> OpenAiCompatibleApiResponse(status,
                        """{"error":{"code":"$code","message":"private-api-key /private/model expense text"}}""",
                        companionRequestStage = if (stage == CompanionRequestStage.CHAT) CompanionRequestStage.PREPARATION else CompanionRequestStage.CHAT)
                }
            }
            val failure = assertIs<ProviderTestResult.Failed>(c.testConfiguration())
            assertContains(failure.reason, "Stop/restart the companion")
            assertContains(failure.reason, stage.description)
            assertContains(failure.reason, "HTTP $status, $code")
            assertFalse(failure.reason.contains("private-api-key"))
            assertFalse(failure.reason.contains("/private/model"))
            assertFalse(failure.reason.contains("expense text"))
            assertEquals(if (stage == CompanionRequestStage.PREPARATION) listOf("POST") else listOf("POST", "POST", "DELETE"),
                requests.map { it.method })
        }
    }
    @Test fun diagnosticsRejectUnknownCodesMalformedErrorsAndArbitraryServerMessages() {
        for (body in listOf(
            """{"error":{"code":"private-api-key","message":"private expense"}}""",
            """{"error":{"code":"runtime_unavailable","code":"private-api-key"}}""",
            """{"error":{"code":123,"message":"private expense"}}""",
            "private-api-key /private/model",
        )) {
            val c = client("private-api-key") { OpenAiCompatibleApiResponse(503, body) }
            val reason = assertIs<ProviderTestResult.Failed>(c.testConfiguration()).reason
            assertContains(reason, "Request preparation: HTTP 503, unrecognized_error")
            assertFalse(reason.contains("private-api-key"))
            assertFalse(reason.contains("private expense"))
            assertFalse(reason.contains("/private/model"))
        }
    }
    @Test fun samePhonePolicyNeverRetriesAndSerialItemsGetIndependentClocks() = runBlocking {
        var attempts = 0
        val outcomes = mutableListOf<Result<Int>>()
        BatchRequestScheduler().run(listOf(1, 2), BatchProcessingPolicy(itemDeadlineMillis = 100),
            category = ProviderCategory.SAME_PHONE_SERVER,
            operation = { attempts++; delay(70); if (it == 1) throw RemoteProviderException.NetworkUnavailable("phone", "offline") else it },
            onResult = { _, result -> outcomes += result })
        assertEquals(2, attempts)
        assertTrue(outcomes.first().isFailure)
        assertEquals(2, outcomes.last().getOrThrow())
    }
    @Test fun snapshotCanFreezeExplicitNoKeyBeforeQueue() {
        val snapshot = OperationCredentialSnapshot(mapOf("alias" to null))
        assertNull(snapshot.get("alias", ApiKeyStore { "new-key" }))
        assertFalse(snapshot.toString().contains("new-key"))
    }
    @Test fun unconfirmedCleanupQuarantinesEveryCallerUntilExplicitFreshAvailableInstance() = runBlocking {
        for (failure in listOf(401, 404, 409, 500, -1)) {
            var activeInstance = instance
            var finishChat = false
            var controls = 0
            var calls = 0
            val c = client { request ->
                calls++
                when {
                    request.url.endsWith("/requests") -> OpenAiCompatibleApiResponse(201, fixture("prepared").replace(instance, activeInstance))
                    request.method == "DELETE" || request.method == "GET" -> {
                        controls++
                        if (failure == -1) throw java.io.IOException("Connection lost")
                        OpenAiCompatibleApiResponse(failure, "{}")
                    }
                    finishChat -> response("\"{}\"").copy(headers = mapOf("X-Companion-Instance" to activeInstance, "X-Request-Id" to id))
                    else -> OpenAiCompatibleApiResponse(503, "{}")
                }
            }
            assertFailsWith<RemoteProviderException.UnexpectedResponse> { c.extractFromReceiptText("expense") }
            assertEquals(1, controls)
            val beforeBlockedCall = calls
            assertFailsWith<IllegalStateException> { c.extractFromReceiptText("expense") }
            assertEquals(beforeBlockedCall, calls, "Ordinary work must not silently prepare or resubmit")
            val scope = InferenceExecution.Scope(coroutineContext.job, ProviderRequestGate(), 0,
                category = ProviderCategory.SAME_PHONE_SERVER, explicitRecovery = true)
            withContext(InferenceExecution.current.asContextElement(scope)) {
                assertFailsWith<IllegalStateException> { c.extractFromReceiptText("expense") }
                activeInstance = java.util.UUID.randomUUID().toString()
                finishChat = true
                assertEquals("{}", c.extractFromReceiptText("explicit retry"))
            }
            assertEquals("{}", c.extractFromReceiptText("later admitted work"))
        }
    }
    @Test fun pendingCancellationPollsGetAndAcceptsOnlyMatchingDrainedIdentity() {
        val methods = mutableListOf<String>()
        val c = client { request ->
            methods += request.method
            when {
                request.url.endsWith("/requests") -> OpenAiCompatibleApiResponse(201, fixture("prepared"))
                request.method == "DELETE" -> OpenAiCompatibleApiResponse(202, fixture("drained").replace("drained", "cancelling"))
                request.method == "GET" -> OpenAiCompatibleApiResponse(200, fixture("drained"))
                else -> OpenAiCompatibleApiResponse(503, "{}")
            }
        }
        assertFailsWith<RemoteProviderException.UnexpectedResponse> { c.extractFromReceiptText("expense") }
        assertEquals(listOf("POST", "POST", "DELETE", "GET"), methods)
    }
    @Test fun cancellingAfterSamePhonePermitWhileGlobalBudgetBusyReleasesIt() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val owner = launch(Dispatchers.IO) {
            InferenceExecution.request(verification = true, category = ProviderCategory.REMOTE_API) {
                entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        assertTrue(withContext(Dispatchers.IO) { entered.await(5, java.util.concurrent.TimeUnit.SECONDS) })
        try {
            val waiting = launch(Dispatchers.IO) {
                val scope = InferenceExecution.Scope(coroutineContext.job, ProviderRequestGate(), 0,
                    category = ProviderCategory.SAME_PHONE_SERVER)
                withContext(InferenceExecution.current.asContextElement(scope)) {
                    InferenceExecution.request(category = ProviderCategory.SAME_PHONE_SERVER) { fail("Cancelled waiter dispatched") }
                }
            }
            delay(150); waiting.cancelAndJoin()
        } finally { release.countDown(); owner.join() }
        val scope = InferenceExecution.Scope(coroutineContext.job, ProviderRequestGate(), 0,
            category = ProviderCategory.SAME_PHONE_SERVER, deadlineNanos = System.nanoTime() + 1_000_000_000)
        withContext(Dispatchers.IO + InferenceExecution.current.asContextElement(scope)) {
            assertEquals("reused", InferenceExecution.request(category = ProviderCategory.SAME_PHONE_SERVER) { "reused" })
        }
    }
    @Test fun allSamePhoneCallersSharePermitAndWaitingConsumesDeadline() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val active = java.util.concurrent.atomic.AtomicInteger()
        val peak = java.util.concurrent.atomic.AtomicInteger()
        val holder = async(Dispatchers.IO) {
            InferenceExecution.request(category = ProviderCategory.SAME_PHONE_SERVER) {
                peak.set(maxOf(peak.get(), active.incrementAndGet()))
                entered.countDown(); release.await(); active.decrementAndGet()
            }
        }
        withContext(Dispatchers.IO) { assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)) }
        val timedOut = async(Dispatchers.IO) {
            val scope = InferenceExecution.Scope(coroutineContext.job, InferenceExecution.sharedGate, 0,
                category = ProviderCategory.SAME_PHONE_SERVER, deadlineNanos = System.nanoTime() + 60_000_000)
            withContext(InferenceExecution.current.asContextElement(scope)) {
                assertFailsWith<java.net.SocketTimeoutException> {
                    InferenceExecution.request(verification = true) { fail("Waiting caller must not dispatch.") }
                }
            }
        }
        timedOut.await(); release.countDown(); holder.await()
        InferenceExecution.request(category = ProviderCategory.SAME_PHONE_SERVER) {
            peak.set(maxOf(peak.get(), active.incrementAndGet())); active.decrementAndGet()
        }
        assertEquals(1, peak.get())
    }
    @Test fun refusesOversizedImageBeforePreparingOrAllocatingBase64() {
        var sent = false
        val c = client { sent = true; error("Must not connect.") }
        assertFailsWith<IllegalArgumentException> {
            c.extractFromReceiptImage(ReceiptImage("huge.png", ByteArray(8 * 1024 * 1024 + 1), "image/png"))
        }
        assertFalse(sent)
    }
}
