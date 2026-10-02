package com.hugo.smartexpense.app.tailscale

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hugo.smartexpense.app.modelprofile.ui.ModelProfilesUiState

@Composable
fun TailscaleControl(state: ModelProfilesUiState, onRequest: (Boolean) -> Unit) {
    val profile = state.profiles.firstOrNull {
        state.selectorState.remoteProvidersEnabled && it.id == state.selectorState.selectedRemoteProfileId &&
            it.showTailscaleToggle
    } ?: return
    val status = state.tailscale?.takeIf { it.profile == profile } ?: TailscaleAccessState(profile)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Tailscale — ${profile.displayName}", style = MaterialTheme.typography.titleMedium)
            Text(profile.baseUrl, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(
                    checked = status.connectRequested,
                    onCheckedChange = onRequest,
                    // Off can cancel a pending check and explicitly request disconnect.
                    enabled = !state.busy,
                    modifier = Modifier.semantics { contentDescription = "Request Tailscale for ${profile.displayName}" },
                )
                Text(if (status.connectRequested) "Connection requested" else "Connect on demand")
            }
            Text(status.message, Modifier.semantics { contentDescription = "Tailscale request and endpoint status" })
            Text(status.vpnStatus.label, Modifier.semantics { contentDescription = "VPN detection status" })
            Text(status.endpointStatus.label, Modifier.semantics { contentDescription = "Models endpoint status" })
            status.endpointDetail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("VPN detection cannot identify Tailscale. Endpoint reachability does not verify inference.",
                style = MaterialTheme.typography.bodySmall)
            if (!status.connectRequested) {
                TextButton(onClick = { onRequest(false) }, enabled = !state.busy) {
                    Text("Request Tailscale disconnect")
                }
            }
        }
    }
}
