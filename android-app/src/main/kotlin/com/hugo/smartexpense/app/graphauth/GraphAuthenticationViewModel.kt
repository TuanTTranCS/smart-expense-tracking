package com.hugo.smartexpense.app.graphauth

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class GraphAuthenticationStatus {
    INITIALIZING,
    SIGNED_OUT,
    SIGNED_IN,
    INTERACTION_REQUIRED,
    ERROR,
}

data class GraphAuthenticationUiState(
    val status: GraphAuthenticationStatus = GraphAuthenticationStatus.INITIALIZING,
    val accountDisplayName: String? = null,
    val busy: Boolean = true,
    val message: String? = null,
    val folderVerified: Boolean = false,
)

class GraphAuthenticationViewModel(
    private val authenticationClient: GraphAuthenticationClient,
    private val folderVerifier: OneDriveFolderVerifier,
    private val operationDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(GraphAuthenticationUiState())
    val uiState: StateFlow<GraphAuthenticationUiState> = mutableUiState.asStateFlow()

    init {
        refreshAccount()
    }

    fun refreshAccount() = launchOperation {
        val account = authenticationClient.loadCurrentAccount()
        mutableUiState.value = if (account == null) {
            GraphAuthenticationUiState(status = GraphAuthenticationStatus.SIGNED_OUT, busy = false)
        } else {
            signedInState(account, "Microsoft account restored.")
        }
    }

    fun connect(activity: Activity) = launchOperation {
        val account = authenticationClient.signIn(activity)
        mutableUiState.value = signedInState(account, "Microsoft account connected.")
    }

    fun verifyFolder() = launchOperation {
        val token = withContext(operationDispatcher) { authenticationClient.acquireAccessTokenSilently() }
        val result = withContext(operationDispatcher) { folderVerifier.verify(token) }
        mutableUiState.value = when (result) {
            is OneDriveFolderVerificationResult.Verified -> mutableUiState.value.copy(
                status = GraphAuthenticationStatus.SIGNED_IN,
                busy = false,
                folderVerified = true,
                message = "OneDrive folder ${result.folderName} is accessible.",
            )
            is OneDriveFolderVerificationResult.Failed -> result.toUiState(mutableUiState.value)
        }
    }

    fun disconnect() = launchOperation {
        authenticationClient.signOut()
        mutableUiState.value = GraphAuthenticationUiState(
            status = GraphAuthenticationStatus.SIGNED_OUT,
            busy = false,
            message = "Microsoft account disconnected. Local receipt data was preserved.",
        )
    }

    fun clearMessage() {
        mutableUiState.value = mutableUiState.value.copy(message = null)
    }

    private fun launchOperation(block: suspend () -> Unit) {
        if (mutableUiState.value.busy && mutableUiState.value.status != GraphAuthenticationStatus.INITIALIZING) return
        mutableUiState.value = mutableUiState.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try {
                block()
            } catch (error: GraphAuthenticationException) {
                mutableUiState.value = mutableUiState.value.copy(
                    status = if (error is GraphAuthenticationException.InteractionRequired) {
                        GraphAuthenticationStatus.INTERACTION_REQUIRED
                    } else if (mutableUiState.value.accountDisplayName == null) {
                        GraphAuthenticationStatus.SIGNED_OUT
                    } else {
                        GraphAuthenticationStatus.ERROR
                    },
                    busy = false,
                    folderVerified = false,
                    message = error.message,
                )
            } catch (_: Exception) {
                mutableUiState.value = mutableUiState.value.copy(
                    status = GraphAuthenticationStatus.ERROR,
                    busy = false,
                    folderVerified = false,
                    message = "The Microsoft account operation failed. Try again.",
                )
            }
        }
    }

    private fun signedInState(account: GraphAccount, message: String) = GraphAuthenticationUiState(
        status = GraphAuthenticationStatus.SIGNED_IN,
        accountDisplayName = account.displayName,
        busy = false,
        message = message,
    )

    private fun OneDriveFolderVerificationResult.Failed.toUiState(
        current: GraphAuthenticationUiState,
    ): GraphAuthenticationUiState = current.copy(
        status = if (reason == OneDriveFolderVerificationResult.Failure.RECONNECT_REQUIRED) {
            GraphAuthenticationStatus.INTERACTION_REQUIRED
        } else {
            GraphAuthenticationStatus.SIGNED_IN
        },
        busy = false,
        folderVerified = false,
        message = when (reason) {
            OneDriveFolderVerificationResult.Failure.RECONNECT_REQUIRED ->
                "Your Microsoft session expired. Reconnect and try again."
            OneDriveFolderVerificationResult.Failure.PERMISSION_DENIED ->
                "OneDrive access was denied. Reconnect and approve file access."
            OneDriveFolderVerificationResult.Failure.FOLDER_NOT_FOUND ->
                "The configured OneDrive handoff folder was not found."
            OneDriveFolderVerificationResult.Failure.RATE_LIMITED ->
                retryAfterSeconds?.let { "Microsoft Graph is busy. Try again in $it seconds." }
                    ?: "Microsoft Graph is busy. Wait before retrying."
            OneDriveFolderVerificationResult.Failure.INVALID_RESPONSE ->
                "Microsoft Graph did not return the expected OneDrive folder."
            OneDriveFolderVerificationResult.Failure.NETWORK_UNAVAILABLE ->
                "OneDrive could not be reached. Check the network and try again."
            OneDriveFolderVerificationResult.Failure.SERVICE_FAILURE ->
                "Microsoft Graph could not verify the folder. Try again later."
        },
    )

    class Factory(
        private val create: () -> GraphAuthenticationViewModel,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }
}
