package com.hugo.smartexpense.app.tailscale

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hugo.smartexpense.app.modelprofile.ui.ModelProfilesUiState
import com.hugo.smartexpense.extraction.*
import com.hugo.smartexpense.app.connectivity.VpnStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TailscaleControlTest {
    @get:Rule val compose = createComposeRule()
    private val allowed = ModelProfile("allowed", "Tailnet", "https://example.test/v1", "model",
        RemoteInputMode.DIRECT_IMAGE, RemoteStructuredOutputFormat.JSON_SCHEMA, "alias", 1, 1, true)
    private val denied = allowed.copy(id = "denied", showTailscaleToggle = false)

    @Test fun selectedEffectiveProfileControlsVisibilityAndAccessibleActions() {
        var state by mutableStateOf(ModelProfilesUiState(
            profiles = listOf(allowed, denied), selectorState = ModelProfileSelectorState(allowed.id, true)))
        var request: Boolean? = null
        compose.setContent { MaterialTheme { TailscaleControl(state) { request = it } } }
        compose.onNodeWithText("Request Tailscale disconnect").performClick()
        check(request == false)
        compose.onNodeWithContentDescription("Request Tailscale for Tailnet").assertIsDisplayed().performClick()
        check(request == true)
        compose.onNodeWithContentDescription("Tailscale request and endpoint status").assertIsDisplayed()
        for (selector in listOf(ModelProfileSelectorState(null, true), ModelProfileSelectorState(allowed.id, false),
            ModelProfileSelectorState(denied.id, true), ModelProfileSelectorState("missing", true))) {
            compose.runOnUiThread { state = state.copy(selectorState = selector) }
            compose.onNodeWithContentDescription("Request Tailscale for Tailnet").assertDoesNotExist()
        }
        compose.runOnUiThread { state = state.copy(selectorState = ModelProfileSelectorState(allowed.id, true)) }
        compose.onNodeWithContentDescription("Request Tailscale for Tailnet").assertIsDisplayed()
    }

    @Test fun requestBusyAndErrorStatusesAreShownAndBusyEditorDisablesActions() {
        var state by mutableStateOf(ModelProfilesUiState(profiles = listOf(allowed),
            selectorState = ModelProfileSelectorState(allowed.id, true)))
        compose.setContent { MaterialTheme { TailscaleControl(state) {} } }
        for (message in listOf("Connecting…", "Disconnecting…", "Connection requested — endpoint reachable", "Endpoint unavailable", "Tailscale unavailable")) {
            compose.runOnUiThread { state = state.copy(tailscale = TailscaleAccessState(allowed, true, true, message)) }
            compose.onNodeWithText(message).assertIsDisplayed()
            compose.onNodeWithContentDescription("Request Tailscale for Tailnet").assertIsEnabled()
        }
        compose.runOnUiThread { state = state.copy(busy = true) }
        compose.onNodeWithContentDescription("Request Tailscale for Tailnet").assertIsNotEnabled()
    }

    @Test fun liveObservationsAreSeparateFromCommandSwitchAndAccessible() {
        var state by mutableStateOf(ModelProfilesUiState(profiles = listOf(allowed),
            selectorState = ModelProfileSelectorState(allowed.id, true),
            tailscale = TailscaleAccessState(allowed, vpnStatus = VpnStatus.DETECTED,
                endpointStatus = EndpointStatus.UNAVAILABLE)))
        compose.setContent { MaterialTheme { TailscaleControl(state) {} } }
        compose.onNodeWithContentDescription("VPN detection status").assertTextEquals("VPN detected")
        compose.onNodeWithContentDescription("Models endpoint status").assertTextEquals("Models endpoint unavailable")
        compose.onNodeWithContentDescription("Request Tailscale for Tailnet").assertIsOff()
        compose.runOnUiThread {
            state = state.copy(tailscale = TailscaleAccessState(allowed,
                vpnStatus = VpnStatus.NOT_DETECTED, endpointStatus = EndpointStatus.REACHABLE))
        }
        compose.onNodeWithText("No VPN detected for this app").assertIsDisplayed()
        compose.onNodeWithText("Models endpoint reachable").assertIsDisplayed()
        compose.onNodeWithContentDescription("Request Tailscale for Tailnet").assertIsOff()
    }
}
