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
                    return OpenAiCompatibleApiResponse(200, """{"choices":[{"message":{"role":"assistant","content":"{\"status\":\"ok\"}"}}]}""")
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
                return OpenAiCompatibleApiResponse(200, """{"choices":[{"message":{"role":"assistant","content":"{\"status\":\"ok\"}"}}]}""")
            }
        }
        val service = DefaultModelProfileTestService(store, startSpacingMillis = 0) { RemoteModelClientFactory(it, transport) }
        assertEquals(ProviderTestResult.Success, service.test(profile, null))
        assertEquals(1, reads)
    }
    @Test fun explicitClearSuppressesSavedRemoteCredential() = runBlocking {
        var reads = 0
        var calls = 0
        val transport = object : OpenAiCompatibleApiTransport {
            override fun send(request: OpenAiCompatibleApiRequest): OpenAiCompatibleApiResponse { calls++; error("No credential must fail before networking") }
        }
        val service = DefaultModelProfileTestService(ApiKeyStore { reads++; "saved-key" }, startSpacingMillis = 0) {
            RemoteModelClientFactory(it, transport)
        }
        assertTrue(service.test(profile, "") is ProviderTestResult.Failed)
        assertEquals(0, reads)
        assertEquals(0, calls)
    }

    @Test fun samePhoneClearAndCredentialFreeVerificationSendNoAuthorization() = runBlocking {
        val phone = profile.copy(baseUrl = "http://127.0.0.1:8080/v1", category = ApiProfileCategory.SAME_PHONE_SERVER,
            structuredOutputFormat = RemoteStructuredOutputFormat.PROMPT_ONLY_JSON)
        val instance = "11111111-1111-1111-1111-111111111111"
        val requestId = "22222222-2222-2222-2222-222222222222"
        var reads = 0
        val requests = mutableListOf<OpenAiCompatibleApiRequest>()
        val transport = object : OpenAiCompatibleApiTransport {
            override fun send(request: OpenAiCompatibleApiRequest): OpenAiCompatibleApiResponse {
                requests += request
                assertEquals(null, request.headers["Authorization"])
                return if (request.url.endsWith("/requests")) OpenAiCompatibleApiResponse(201,
                    """{"instanceId":"$instance","requestId":"$requestId","runtimeState":"available"}""")
                else {
                    assertFalse(request.body.contains("response_format"))
                    OpenAiCompatibleApiResponse(200,
                        """{"choices":[{"message":{"role":"assistant","content":"{\"status\":\"ok\"}"}}]}""",
                        mapOf("X-Companion-Instance" to instance, "X-Request-Id" to requestId))
                }
            }
        }
        val service = DefaultModelProfileTestService(ApiKeyStore { reads++; "saved-key" }, startSpacingMillis = 0) {
            RemoteModelClientFactory(it, transport)
        }
        assertEquals(ProviderTestResult.Success, service.test(phone, ""))
        assertEquals(0, reads)
        assertEquals(2, requests.size)
        assertEquals("http://127.0.0.1:8080/v1/requests", requests.first().url)
        assertEquals("http://127.0.0.1:8080/v1/chat/completions", requests.last().url)
        val credentialFreeService = DefaultModelProfileTestService(ApiKeyStore { null }, startSpacingMillis = 0) {
            RemoteModelClientFactory(it, transport)
        }
        assertEquals(ProviderTestResult.Success, credentialFreeService.test(phone, null))
        assertEquals(4, requests.size)
    }

}
