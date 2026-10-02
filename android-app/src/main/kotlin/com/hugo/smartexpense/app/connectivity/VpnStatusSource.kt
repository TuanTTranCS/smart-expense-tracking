package com.hugo.smartexpense.app.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

enum class VpnStatus(val label: String) {
    UNKNOWN("VPN detection unavailable"),
    DETECTED("VPN detected"),
    NOT_DETECTED("No VPN detected for this app"),
}

/** Reports VPN networks visible to this app, never their owner or tailnet health. */
fun interface VpnStatusSource {
    fun observe(): Flow<VpnStatus>
}

object UnknownVpnStatusSource : VpnStatusSource {
    override fun observe(): Flow<VpnStatus> = flowOf(VpnStatus.UNKNOWN)
}

class AndroidVpnStatusSource(context: Context) : VpnStatusSource {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun observe(): Flow<VpnStatus> = callbackFlow {
        if (manager == null) {
            trySend(VpnStatus.UNKNOWN)
            close()
            return@callbackFlow
        }
        val lock = Any()
        val networks = mutableSetOf<Network>()
        fun publish() {
            trySend(if (networks.isEmpty()) VpnStatus.NOT_DETECTED else VpnStatus.DETECTED)
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = synchronized(lock) {
                networks += network
                publish()
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = synchronized(lock) {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) networks += network
                else networks -= network
                publish()
            }
            override fun onLost(network: Network) = synchronized(lock) {
                networks -= network
                publish()
            }
        }
        var registered = false
        try {
            synchronized(lock) {
                manager.registerNetworkCallback(
                    NetworkRequest.Builder().clearCapabilities().addTransportType(NetworkCapabilities.TRANSPORT_VPN).build(),
                    callback,
                )
                registered = true
                manager.allNetworks.filterTo(networks) {
                    manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                }
                publish()
            }
        } catch (_: Exception) {
            trySend(VpnStatus.UNKNOWN)
            close()
        }
        awaitClose {
            if (registered) runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged()
}
