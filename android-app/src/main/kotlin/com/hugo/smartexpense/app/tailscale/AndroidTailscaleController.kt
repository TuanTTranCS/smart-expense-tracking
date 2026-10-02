package com.hugo.smartexpense.app.tailscale

import android.content.Context
import android.content.Intent
import com.hugo.smartexpense.extraction.ModelProfile

class AndroidTailscaleController(context: Context) : TailscaleController {
    private val context = context.applicationContext

    override fun request(profile: ModelProfile, connect: Boolean) {
        val intent = Intent(if (connect) CONNECT_ACTION else DISCONNECT_ACTION).setPackage(PACKAGE)
        @Suppress("DEPRECATION")
        val receivers = context.packageManager.queryBroadcastReceivers(intent, 0)
        if (receivers.none { it.activityInfo?.exported == true && it.activityInfo?.enabled == true }) {
            throw TailscaleUnavailableException(
                "Tailscale unavailable. Install or update Tailscale, open it to sign in and allow VPN access, then retry.",
            )
        }
        context.sendBroadcast(intent)
    }

    companion object {
        const val PACKAGE = "com.tailscale.ipn"
        const val CONNECT_ACTION = "$PACKAGE.CONNECT_VPN"
        const val DISCONNECT_ACTION = "$PACKAGE.DISCONNECT_VPN"
    }
}
