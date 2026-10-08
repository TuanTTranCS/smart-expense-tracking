package com.hugo.smartexpense.extraction

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class BatchRequestSchedulerTest {
    private fun TestScope.clock() = object : BatchClock {
        override fun nowMillis() = testScheduler.currentTime
        override suspend fun sleep(millis: Long) { delay(millis) }
    }

    @Test fun enforcesSpacingAndHardConcurrencyCeiling() = runTest {
        val scheduler = BatchRequestScheduler(clock())
        var active = 0
        var peak = 0
        val starts = mutableListOf<Long>()
        val results = mutableListOf<Int>()
        scheduler.run((1..5).toList(), BatchProcessingPolicy(maxConcurrency = 3), operation = {
            scheduler.gate.awaitStart(3_500)
            starts += testScheduler.currentTime
            active++
            peak = maxOf(peak, active)
            delay(12_000)
            active--
            it
        }, onResult = { _, result -> results += result.getOrThrow() })
        assertEquals(listOf(0L, 3_500L, 7_000L, 12_000L, 15_500L), starts)
        assertEquals(3, peak)
        assertEquals((1..5).toList(), results)
    }

    @Test fun localPolicySerializesWithoutDelayAndSiblingFailuresAreIndependent() = runTest {
        val scheduler = BatchRequestScheduler(clock())
        var active = 0
        val starts = mutableListOf<Long>()
        val results = mutableListOf<Result<Int>>()
        scheduler.run(listOf(1,2,3), BatchProcessingPolicy(3), onDevice = true, operation = {
            assertEquals(0, active++)
            starts += testScheduler.currentTime
            delay(100)
            active--
            if (it == 2) error("Bad output") else it
        }, onResult = { _, result -> results += result })
        assertEquals(listOf(0L,100L,200L), starts)
        assertEquals(1, results.count { it.isFailure })
        assertEquals(2, results.count { it.isSuccess })
    }

    @Test fun rateLimitExtendsAlreadyWaitingRequestStarts() = runTest {
        val gate = ProviderRequestGate(clock())
        gate.awaitStart(3_500)
        var startedAt: Long? = null
        val waiting = launch { gate.awaitStart(3_500); startedAt = testScheduler.currentTime }
        runCurrent()
        advanceTimeBy(1_000)
        gate.cooldown(5_000)
        waiting.join()
        assertEquals(6_000L, startedAt)
    }

    @Test fun boundsRetriesHonorsCooldownAndDoesNotRetryAuthentication() = runTest {
        val scheduler = BatchRequestScheduler(clock(), jitterMillis = { 0 })
        var calls = 0
        var outcome: Result<Int>? = null
        scheduler.run(listOf(1), operation = {
            calls++
            throw RemoteProviderException.RateLimited("test", "slow down", retryAfterMillis = 5_000)
        }, onResult = { _, result -> outcome = result })
        assertEquals(3, calls)
        assertEquals(10_000, testScheduler.currentTime)
        assertTrue(outcome!!.isFailure)
        calls = 0
        scheduler.run(listOf(1), operation = {
            calls++
            throw RemoteProviderException.AuthenticationFailed("test", "denied")
        }, onResult = { _, _ -> })
        assertEquals(1, calls)
    }

    @Test fun timeoutAndCancellationStopDispatchWithoutLateResults() = runTest {
        val scheduler = BatchRequestScheduler(clock())
        var calls = 0
        val outcomes = mutableListOf<Result<Int>>()
        scheduler.run(listOf(1), BatchProcessingPolicy(itemDeadlineMillis = 100), operation = {
            delay(1000); it
        }, onResult = { _, result -> outcomes += result })
        assertTrue(outcomes.single().exceptionOrNull()!!.message!!.contains("timed out"))
        val job = launch {
            scheduler.run(listOf(1,2,3), operation = { calls++; delay(1000); it }, onResult = { _, _ -> fail("Late publication") })
        }
        runCurrent()
        job.cancelAndJoin()
        assertEquals(1, calls)
    }

    @Test fun parsesBothRetryAfterFormatsAndRejectsInvalidValues() {
        val now = java.time.Instant.parse("2026-10-05T00:00:00Z")
        assertEquals(3500L, parseRetryAfter("Mon, 5 Oct 2026 00:00:04 GMT", now.plusMillis(500)))
        assertEquals(4000L, parseRetryAfter("4", now))
        assertNull(parseRetryAfter("-2", now))
        assertNull(parseRetryAfter("invalid", now))
    }

    @Test fun holdsItemSlotUntilTimedOutBlockingOperationHasActuallyEnded() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val outcomes = mutableListOf<Result<Int>>()
        val scheduler = BatchRequestScheduler()
        val job = launch {
            scheduler.run(listOf(1,2), BatchProcessingPolicy(startSpacingMillis = 0, itemDeadlineMillis = 50), operation = { item ->
                withContext(Dispatchers.IO) {
                    calls.incrementAndGet()
                    if (item == 1) { entered.countDown(); release.await() }
                    item
                }
            }, onResult = { _, result -> outcomes += result })
        }
        withContext(Dispatchers.IO) { assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)) }
        delay(100)
        assertEquals(1, calls.get(), "Timeout must not free a slot while native work remains running")
        release.countDown()
        job.join()
        assertEquals(2, calls.get())
        assertTrue(outcomes.first().isFailure)
        assertEquals(2, outcomes.last().getOrThrow())
    }

    @Test fun compatibilityRecoveryUsesTheSameStartGateAndTypedSchema() = runBlocking {
        val times = mutableListOf<Long>()
        var now = 0L
        val clock = object : BatchClock {
            override fun nowMillis() = now
            override suspend fun sleep(millis: Long) { now += millis }
        }
        val scheduler = BatchRequestScheduler(clock)
        val provider = OpenAiCompatibleProviderOption("test", "Test", "https://example.com", "model",
            RemoteInputMode.DIRECT_IMAGE, "key", structuredOutputFormat = RemoteStructuredOutputFormat.JSON_SCHEMA)
        var requestBody = ""
        val source = TypedExpenseDraft("", "15+50, which is 65 in total", "CAD", "",
            "  Walmrat, Oct 7 2026, Canadian dollars\n\"original\" \\ text  ")
        val client = OpenAiCompatibleReceiptModelClient(provider, ApiKeyStore { "secret" }, object : OpenAiCompatibleApiTransport {
            override fun send(request: OpenAiCompatibleApiRequest): OpenAiCompatibleApiResponse {
                times += now
                val messages = org.json.JSONObject(request.body).getJSONArray("messages")
                val content = messages.getJSONObject(0).getString("content")
                assertEquals(TypedExpenseReviewPrompt.text + "\n\nReceipt text:\n" + source.modelInput(), content)
                assertFalse(request.body.contains("image_url"))
                assertEquals("", org.json.JSONObject(content.substringAfter("Receipt text:\n")).getString("merchantName"))
                assertEquals(source.notes, org.json.JSONObject(content.substringAfter("Receipt text:\n")).getString("notes"))
                if (times.size == 1) {
                    requestBody = request.body
                    return OpenAiCompatibleApiResponse(400, """{"code":"invalid_json"}""")
                }
                return OpenAiCompatibleApiResponse(200, """{"choices":[{"message":{"content":"ok"}}]}""")
            }
        })
        scheduler.run(listOf(1), operation = {
            withContext(Dispatchers.IO) { client.reviewTypedExpense(source) }
        }, onResult = { _, result -> assertEquals("ok", result.getOrThrow()) })
        assertEquals(listOf(0L,3500L), times)
        val schema = org.json.JSONObject(requestBody).getJSONObject("response_format").getJSONObject("json_schema")
        assertEquals("typed_expense_review", schema.getString("name"))
        val receipts = schema.getJSONObject("schema").getJSONObject("properties").getJSONObject("receipts")
        assertEquals(1, receipts.getInt("maxItems"))
        assertTrue(receipts.getJSONObject("items").getJSONObject("properties").has("issues"))
        assertFalse(requestBody.contains("image_url"))
    }

    @Test fun batchCredentialsAreResolvedOnceInMemoryAndNextRunUsesNewCredential() = runBlocking {
        var reads = 0
        var key = "first"
        val store = ApiKeyStore { reads++; key }
        val observed = mutableListOf<String?>()
        val provider = OpenAiCompatibleProviderOption("test", "Test", "https://example.com", "model", RemoteInputMode.DIRECT_IMAGE, "key")
        val transport = object : OpenAiCompatibleApiTransport {
            override fun send(request: OpenAiCompatibleApiRequest): OpenAiCompatibleApiResponse {
                observed += request.headers["Authorization"]
                return OpenAiCompatibleApiResponse(200, """{"choices":[{"message":{"content":"ok"}}]}""")
            }
        }
        val scheduler = BatchRequestScheduler()
        suspend fun run(items: List<Int>) = scheduler.run(items, BatchProcessingPolicy(startSpacingMillis = 0), operation = {
            withContext(Dispatchers.IO) {
                OpenAiCompatibleReceiptModelClient(provider, store, transport).extractFromReceiptText("data")
            }
        }, onResult = { _, result -> assertEquals("ok", result.getOrThrow()); key = "second" })
        run(listOf(1,2))
        assertEquals(listOf<String?>("Bearer first", "Bearer first"), observed.toList())
        assertEquals(1, reads)
        run(listOf(3))
        assertEquals("Bearer second", observed.last())
        assertEquals(2, reads)
    }
}
