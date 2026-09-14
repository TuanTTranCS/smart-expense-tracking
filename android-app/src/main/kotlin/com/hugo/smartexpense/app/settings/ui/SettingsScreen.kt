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
    onExportProfiles: () -> Unit,
    onImportProfiles: () -> Unit,
    onNavigateBack: () -> Unit,
) {
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
        Row {
            Checkbox(
                checked = settings.reduceOversizedImages,
                onCheckedChange = onReduceOversizedImagesChange,
                modifier = Modifier.semantics { contentDescription = "Reduce oversized receipt images" },
            )
            Text("Reduce input images larger than 200 KB", Modifier.padding(top = 12.dp))
        }
        Text("Oversized images are resized and encoded as JPEG below 200 KB before the next extraction.")
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
