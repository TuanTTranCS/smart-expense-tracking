package com.hugo.smartexpense.app.settings.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.Role
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
import com.hugo.smartexpense.app.settings.data.AppThemeMode

@OptIn(ExperimentalLayoutApi::class)
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
    onThemeModeChange: (AppThemeMode) -> Unit = {},
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = ::handleBack,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Back to receipt workflow" },
            ) {
                Icon(settingsBackIcon(), null, Modifier.size(24.dp))
                Text("Back", Modifier.padding(start = 8.dp))
            }
            Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        }
        ThemeSettingsCard(settings.themeMode, onThemeModeChange)
        SettingsCard("Models and providers") {
            ModelProfilesPanel(
                state = profileState,
                viewModel = profilesViewModel,
                onExportProfiles = onExportProfiles,
                onImportProfiles = onImportProfiles,
            )
        }
        SettingsCard("Batch processing") {
            Text("Remote requests: choose 1–3 concurrent expenses. On-device processing always uses one.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..3).forEach { count ->
                    TextButton(onClick = { onBatchMaxConcurrencyChange(count) }, modifier = Modifier.heightIn(min = 48.dp)) {
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
        }
        SettingsCard("Receipt image preprocessing") {
            SettingsToggleRow(settings.reduceOversizedImages, onReduceOversizedImagesChange,
                "Reduce input images larger than 200 KB",
                "Oversized images are resized and encoded as JPEG below 200 KB before the next extraction.",
                "Reduce oversized receipt images")
        }
        SettingsCard("Debug") {
            SettingsToggleRow(settings.debugOutputEnabled, onDebugOutputEnabledChange, "Debug output",
                "Show raw extraction responses and generated handoff JSON on the receipt screen.", "Debug output")
        }
        SettingsCard("Device name") {
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
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard unsaved changes?") },
            text = { Text("Your profile edits and unsaved API key entry will be lost.") },
            confirmButton = {
                Button(onClick = { confirmDiscard = false; profilesViewModel.cancelEdit() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep editing") }
            },
        )
    }
}

@Composable
internal fun ThemeSettingsCard(themeMode: AppThemeMode, onThemeModeChange: (AppThemeMode) -> Unit) {
    SettingsCard("Appearance") {
        Text("Theme", style = MaterialTheme.typography.titleMedium)
        Text("System follows your device's light or dark appearance.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.selectableGroup()) {
            AppThemeMode.entries.forEach { mode ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = themeMode == mode, role = Role.RadioButton,
                            onClick = { onThemeModeChange(mode) })
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = themeMode == mode, onClick = null)
                    Text(when (mode) {
                        AppThemeMode.LIGHT -> "Light"
                        AppThemeMode.DARK -> "Dark"
                        AppThemeMode.SYSTEM -> "System"
                    })
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(settingsGroupIcon(), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            content()
        }
    }
}

@Composable
internal fun SettingsToggleRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit,
    title: String, description: String, accessibilityLabel: String, enabled: Boolean = true, checkbox: Boolean = true) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(checked, enabled = enabled, role = if (checkbox) Role.Checkbox else Role.Switch, onValueChange = onCheckedChange)
        .semantics { contentDescription = accessibilityLabel }.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (checkbox) Checkbox(checked, onCheckedChange = null, enabled = enabled)
        else Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
internal fun settingsGroupIcon(): ImageVector {
    val color = MaterialTheme.colorScheme.primary
    return remember(color) { ImageVector.Builder("Settings group", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(color)) {
        moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 7f); lineTo(3f, 7f); close()
        moveTo(3f, 11f); lineTo(21f, 11f); lineTo(21f, 13f); lineTo(3f, 13f); close()
        moveTo(3f, 17f); lineTo(21f, 17f); lineTo(21f, 19f); lineTo(3f, 19f); close()
        moveTo(7f, 3f); lineTo(9f, 3f); lineTo(9f, 9f); lineTo(7f, 9f); close()
        moveTo(15f, 9f); lineTo(17f, 9f); lineTo(17f, 15f); lineTo(15f, 15f); close()
        moveTo(7f, 15f); lineTo(9f, 15f); lineTo(9f, 21f); lineTo(7f, 21f); close()
    }
}.build() }
}

@Composable
private fun settingsBackIcon(): ImageVector {
    val color = MaterialTheme.colorScheme.onSurface
    return remember(color) { ImageVector.Builder("Back", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(color)) {
        moveTo(20f, 11f); lineTo(7.83f, 11f); lineTo(13.42f, 5.41f); lineTo(12f, 4f)
        lineTo(4f, 12f); lineTo(12f, 20f); lineTo(13.42f, 18.59f); lineTo(7.83f, 13f)
        lineTo(20f, 13f); close()
    }
}.build() }
}
