package com.hugo.smartexpense.app.modelprofile.ui

import com.hugo.smartexpense.app.modelprofile.domain.RemoteModelClientFactory
import com.hugo.smartexpense.extraction.*
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DefaultModelProfileTestServiceTest {
    private val profile = ModelProfile("id", "Provider", "https://example.test", "model", RemoteInputMode.DIRECT_IMAGE,
        RemoteStructuredOutputFormat.JSON_SCHEMA, ModelProfile.credentialAlias("id"), 1, 1)

    @OptIn(InternalCoroutinesApi::class)
    @Test fun cancellingVerificationCancelsAdapterScopeAndDrainsBlockingCall() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val transport = object : OpenAiCompatibleApiTransport {
            override fun send(request: OpenAiCompatibleApiRequest): OpenAiCompatibleApiResponse {
                val scope = assertNotNull(InferenceExecution.current.get())
                val registration = scope.job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
                    if (cause != null) release.countDown()
                }
                try {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    return OpenAiCompatibleApiResponse(200, """{"choices":[{"message":{"content":"{\"status\":\"ok\"}"}}]}""")
                } finally { registration.dispose(); ended.countDown() }
            }
        }
        val service = DefaultModelProfileTestService(ApiKeyStore { "secret" }, startSpacingMillis = 0) {
            RemoteModelClientFactory(it, transport)
        }
        val operation = launch { service.test(profile, null); fail("Cancellation must propagate") }
        withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
        operation.cancelAndJoin()
        assertEquals(0L, ended.count)
    }

    @Test fun credentialIsResolvedOnceAndFrozenForVerification() = runBlocking {
        var reads = 0
        val store = ApiKeyStore { reads++; "original-key" }
        val transport = object : OpenAiCompatibleApiTransport {
            override fun send(request: OpenAiCompatibleApiRequest): OpenAiCompatibleApiResponse {
                assertEquals("Bearer original-key", request.headers["Authorization"])
                assertNotNull(InferenceExecution.current.get())
                return OpenAiCompatibleApiResponse(200, """{"choices":[{"message":{"content":"{\"status\":\"ok\"}"}}]}""")
            }
        }
        val service = DefaultModelProfileTestService(store, startSpacingMillis = 0) { RemoteModelClientFactory(it, transport) }
        assertEquals(ProviderTestResult.Success, service.test(profile, null))
        assertEquals(1, reads)
    }
}
