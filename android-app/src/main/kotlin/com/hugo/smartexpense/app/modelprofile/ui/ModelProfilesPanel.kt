package com.hugo.smartexpense.app.modelprofile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hugo.smartexpense.app.ui.CopyableMessage
import com.hugo.smartexpense.extraction.ApiProfileCategory
import com.hugo.smartexpense.extraction.CredentialEdit
import com.hugo.smartexpense.extraction.ModelProfile
import com.hugo.smartexpense.extraction.ModelProfileDraft
import com.hugo.smartexpense.extraction.ModelProfileField
import com.hugo.smartexpense.extraction.ModelProfileValidator
import com.hugo.smartexpense.extraction.RemoteInputMode
import com.hugo.smartexpense.extraction.RemoteStructuredOutputFormat

@Composable
fun ModelProfilesPanel(
    state: ModelProfilesUiState,
    viewModel: ModelProfilesViewModel,
    onExportProfiles: () -> Unit,
    onImportProfiles: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Built-in on-device | Same-phone server | Remote API", style = MaterialTheme.typography.labelLarge)
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
        TextButton(onClick = viewModel::recoverCredentials, enabled = !state.busy) { Text("Recover credentials") }
        state.message?.let {
            Card(Modifier.fillMaxWidth()) { CopyableMessage(it, Modifier.padding(12.dp)) }
            if (it.contains("cleanup", ignoreCase = true)) TextButton(onClick = viewModel::recoverCredentials, enabled = !state.busy) { Text("Retry credential recovery") }
        }

        val editor = state.editor
        if (editor == null) {
            ProfileList(state, viewModel, onExportProfiles, onImportProfiles)
        } else {
            ProfileEditor(editor, state.busy, viewModel)
        }

        state.importPreview?.let { preview ->
            AlertDialog(
                onDismissRequest = viewModel::cancelProviderConfigImport,
                title = { Text("Import ${preview.profileCount} provider profile${if (preview.profileCount == 1) "" else "s"}?") },
                text = {
                    Column(
                        modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        preview.config.profiles.forEach { profile ->
                            Text("${profile.displayName} — ${profile.modelId}")
                            Text(profile.baseUrl, style = MaterialTheme.typography.bodySmall)
                        }
                        if (preview.conflictCount > 0) {
                            Text(
                                if (preview.conflictCount == 1) {
                                    "1 existing profile ID conflict will be saved as a new copy."
                                } else {
                                    "${preview.conflictCount} existing profile ID conflicts will be saved as new copies."
                                },
                            )
                        }
                        Text("API keys are not included. Add credentials separately after import.")
                        Text("Your current provider selection and remote-provider setting will not change.")
                    }
                },
                confirmButton = {
                    Button(onClick = viewModel::confirmProviderConfigImport, enabled = !state.busy) {
                        Text("Import profiles")
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::cancelProviderConfigImport, enabled = !state.busy) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileList(
    state: ModelProfilesUiState,
    viewModel: ModelProfilesViewModel,
    onExportProfiles: () -> Unit,
    onImportProfiles: () -> Unit,
) {
    var deleteTarget by remember { mutableStateOf<ModelProfile?>(null) }
    Text("Saved profiles", style = MaterialTheme.typography.headlineSmall)
    if (state.profiles.isEmpty()) {
        Text("No remote profiles are saved. Local extraction remains selected.")
    }
    state.profiles.forEach { profile ->
        val selected = state.selectorState.selectedRemoteProfileId == profile.id &&
            (profile.category == ApiProfileCategory.SAME_PHONE_SERVER || state.selectorState.remoteProvidersEnabled)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (profile.category == ApiProfileCategory.SAME_PHONE_SERVER) "Same-phone server" else "Remote API", style = MaterialTheme.typography.labelLarge)
                Text(profile.displayName, style = MaterialTheme.typography.titleMedium)
                Text("${profile.modelId} • ${profile.inputMode.name.lowercase().replace('_', ' ')}")
                Text(profile.baseUrl)
                profile.configurationError?.let { CopyableMessage(it, color = MaterialTheme.colorScheme.error) }
                if (selected) Text("Selected for next extraction", color = MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.selectProfile(profile) },
                        enabled = !selected && (profile.category == ApiProfileCategory.SAME_PHONE_SERVER || state.selectorState.remoteProvidersEnabled) && !state.busy,
                    ) { Text(if (selected) "Selected" else "Select") }
                    OutlinedButton(onClick = { viewModel.editProfile(profile) }, enabled = !state.busy) { Text("Edit") }
                    TextButton(onClick = { deleteTarget = profile }, enabled = !state.busy) { Text("Delete") }
                }
                OutlinedButton(
                    onClick = { viewModel.duplicateProfile(profile.id) },
                    enabled = !state.busy,
                    modifier = Modifier.semantics { contentDescription = "Duplicate profile ${profile.displayName}" },
                ) { Text("Duplicate profile") }
            }
        }
    }
    Button(onClick = viewModel::addProfile, enabled = !state.busy) { Text("Add profile") }
    OutlinedButton(
        onClick = onExportProfiles,
        enabled = state.profiles.isNotEmpty() && !state.busy,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Export all profiles") }
    OutlinedButton(
        onClick = onImportProfiles,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Import profiles") }

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

@OptIn(ExperimentalLayoutApi::class)
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

    Text(if (draft.id == null) "Add API profile" else "Edit API profile", style = MaterialTheme.typography.headlineSmall)
    Text("Provider category")
    EnumChoice(draft.category == ApiProfileCategory.REMOTE_API, "Remote API", { viewModel.changeCategory(ApiProfileCategory.REMOTE_API) }, !busy)
    EnumChoice(draft.category == ApiProfileCategory.SAME_PHONE_SERVER, "Same-phone server", { viewModel.changeCategory(ApiProfileCategory.SAME_PHONE_SERVER) }, !busy)
    if (draft.category == ApiProfileCategory.SAME_PHONE_SERVER) Text("Install the supported companion, load a vision model, and start it manually. Match its listening port, API path and optional key. Remote opt-in is independent.")
    ProfileField(
        value = draft.displayName,
        onValueChange = { update(draft.copy(displayName = it)) },
        label = "Display name",
        error = editor.validationErrors[ModelProfileField.DISPLAY_NAME],
        enabled = !busy,
    )
    ProfileField(
        value = draft.baseUrl,
        onValueChange = { update(draft.copy(baseUrl = it)) },
        label = "Base URL",
        error = editor.validationErrors[ModelProfileField.BASE_URL],
        enabled = !busy,
    )
    ProfileField(
        value = draft.modelId,
        onValueChange = { update(draft.copy(modelId = it)) },
        label = "Model ID",
        error = editor.validationErrors[ModelProfileField.MODEL_ID],
        enabled = !busy,
    )
    OutlinedButton(onClick = viewModel::loadModels, enabled = !busy) { Text("Load models") }
    Text("Loading models sends only a model-list request. You can always enter a model ID manually.")
    editor.catalogEndpoint?.let { Text("Models endpoint: $it", style = MaterialTheme.typography.bodySmall) }
    when (val catalog = editor.catalogState) {
        ModelCatalogState.Idle -> Unit
        is ModelCatalogState.Loading -> {
            Text("Loading models...")
            TextButton(onClick = viewModel::cancelModelDiscovery) { Text("Cancel model loading") }
        }
        is ModelCatalogState.Loaded -> {
            Text("Loaded ${catalog.models.size} models. Listing does not verify receipt support or inference access.")
            TextButton(onClick = viewModel::openModelPicker, enabled = !busy) { Text("Choose model") }
            if (editor.modelPickerOpen) ModelPicker(catalog, viewModel)
        }
        ModelCatalogState.Empty -> Text("The endpoint is reachable but returned no models. Enter a model ID manually.")
        is ModelCatalogState.Failed -> CopyableMessage("Models could not be loaded: ${catalog.reason} Manual model entry is available.")
        ModelCatalogState.Cancelled -> Text("Model loading cancelled. Your draft was kept.")
    }
    OutlinedTextField(
        value = draft.apiKey,
        onValueChange = { viewModel.updateApiKey(it) },
        label = { Text(if (draft.category == ApiProfileCategory.SAME_PHONE_SERVER) "Optional API key" else "API key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(when (draft.credentialEdit) {
        CredentialEdit.Keep -> "Keep saved credential"
        CredentialEdit.Clear -> "Clear credential when saved"
        is CredentialEdit.Replace -> "Replace credential when saved"
    })
    Row {
        TextButton(onClick = viewModel::keepCredential, enabled = !busy) { Text("Keep") }
        TextButton(onClick = viewModel::clearCredential, enabled = !busy) { Text("Clear") }
    }
    if (draft.category == ApiProfileCategory.REMOTE_API) {
        Text("Receipt input")
        EnumChoice(
            selected = draft.inputMode == RemoteInputMode.DIRECT_IMAGE,
            label = "Direct image",
            onClick = { update(draft.copy(inputMode = RemoteInputMode.DIRECT_IMAGE)) },
            enabled = !busy,
        )
        EnumChoice(
            selected = draft.inputMode == RemoteInputMode.OCR_TEXT,
            label = "OCR text",
            onClick = { update(draft.copy(inputMode = RemoteInputMode.OCR_TEXT)) },
            enabled = !busy,
        )
        Text("Structured output")
        EnumChoice(
            selected = draft.structuredOutputFormat == RemoteStructuredOutputFormat.JSON_SCHEMA,
            label = "JSON schema",
            onClick = { update(draft.copy(structuredOutputFormat = RemoteStructuredOutputFormat.JSON_SCHEMA)) },
            enabled = !busy,
        )
        EnumChoice(
            selected = draft.structuredOutputFormat == RemoteStructuredOutputFormat.JSON_OBJECT,
            label = "JSON object",
            onClick = { update(draft.copy(structuredOutputFormat = RemoteStructuredOutputFormat.JSON_OBJECT)) },
            enabled = !busy,
        )
        Row {
            Checkbox(
                checked = draft.showTailscaleToggle,
                onCheckedChange = { update(draft.copy(showTailscaleToggle = it)) },
                enabled = !busy,
                modifier = Modifier.semantics { contentDescription = "Show Tailscale control on Main" },
            )
            Text("Show Tailscale control on Main", Modifier.padding(top = 12.dp))
        }
        Text("When this profile is selected, show a control for connecting to the Tailscale network used by its model endpoint.")
    } else {
        Text("Receipt input: Direct image")
        Text("Structured output: Prompt-only JSON")
    }
    Text("Testing sends a connectivity prompt to this endpoint. It does not save or select the profile.")
    editor.testStatus?.let { CopyableMessage(it) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = viewModel::testProfile, enabled = locallyValid && !busy) { Text("Test provider") }
        Button(onClick = { viewModel.saveProfile(false) }, enabled = locallyValid && !busy) { Text("Save") }
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
private fun ProfileField(value: String, onValueChange: (String) -> Unit, label: String, error: String?, enabled: Boolean = true) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = error != null,
        enabled = enabled,
        supportingText = error?.let { { CopyableMessage(it) } },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun EnumChoice(selected: Boolean, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun ModelPicker(catalog: ModelCatalogState.Loaded, viewModel: ModelProfilesViewModel) {
    var search by remember(catalog) { mutableStateOf("") }
    val matching = catalog.models.filter { it.id.contains(search, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = viewModel::dismissModelPicker,
        title = { Text("Choose model") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text("Search models") }, singleLine = true)
                if (matching.isEmpty()) Text("No matching models. Try another search or enter a model ID manually.")
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(matching, key = { it.id }) { model ->
                        TextButton(
                            onClick = { viewModel.selectCatalogModel(model.id) },
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Choose model ${model.id}" },
                        ) { Text(model.id) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::dismissModelPicker) { Text("Close picker") } },
    )
}
