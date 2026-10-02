package com.hugo.smartexpense.app.connectivity

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.annotation.Config
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AndroidVpnStatusSourceTest {
    @Suppress("DEPRECATION")
    @Test fun detectsVpnSnapshotIgnoresWifiTracksLossAndUnregistersCallback() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val shadow = shadowOf(manager)
        val wifi = ShadowNetwork.newInstance(10)
        val vpn = ShadowNetwork.newInstance(11)
        shadow.clearAllNetworks()
        shadow.addNetwork(wifi, ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_WIFI, 0, true, true))
        shadow.setNetworkCapabilities(wifi, NetworkCapabilities().also {
            shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        })
        val statuses = mutableListOf<VpnStatus>()
        val job = backgroundScope.launch { AndroidVpnStatusSource(context).observe().collect { statuses += it } }
        runCurrent()
        assertEquals(VpnStatus.NOT_DETECTED, statuses.last())
        val callback = shadow.networkCallbacks.single()
        callback.onAvailable(vpn)
        runCurrent()
        assertEquals(VpnStatus.DETECTED, statuses.last())
        val secondVpn = ShadowNetwork.newInstance(12)
        callback.onAvailable(secondVpn)
        callback.onLost(vpn)
        runCurrent()
        assertEquals(VpnStatus.DETECTED, statuses.last())
        callback.onLost(secondVpn)
        runCurrent()
        assertEquals(VpnStatus.NOT_DETECTED, statuses.last())
        job.cancel()
        runCurrent()
        assertTrue(shadow.networkCallbacks.isEmpty())
        shadow.addNetwork(vpn, ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_VPN, 0, true, true))
        shadow.setNetworkCapabilities(vpn, NetworkCapabilities().also {
            shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_VPN)
        })
        backgroundScope.launch { AndroidVpnStatusSource(context).observe().collect { statuses += it } }
        runCurrent()
        assertEquals(VpnStatus.DETECTED, statuses.last())
    }
}
