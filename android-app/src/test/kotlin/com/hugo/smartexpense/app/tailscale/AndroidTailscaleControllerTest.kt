package com.hugo.smartexpense.app.tailscale

import android.app.Application
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.hugo.smartexpense.extraction.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
class AndroidTailscaleControllerTest {
    @Test fun dispatchesOnlyPackageTargetedSupportedActionsWithoutReceiptExtras() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val controller = AndroidTailscaleController(context)
        val profile = profile()
        for ((on, action) in listOf(true to AndroidTailscaleController.CONNECT_ACTION, false to AndroidTailscaleController.DISCONNECT_ACTION)) {
            val intent = Intent(action).setPackage(AndroidTailscaleController.PACKAGE)
            val receiver = ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = AndroidTailscaleController.PACKAGE
                    name = "IPNReceiver"
                    exported = true
                    enabled = true
                    applicationInfo = ApplicationInfo().apply { packageName = AndroidTailscaleController.PACKAGE }
                }
            }
            @Suppress("DEPRECATION")
            shadowOf(context.packageManager).addResolveInfoForIntent(intent, receiver)
            controller.request(profile, on)
            val sent = shadowOf(context).broadcastIntents.last()
            assertEquals(action, sent.action)
            assertEquals(AndroidTailscaleController.PACKAGE, sent.`package`)
            assertNull(sent.extras)
        }
    }

    @Test fun noCompatibleReceiverIsUnavailable() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        assertFailsWith<TailscaleUnavailableException> { AndroidTailscaleController(context).request(profile(), true) }
    }

    private fun profile() = ModelProfile("saved", "Saved", "https://example.test/v1", "model",
        RemoteInputMode.DIRECT_IMAGE, RemoteStructuredOutputFormat.JSON_SCHEMA, "alias", 1, 1, true)
}
