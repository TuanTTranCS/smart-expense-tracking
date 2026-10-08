package com.hugo.smartexpense.app.settings.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hugo.smartexpense.app.modelprofile.ui.ModelProfilesPanel
import com.hugo.smartexpense.app.modelprofile.ui.ModelProfilesUiState
import com.hugo.smartexpense.app.modelprofile.ui.ModelProfilesViewModel
import com.hugo.smartexpense.app.settings.data.AppSettings

@Composable
fun SettingsScreen(
    profileState: ModelProfilesUiState,
    profilesViewModel: ModelProfilesViewModel,
    settings: AppSettings,
    onReduceOversizedImagesChange: (Boolean) -> Unit,
    onDebugOutputEnabledChange: (Boolean) -> Unit,
    onDeviceNameChange: (String) -> Unit,
    onExportProfiles: () -> Unit,
    onImportProfiles: () -> Unit,
    onNavigateBack: () -> Unit,
    onBatchMaxConcurrencyChange: (Int) -> Unit = {},
    onBatchStartSpacingMillisChange: (Long) -> Unit = {},
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, profilesViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) profilesViewModel.cancelModelDiscovery()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            profilesViewModel.cancelModelDiscovery()
        }
    }
    var confirmDiscard by remember { mutableStateOf(false) }
    fun handleBack() {
        val editor = profileState.editor
        when {
            editor?.hasUnsavedChanges == true -> confirmDiscard = true
            editor != null -> profilesViewModel.cancelEdit()
            else -> onNavigateBack()
        }
    }
    BackHandler(onBack = ::handleBack)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(
                onClick = ::handleBack,
                modifier = Modifier.semantics { contentDescription = "Back to receipt workflow" },
            ) { Text("Back") }
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
        }
        ModelProfilesPanel(
            state = profileState,
            viewModel = profilesViewModel,
            onExportProfiles = onExportProfiles,
            onImportProfiles = onImportProfiles,
        )
        Text("Receipt image preprocessing", style = MaterialTheme.typography.headlineSmall)
        Text("Batch processing", style = MaterialTheme.typography.headlineSmall)
        Text("Remote requests: choose 1–3 concurrent expenses. On-device processing always uses one.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..3).forEach { count ->
                TextButton(onClick = { onBatchMaxConcurrencyChange(count) }) {
                    Text(if (settings.batchMaxConcurrency == count) "$count ✓" else "$count")
                }
            }
        }
        var spacing by remember(settings.batchStartSpacingMillis) { mutableStateOf(settings.batchStartSpacingMillis.toString()) }
        OutlinedTextField(
            value = spacing,
            onValueChange = { value ->
                spacing = value
                value.toLongOrNull()?.takeIf { it in 0..60_000 }?.let(onBatchStartSpacingMillisChange)
            },
            label = { Text("Minimum request start interval (milliseconds)") },
            supportingText = { Text("Default 3500 ms. Match concurrency to your server's prediction capacity. On-device has no fixed delay.") },
            isError = spacing.toLongOrNull()?.let { it !in 0..60_000 } != false,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row {
            Checkbox(
                checked = settings.reduceOversizedImages,
                onCheckedChange = onReduceOversizedImagesChange,
                modifier = Modifier.semantics { contentDescription = "Reduce oversized receipt images" },
            )
            Text("Reduce input images larger than 200 KB", Modifier.padding(top = 12.dp))
        }
        Text("Oversized images are resized and encoded as JPEG below 200 KB before the next extraction.")
        Row {
            Checkbox(
                checked = settings.debugOutputEnabled,
                onCheckedChange = onDebugOutputEnabledChange,
                modifier = Modifier.semantics { contentDescription = "Debug output" },
            )
            Text("Debug output", Modifier.padding(top = 12.dp))
        }
        Text("Show raw extraction responses and generated handoff JSON on the receipt screen.")
        Text("Device name", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = settings.deviceNameOverride,
            onValueChange = onDeviceNameChange,
            label = { Text("Custom device name") },
            placeholder = { Text(settings.detectedDeviceName) },
            supportingText = { Text("Exported as sourceDeviceName. Clear to use the detected name: ${settings.detectedDeviceName}") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Custom device name" },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard unsaved changes?") },
            text = { Text("Your profile edits and unsaved API key entry will be lost.") },
            confirmButton = {
                Button(onClick = { confirmDiscard = false; profilesViewModel.cancelEdit() }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
            },
        )
    }
}
