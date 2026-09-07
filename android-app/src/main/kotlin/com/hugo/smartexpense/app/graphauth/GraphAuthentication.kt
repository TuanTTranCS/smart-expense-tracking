package com.hugo.smartexpense.app.graphauth

import android.app.Activity

data class GraphAccount(
    val id: String,
    val displayName: String,
)

interface GraphAuthenticationClient {
    suspend fun loadCurrentAccount(): GraphAccount?
    suspend fun signIn(activity: Activity): GraphAccount
    suspend fun acquireAccessTokenSilently(): String
    suspend fun signOut()
}

sealed class GraphAuthenticationException(message: String) : Exception(message) {
    class NotConfigured : GraphAuthenticationException(
        "Microsoft Graph is not configured. Add the Entra client ID and Android redirect URI.",
    )

    class InteractionRequired : GraphAuthenticationException(
        "Your Microsoft session needs attention. Reconnect the account and try again.",
    )

    class Cancelled : GraphAuthenticationException("Microsoft account connection was cancelled.")
    class NetworkUnavailable : GraphAuthenticationException(
        "Microsoft could not be reached. Check the network connection and try again.",
    )

    class ConsentDeclined : GraphAuthenticationException(
        "OneDrive permission was not granted. Reconnect and approve file access.",
    )

    class InvalidConfiguration : GraphAuthenticationException(
        "Microsoft authentication is misconfigured. Check the client ID and Android redirect URI.",
    )

    class Failed : GraphAuthenticationException(
        "Microsoft authentication failed. Try again without changing the receipt data.",
    )
}
