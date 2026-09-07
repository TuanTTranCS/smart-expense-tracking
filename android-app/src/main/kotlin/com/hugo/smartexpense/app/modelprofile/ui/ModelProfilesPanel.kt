package com.hugo.smartexpense.app.modelprofile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hugo.smartexpense.extraction.ModelProfile
import com.hugo.smartexpense.extraction.ModelProfileDraft
import com.hugo.smartexpense.extraction.ModelProfileField
import com.hugo.smartexpense.extraction.ModelProfileValidator
import com.hugo.smartexpense.extraction.RemoteInputMode
import com.hugo.smartexpense.extraction.RemoteStructuredOutputFormat

@Composable
fun ModelProfilesPanel(state: ModelProfilesUiState, viewModel: ModelProfilesViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Model Selector", style = MaterialTheme.typography.headlineMedium)
        Text(state.effectiveProviderSummary)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(
                checked = state.selectorState.remoteProvidersEnabled,
                onCheckedChange = viewModel::setRemoteProvidersEnabled,
                enabled = !state.busy,
            )
            Text(
                if (state.selectorState.remoteProvidersEnabled) "Remote providers enabled"
                else "Remote providers disabled (private local fallback)",
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Text("Remote testing and extraction contact the configured endpoint and may send receipt data.")
        state.message?.let {
            Card(Modifier.fillMaxWidth()) { Text(it, Modifier.padding(12.dp)) }
        }

        val editor = state.editor
        if (editor == null) {
            ProfileList(state, viewModel)
        } else {
            ProfileEditor(editor, state.busy, viewModel)
        }
    }
}

@Composable
private fun ProfileList(state: ModelProfilesUiState, viewModel: ModelProfilesViewModel) {
    var deleteTarget by remember { mutableStateOf<ModelProfile?>(null) }
    Text("Saved profiles", style = MaterialTheme.typography.headlineSmall)
    if (state.profiles.isEmpty()) {
        Text("No remote profiles are saved. Local extraction remains selected.")
    }
    state.profiles.forEach { profile ->
        val selected = state.selectorState.selectedRemoteProfileId == profile.id &&
            state.selectorState.remoteProvidersEnabled
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(profile.displayName, style = MaterialTheme.typography.titleMedium)
                Text("${profile.modelId} • ${profile.inputMode.name.lowercase().replace('_', ' ')}")
                Text(profile.baseUrl)
                if (selected) Text("Selected for next extraction", color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.selectProfile(profile) },
                        enabled = !selected && state.selectorState.remoteProvidersEnabled && !state.busy,
                    ) { Text(if (selected) "Selected" else "Select") }
                    OutlinedButton(onClick = { viewModel.editProfile(profile) }, enabled = !state.busy) { Text("Edit") }
                    TextButton(onClick = { deleteTarget = profile }, enabled = !state.busy) { Text("Delete") }
                }
            }
        }
    }
    Button(onClick = viewModel::addProfile, enabled = !state.busy) { Text("Add profile") }

    deleteTarget?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${profile.displayName}?") },
            text = { Text("This removes the profile and its stored credential. If selected, extraction immediately falls back to the local provider.") },
            confirmButton = {
                Button(onClick = { viewModel.deleteProfile(profile); deleteTarget = null }) { Text("Delete profile") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ProfileEditor(
    editor: ModelProfileEditorState,
    busy: Boolean,
    viewModel: ModelProfilesViewModel,
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    val draft = editor.draft
    val locallyValid = remember(draft) { ModelProfileValidator().validate(draft).isValid }
    fun update(value: ModelProfileDraft) = viewModel.updateDraft(value)

    Text(if (draft.id == null) "Add remote profile" else "Edit remote profile", style = MaterialTheme.typography.headlineSmall)
    ProfileField(
        value = draft.displayName,
        onValueChange = { update(draft.copy(displayName = it)) },
        label = "Display name",
        error = editor.validationErrors[ModelProfileField.DISPLAY_NAME],
    )
    ProfileField(
        value = draft.baseUrl,
        onValueChange = { update(draft.copy(baseUrl = it)) },
        label = "Base URL",
        error = editor.validationErrors[ModelProfileField.BASE_URL],
    )
    ProfileField(
        value = draft.modelId,
        onValueChange = { update(draft.copy(modelId = it)) },
        label = "Model ID",
        error = editor.validationErrors[ModelProfileField.MODEL_ID],
    )
    OutlinedTextField(
        value = draft.apiKey,
        onValueChange = { update(draft.copy(apiKey = it)) },
        label = { Text(if (editor.hasStoredCredential) "Replacement API key (leave blank to retain)" else "API key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    if (editor.hasStoredCredential) {
        OutlinedButton(onClick = viewModel::clearCredential, enabled = !busy) { Text("Clear stored credential") }
    }
    Text("Receipt input")
    EnumChoice(
        selected = draft.inputMode == RemoteInputMode.DIRECT_IMAGE,
        label = "Direct image",
        onClick = { update(draft.copy(inputMode = RemoteInputMode.DIRECT_IMAGE)) },
    )
    EnumChoice(
        selected = draft.inputMode == RemoteInputMode.OCR_TEXT,
        label = "OCR text",
        onClick = { update(draft.copy(inputMode = RemoteInputMode.OCR_TEXT)) },
    )
    Text("Structured output")
    EnumChoice(
        selected = draft.structuredOutputFormat == RemoteStructuredOutputFormat.JSON_SCHEMA,
        label = "JSON schema",
        onClick = { update(draft.copy(structuredOutputFormat = RemoteStructuredOutputFormat.JSON_SCHEMA)) },
    )
    EnumChoice(
        selected = draft.structuredOutputFormat == RemoteStructuredOutputFormat.JSON_OBJECT,
        label = "JSON object",
        onClick = { update(draft.copy(structuredOutputFormat = RemoteStructuredOutputFormat.JSON_OBJECT)) },
    )
    Text("Testing sends a connectivity prompt to this remote endpoint. It does not save or select the profile.")
    editor.testStatus?.let { Text(it) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = viewModel::testProfile, enabled = locallyValid && !busy) { Text("Test provider") }
        Button(onClick = { viewModel.saveProfile(false) }, enabled = locallyValid && !busy) { Text("Save") }
        Button(onClick = { viewModel.saveProfile(true) }, enabled = locallyValid && !busy) { Text("Save and select") }
    }
    TextButton(
        onClick = { if (editor.hasUnsavedChanges) confirmDiscard = true else viewModel.cancelEdit() },
        enabled = !busy,
    ) { Text("Cancel") }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard unsaved changes?") },
            text = { Text("Your profile edits and unsaved API key entry will be lost.") },
            confirmButton = { Button(onClick = { confirmDiscard = false; viewModel.cancelEdit() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
}

@Composable
private fun ProfileField(value: String, onValueChange: (String) -> Unit, label: String, error: String?) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun EnumChoice(selected: Boolean, label: String, onClick: () -> Unit) {
    Row {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, Modifier.padding(top = 12.dp))
    }
}
