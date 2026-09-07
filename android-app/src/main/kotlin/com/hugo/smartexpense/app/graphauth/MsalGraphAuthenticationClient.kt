package com.hugo.smartexpense.app.graphauth

import android.app.Activity
import android.content.Context
import com.hugo.smartexpense.app.R
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.IPublicClientApplication
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.SignInParameters
import com.microsoft.identity.client.SilentAuthenticationCallback
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalServiceException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MsalGraphAuthenticationClient(
    context: Context,
) : GraphAuthenticationClient {
    private val appContext = context.applicationContext
    private val application = AtomicReference<ISingleAccountPublicClientApplication?>()

    override suspend fun loadCurrentAccount(): GraphAccount? {
        val app = getApplication()
        return suspendCancellableCoroutine { continuation ->
            app.getCurrentAccountAsync(object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
                override fun onAccountLoaded(activeAccount: IAccount?) {
                    if (continuation.isActive) continuation.resume(activeAccount?.toGraphAccount())
                }

                override fun onAccountChanged(priorAccount: IAccount?, currentAccount: IAccount?) = Unit

                override fun onError(exception: MsalException) {
                    if (continuation.isActive) continuation.resumeWithException(exception.toSafeGraphException())
                }
            })
        }
    }

    override suspend fun signIn(activity: Activity): GraphAccount {
        val app = getApplication()
        return suspendCancellableCoroutine { continuation ->
            val callback = object : AuthenticationCallback {
                override fun onSuccess(authenticationResult: IAuthenticationResult) {
                    if (continuation.isActive) continuation.resume(authenticationResult.account.toGraphAccount())
                }

                override fun onError(exception: MsalException) {
                    if (continuation.isActive) continuation.resumeWithException(exception.toSafeGraphException())
                }

                override fun onCancel() {
                    if (continuation.isActive) continuation.resumeWithException(GraphAuthenticationException.Cancelled())
                }
            }
            val parameters = SignInParameters.builder()
                .withActivity(activity)
                .withScopes(SCOPES)
                .withCallback(callback)
                .build()
            app.signIn(parameters)
        }
    }

    override suspend fun acquireAccessTokenSilently(): String {
        val app = getApplication()
        val account = currentMsalAccount(app) ?: throw GraphAuthenticationException.InteractionRequired()
        return suspendCancellableCoroutine { continuation ->
            val callback = object : SilentAuthenticationCallback {
                override fun onSuccess(authenticationResult: IAuthenticationResult) {
                    if (continuation.isActive) continuation.resume(authenticationResult.accessToken)
                }

                override fun onError(exception: MsalException) {
                    if (continuation.isActive) continuation.resumeWithException(exception.toSafeGraphException())
                }
            }
            val parameters = AcquireTokenSilentParameters.Builder()
                .forAccount(account)
                .fromAuthority(account.authority)
                .withScopes(SCOPES)
                .forceRefresh(false)
                .withCallback(callback)
                .build()
            app.acquireTokenSilentAsync(parameters)
        }
    }

    override suspend fun signOut() {
        val app = getApplication()
        suspendCancellableCoroutine { continuation ->
            app.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
                override fun onSignOut() {
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onError(exception: MsalException) {
                    if (continuation.isActive) continuation.resumeWithException(exception.toSafeGraphException())
                }
            })
        }
    }

    private suspend fun getApplication(): ISingleAccountPublicClientApplication {
        application.get()?.let { return it }
        ensureConfigured()
        val created = suspendCancellableCoroutine { continuation ->
            PublicClientApplication.createSingleAccountPublicClientApplication(
                appContext,
                R.raw.auth_config_single_account,
                object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                    override fun onCreated(application: ISingleAccountPublicClientApplication) {
                        if (continuation.isActive) continuation.resume(application)
                    }

                    override fun onError(exception: MsalException) {
                        if (continuation.isActive) continuation.resumeWithException(exception.toSafeGraphException())
                    }
                },
            )
        }
        application.compareAndSet(null, created)
        return application.get() ?: created
    }

    private suspend fun currentMsalAccount(app: ISingleAccountPublicClientApplication): IAccount? =
        suspendCancellableCoroutine { continuation ->
            app.getCurrentAccountAsync(object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
                override fun onAccountLoaded(activeAccount: IAccount?) {
                    if (continuation.isActive) continuation.resume(activeAccount)
                }

                override fun onAccountChanged(priorAccount: IAccount?, currentAccount: IAccount?) = Unit

                override fun onError(exception: MsalException) {
                    if (continuation.isActive) continuation.resumeWithException(exception.toSafeGraphException())
                }
            })
        }

    private fun ensureConfigured() {
        val raw = appContext.resources.openRawResource(R.raw.auth_config_single_account)
            .bufferedReader()
            .use { it.readText() }
        if (raw.contains(PLACEHOLDER_CLIENT_ID) || raw.contains("REPLACE_WITH_")) {
            throw GraphAuthenticationException.NotConfigured()
        }
    }

    private fun IAccount.toGraphAccount(): GraphAccount = GraphAccount(
        id = id,
        displayName = username.takeIf(String::isNotBlank) ?: "Personal Microsoft account",
    )

    private fun MsalException.toSafeGraphException(): GraphAuthenticationException = when {
        this is MsalUiRequiredException -> GraphAuthenticationException.InteractionRequired()
        this is MsalClientException && errorCode == MsalClientException.DEVICE_NETWORK_NOT_AVAILABLE ->
            GraphAuthenticationException.NetworkUnavailable()
        this is MsalServiceException && (
            errorCode.contains("consent", ignoreCase = true) ||
                errorCode.equals("access_denied", ignoreCase = true)
            ) ->
            GraphAuthenticationException.ConsentDeclined()
        this is MsalServiceException && errorCode in INVALID_CONFIGURATION_ERROR_CODES ->
            GraphAuthenticationException.InvalidConfiguration()
        this is MsalClientException -> GraphAuthenticationException.InvalidConfiguration()
        else -> GraphAuthenticationException.Failed()
    }

    private companion object {
        const val PLACEHOLDER_CLIENT_ID = "00000000-0000-0000-0000-000000000000"
        val SCOPES = listOf("Files.ReadWrite")
        val INVALID_CONFIGURATION_ERROR_CODES = setOf("invalid_client", "unauthorized_client", "invalid_request")
    }
}
