package com.hugo.smartexpense.app.graphauth

import android.app.Activity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GraphAuthenticationViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun restoresCachedAccountOnStartup() = runTest(dispatcher) {
        val viewModel = GraphAuthenticationViewModel(
            FakeAuthenticationClient(account = ACCOUNT),
            OneDriveFolderVerifier { OneDriveFolderVerificationResult.Verified("logs") },
            dispatcher,
        )
        advanceUntilIdle()

        assertEquals(GraphAuthenticationStatus.SIGNED_IN, viewModel.uiState.value.status)
        assertEquals("hugo@example.com", viewModel.uiState.value.accountDisplayName)
        assertFalse(viewModel.uiState.value.busy)
    }

    @Test fun connectsAndDisconnectsWithoutChangingLocalData() = runTest(dispatcher) {
        val client = FakeAuthenticationClient()
        val viewModel = GraphAuthenticationViewModel(
            client,
            OneDriveFolderVerifier { OneDriveFolderVerificationResult.Verified("logs") },
            dispatcher,
        )
        advanceUntilIdle()

        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        viewModel.connect(activity)
        advanceUntilIdle()
        assertEquals(GraphAuthenticationStatus.SIGNED_IN, viewModel.uiState.value.status)

        viewModel.disconnect()
        advanceUntilIdle()
        assertEquals(GraphAuthenticationStatus.SIGNED_OUT, viewModel.uiState.value.status)
        assertTrue(client.signedOut)
        assertTrue(viewModel.uiState.value.message!!.contains("preserved"))
    }

    @Test fun verifiesFolderUsingASilentToken() = runTest(dispatcher) {
        var receivedToken: String? = null
        val viewModel = GraphAuthenticationViewModel(
            FakeAuthenticationClient(account = ACCOUNT),
            OneDriveFolderVerifier { token ->
                receivedToken = token
                OneDriveFolderVerificationResult.Verified("logs")
            },
            dispatcher,
        )
        advanceUntilIdle()

        viewModel.verifyFolder()
        advanceUntilIdle()

        assertEquals("access-token", receivedToken)
        assertTrue(viewModel.uiState.value.folderVerified)
        assertTrue(viewModel.uiState.value.message!!.contains("accessible"))
    }

    @Test fun silentAuthenticationFailureRequiresExplicitReconnect() = runTest(dispatcher) {
        val client = FakeAuthenticationClient(account = ACCOUNT, tokenFailure = GraphAuthenticationException.InteractionRequired())
        val viewModel = GraphAuthenticationViewModel(
            client,
            OneDriveFolderVerifier { OneDriveFolderVerificationResult.Verified("logs") },
            dispatcher,
        )
        advanceUntilIdle()

        viewModel.verifyFolder()
        advanceUntilIdle()

        assertEquals(GraphAuthenticationStatus.INTERACTION_REQUIRED, viewModel.uiState.value.status)
        assertFalse(viewModel.uiState.value.folderVerified)
    }

    @Test fun ignoresADuplicateOperationWhileVerificationIsBusy() = runTest(dispatcher) {
        val client = FakeAuthenticationClient(account = ACCOUNT)
        val viewModel = GraphAuthenticationViewModel(
            client,
            OneDriveFolderVerifier { OneDriveFolderVerificationResult.Verified("logs") },
            dispatcher,
        )
        advanceUntilIdle()

        viewModel.verifyFolder()
        viewModel.verifyFolder()
        advanceUntilIdle()

        assertEquals(1, client.tokenRequests)
    }

    private companion object {
        val ACCOUNT = GraphAccount("account-id", "hugo@example.com")
    }
}

private class FakeAuthenticationClient(
    var account: GraphAccount? = null,
    private val tokenFailure: GraphAuthenticationException? = null,
) : GraphAuthenticationClient {
    var signedOut = false
    var tokenRequests = 0

    override suspend fun loadCurrentAccount() = account

    override suspend fun signIn(activity: Activity): GraphAccount = GraphAccount("new-account", "hugo@example.com")
        .also { account = it }

    override suspend fun acquireAccessTokenSilently(): String {
        tokenRequests++
        tokenFailure?.let { throw it }
        return "access-token"
    }

    override suspend fun signOut() {
        account = null
        signedOut = true
    }
}
