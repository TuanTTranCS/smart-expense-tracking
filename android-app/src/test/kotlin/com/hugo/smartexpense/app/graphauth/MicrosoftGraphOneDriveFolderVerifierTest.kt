package com.hugo.smartexpense.app.graphauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class MicrosoftGraphOneDriveFolderVerifierTest {
    @Test fun sendsReadOnlyRequestToEncodedRootRelativePath() = runTest {
        var captured: GraphHttpRequest? = null
        val verifier = MicrosoftGraphOneDriveFolderVerifier(
            transport = GraphHttpTransport { request ->
                captured = request
                GraphHttpResponse(200, """{"name":"logs","folder":{"childCount":0}}""")
            },
            relativePath = "Documents/Expense logs",
        )

        val result = verifier.verify("sensitive-token")
        val request = requireNotNull(captured)

        assertEquals(OneDriveFolderVerificationResult.Verified("logs"), result)
        assertTrue(request.url.contains("/me/drive/root:/Documents/Expense%20logs"))
        assertTrue(request.url.endsWith("?\$select=id,name,folder,parentReference"))
        assertEquals("Bearer sensitive-token", request.headers["Authorization"])
    }

    @Test fun rejectsAFileResponseWhenAFolderWasExpected() = runTest {
        val verifier = verifier(GraphHttpResponse(200, """{"name":"logs"}"""))
        assertEquals(
            OneDriveFolderVerificationResult.Failed(OneDriveFolderVerificationResult.Failure.INVALID_RESPONSE),
            verifier.verify("token"),
        )
    }

    @Test fun mapsGraphFailuresToTypedRecoveryResults() = runTest {
        val expected = mapOf(
            401 to OneDriveFolderVerificationResult.Failure.RECONNECT_REQUIRED,
            403 to OneDriveFolderVerificationResult.Failure.PERMISSION_DENIED,
            404 to OneDriveFolderVerificationResult.Failure.FOLDER_NOT_FOUND,
            429 to OneDriveFolderVerificationResult.Failure.RATE_LIMITED,
            500 to OneDriveFolderVerificationResult.Failure.SERVICE_FAILURE,
        )
        expected.forEach { (status, failure) ->
            assertEquals(OneDriveFolderVerificationResult.Failed(failure), verifier(GraphHttpResponse(status, "")).verify("token"))
        }
    }

    @Test fun preservesRetryAfterForRateLimitGuidance() = runTest {
        val result = verifier(GraphHttpResponse(429, "", mapOf("retry-after" to "45"))).verify("token")
        assertEquals(
            OneDriveFolderVerificationResult.Failed(
                OneDriveFolderVerificationResult.Failure.RATE_LIMITED,
                retryAfterSeconds = 45,
            ),
            result,
        )
    }

    @Test fun mapsTransportExceptionsWithoutExposingTheToken() = runTest {
        val verifier = MicrosoftGraphOneDriveFolderVerifier(GraphHttpTransport { throw IllegalStateException("Bearer secret") })
        val result = assertIs<OneDriveFolderVerificationResult.Failed>(verifier.verify("secret"))
        assertEquals(OneDriveFolderVerificationResult.Failure.NETWORK_UNAVAILABLE, result.reason)
        assertTrue(result.toString().contains("secret").not())
    }

    private fun verifier(response: GraphHttpResponse) =
        MicrosoftGraphOneDriveFolderVerifier(GraphHttpTransport { response })
}
