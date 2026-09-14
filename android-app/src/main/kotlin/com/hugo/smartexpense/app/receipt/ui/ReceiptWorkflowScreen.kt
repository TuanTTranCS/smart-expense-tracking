package com.hugo.smartexpense.app.receipt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hugo.smartexpense.app.ReceiptReviewState
import com.hugo.smartexpense.app.graphauth.GraphAuthenticationUiState
import com.hugo.smartexpense.app.graphauth.OneDrivePresentation
import com.hugo.smartexpense.app.graphauth.OneDriveRecoveryAction
import com.hugo.smartexpense.app.modelprofile.domain.SelectedReceiptModelProviderResolver
import com.hugo.smartexpense.app.modelprofile.ui.ModelProfilesUiState
import com.hugo.smartexpense.app.modelprofile.ui.ProviderVerificationState
import com.hugo.smartexpense.extraction.ModelProfile

@Composable
fun ReceiptWorkflowScreen(
    profileState: ModelProfilesUiState,
    workflowState: ReceiptWorkflowUiState,
    graphState: GraphAuthenticationUiState,
    oneDrive: OneDrivePresentation,
    onOpenSettings: () -> Unit,
    onSelectLocal: () -> Unit,
    onSelectRemote: (ModelProfile) -> Unit,
    onVerifyProvider: () -> Unit,
    onOneDriveRecovery: (OneDriveRecoveryAction) -> Unit,
    onChooseReceipt: () -> Unit,
    onReviewChange: (ReceiptReviewState) -> Unit,
    onExport: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Smart Expense", style = MaterialTheme.typography.headlineMedium)
            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier.semantics { contentDescription = "Open settings" },
            ) { Text("Settings") }
        }
        CompactProviderSelector(
            state = profileState,
            onSelectLocal = onSelectLocal,
            onSelectRemote = onSelectRemote,
        )
        val selectedId = profileState.selectedEffectiveProviderId()
        profileState.verification?.takeIf { it.providerId == selectedId }?.let {
            ProviderVerificationMessage(it)
        }
        OutlinedButton(
            onClick = onVerifyProvider,
            enabled = !profileState.busy && profileState.verification?.busy != true,
            modifier = Modifier.semantics { contentDescription = "Verify selected provider" },
        ) { Text(if (profileState.verification?.busy == true) "Verifying" else "Verify profile") }

        OneDriveStatusCard(oneDrive, graphState.accountDisplayName, onOneDriveRecovery)

        Button(
            enabled = !workflowState.extracting && !workflowState.exporting && !profileState.busy,
            onClick = onChooseReceipt,
        ) { Text(if (workflowState.extracting) "Extracting" else "Choose receipt") }
        workflowState.review?.let {
            ReceiptReviewEditor(it, workflowState.exporting, oneDrive.readyForExport, onReviewChange, onExport)
        }
    }
}

private fun ModelProfilesUiState.selectedEffectiveProviderId(): String =
    profiles.firstOrNull {
        selectorState.remoteProvidersEnabled && it.id == selectorState.selectedRemoteProfileId
    }?.id ?: SelectedReceiptModelProviderResolver.BUILT_IN_LOCAL_PROVIDER.id

@Composable
private fun CompactProviderSelector(
    state: ModelProfilesUiState,
    onSelectLocal: () -> Unit,
    onSelectRemote: (ModelProfile) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.profiles.firstOrNull {
        state.selectorState.remoteProvidersEnabled && it.id == state.selectorState.selectedRemoteProfileId
    }
    Text("Provider for next receipt", style = MaterialTheme.typography.titleMedium)
    OutlinedButton(
        onClick = { expanded = true }, enabled = !state.busy,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Select receipt provider" },
    ) { Text(selected?.displayName ?: "On-device Gemma") }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text("On-device Gemma") },
            onClick = { expanded = false; onSelectLocal() },
        )
        state.profiles.forEach { profile ->
            DropdownMenuItem(
                text = {
                    Text(
                        if (state.selectorState.remoteProvidersEnabled) profile.displayName
                        else "${profile.displayName} — enable in Settings",
                    )
                },
                enabled = state.selectorState.remoteProvidersEnabled,
                onClick = { expanded = false; onSelectRemote(profile) },
            )
        }
    }
    Text(state.effectiveProviderSummary, style = MaterialTheme.typography.bodySmall)
    if (!state.selectorState.remoteProvidersEnabled && state.profiles.isNotEmpty()) {
        Text("Remote providers must be enabled in Settings before selection.")
    }
}

@Composable
private fun ProviderVerificationMessage(state: ProviderVerificationState) {
    Card(Modifier.fillMaxWidth()) { Text(state.message, Modifier.padding(12.dp)) }
}

@Composable
private fun OneDriveStatusCard(
    state: OneDrivePresentation,
    accountName: String?,
    onRecovery: (OneDriveRecoveryAction) -> Unit,
) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "OneDrive export status" }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(state.summary, style = MaterialTheme.typography.titleMedium)
            accountName?.let { Text(it) }
            Text(state.detail)
            state.recoveryLabel?.let { label ->
                Button(onClick = { onRecovery(state.recoveryAction) }) { Text(label) }
            }
        }
    }
}

@Composable
private fun ReceiptReviewEditor(
    state: ReceiptReviewState,
    exporting: Boolean,
    canExport: Boolean,
    onStateChange: (ReceiptReviewState) -> Unit,
    onExport: () -> Unit,
) {
    Text(if (state.manualEntryRequired) "Manual receipt entry" else "Review extracted receipt", style = MaterialTheme.typography.headlineSmall)
    Text(state.message)
    ReviewField("Receipt date (yyyy-MM-dd)", state.receiptDate) { onStateChange(state.copy(receiptDate = it)) }
    ReviewField("Merchant", state.merchantName) { onStateChange(state.copy(merchantName = it)) }
    ReviewField("Total amount", state.totalAmount) { onStateChange(state.copy(totalAmount = it)) }
    ReviewField("Currency", state.currency) { onStateChange(state.copy(currency = it.uppercase())) }
    ReviewField("Merchant location", state.merchantLocation) { onStateChange(state.copy(merchantLocation = it)) }
    Text("Extraction status: ${state.extractionStatus}")
    Text("Confidence: ${state.confidence.ifBlank { "not available" }}")
    if (state.rawModelOutput.isNotBlank()) {
        Text("Raw model output", style = MaterialTheme.typography.titleMedium)
        Text(state.rawModelOutput)
    }
    Button(enabled = canExport && !exporting && !state.exportComplete, onClick = onExport) {
        Text(when {
            exporting -> "Exporting"
            state.exportComplete -> "Exported"
            state.exportExpenseId != null -> "Retry export"
            else -> "Confirm and export"
        })
    }
    if (!canExport) Text("Connect and verify OneDrive access before exporting.")
}

@Composable
private fun ReviewField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(value, onValueChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}
