package com.hugo.smartexpense.app.graphauth

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MicrosoftAccountPanel(
    state: GraphAuthenticationUiState,
    activity: Activity,
    viewModel: GraphAuthenticationViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("Microsoft OneDrive", style = MaterialTheme.typography.headlineSmall)
        when (state.status) {
            GraphAuthenticationStatus.INITIALIZING -> Text("Loading Microsoft account…")
            GraphAuthenticationStatus.SIGNED_OUT -> Button(
                enabled = !state.busy,
                onClick = { viewModel.connect(activity) },
            ) { Text("Connect Microsoft account") }
            GraphAuthenticationStatus.INTERACTION_REQUIRED -> {
                Text("Reconnect your personal Microsoft account to continue.")
                Button(enabled = !state.busy, onClick = { viewModel.connect(activity) }) { Text("Reconnect") }
            }
            GraphAuthenticationStatus.SIGNED_IN,
            GraphAuthenticationStatus.ERROR,
            -> {
                Text("Connected: ${state.accountDisplayName ?: "Personal Microsoft account"}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !state.busy, onClick = viewModel::verifyFolder) {
                        Text(if (state.folderVerified) "Verified" else "Verify OneDrive access")
                    }
                    OutlinedButton(enabled = !state.busy, onClick = viewModel::disconnect) {
                        Text("Disconnect")
                    }
                }
            }
        }
        state.message?.let { Text(it) }
        Text(
            "Folder: ${MicrosoftGraphOneDriveFolderVerifier.DEFAULT_RELATIVE_PATH}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
