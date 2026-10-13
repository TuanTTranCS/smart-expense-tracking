package com.hugo.smartexpense.app.modelprofile.ui

import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import com.hugo.smartexpense.app.settings.ui.SettingsToggleRow
import com.hugo.smartexpense.app.settings.ui.settingsGroupIcon
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Built-in on-device | Same-phone server | Remote API", style = MaterialTheme.typography.labelLarge)
        Text("Model Selector", style = MaterialTheme.typography.headlineMedium)
        Text(state.effectiveProviderSummary)
        SettingsToggleRow(
            checked = state.selectorState.remoteProvidersEnabled,
            onCheckedChange = viewModel::setRemoteProvidersEnabled,
            title = if (state.selectorState.remoteProvidersEnabled) "Remote providers enabled"
                else "Remote providers disabled (private local fallback)",
            description = "Remote testing and extraction contact the configured endpoint and may send receipt data.",
            accessibilityLabel = "Remote providers", enabled = !state.busy, checkbox = false,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TextButton(onClick = viewModel::recoverCredentials, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Recover credentials") }
        state.message?.let {
            Card(Modifier.fillMaxWidth()) { CopyableMessage(it, Modifier.padding(12.dp)) }
            if (it.contains("cleanup", ignoreCase = true)) TextButton(onClick = viewModel::recoverCredentials, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry credential recovery") }
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
                    Button(onClick = viewModel::confirmProviderConfigImport, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Import profiles")
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::cancelProviderConfigImport, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
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
    Text("Saved profiles", style = MaterialTheme.typography.titleLarge)
    if (state.profiles.isEmpty()) {
        Text("No remote profiles are saved. Local extraction remains selected.")
    }
    state.profiles.forEach { profile ->
        val selected = state.selectorState.selectedRemoteProfileId == profile.id &&
            (profile.category == ApiProfileCategory.SAME_PHONE_SERVER || state.selectorState.remoteProvidersEnabled)
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (profile.category == ApiProfileCategory.SAME_PHONE_SERVER) "Same-phone server" else "Remote API", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(settingsGroupIcon(), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(profile.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                }
                Text("${profile.modelId} • ${profile.inputMode.name.lowercase().replace('_', ' ')}")
                Text(profile.baseUrl)
                profile.configurationError?.let { CopyableMessage(it, color = MaterialTheme.colorScheme.error) }
                if (selected) Text("Selected for next extraction", color = MaterialTheme.colorScheme.primary)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.selectProfile(profile) },
                        modifier = Modifier.heightIn(min = 48.dp),
                        enabled = !selected && (profile.category == ApiProfileCategory.SAME_PHONE_SERVER || state.selectorState.remoteProvidersEnabled) && !state.busy,
                    ) { Text(if (selected) "Selected" else "Select") }
                    OutlinedButton(onClick = { viewModel.editProfile(profile) }, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Edit") }
                    TextButton(onClick = { deleteTarget = profile }, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete") }
                }
                OutlinedButton(
                    onClick = { viewModel.duplicateProfile(profile.id) },
                    enabled = !state.busy,
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Duplicate profile ${profile.displayName}" },
                ) { Text("Duplicate profile") }
            }
        }
    }
    Button(onClick = viewModel::addProfile, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add profile") }
    OutlinedButton(
        onClick = onExportProfiles,
        enabled = state.profiles.isNotEmpty() && !state.busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { Text("Export all profiles") }
    OutlinedButton(
        onClick = onImportProfiles,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { Text("Import profiles") }

    deleteTarget?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${profile.displayName}?") },
            text = { Text("This removes the profile and its stored credential. If selected, extraction immediately falls back to the local provider.") },
            confirmButton = {
                Button(onClick = { viewModel.deleteProfile(profile); deleteTarget = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete profile") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
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
    OutlinedButton(onClick = viewModel::loadModels, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Load models") }
    Text("Loading models sends only a model-list request. You can always enter a model ID manually.")
    editor.catalogEndpoint?.let { Text("Models endpoint: $it", style = MaterialTheme.typography.bodySmall) }
    when (val catalog = editor.catalogState) {
        ModelCatalogState.Idle -> Unit
        is ModelCatalogState.Loading -> {
            Text("Loading models...")
            TextButton(onClick = viewModel::cancelModelDiscovery, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel model loading") }
        }
        is ModelCatalogState.Loaded -> {
            Text("Loaded ${catalog.models.size} models. Listing does not verify receipt support or inference access.")
            TextButton(onClick = viewModel::openModelPicker, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Choose model") }
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
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = viewModel::keepCredential, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep") }
        TextButton(onClick = viewModel::clearCredential, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear") }
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
        SettingsToggleRow(draft.showTailscaleToggle, { update(draft.copy(showTailscaleToggle = it)) },
            "Show Tailscale control on Main",
            "When this profile is selected, show a control for connecting to the Tailscale network used by its model endpoint.",
            "Show Tailscale control on Main", enabled = !busy)
    } else {
        Text("Receipt input: Direct image")
        Text("Structured output: Prompt-only JSON")
    }
    Text("Testing sends a connectivity prompt to this endpoint. It does not save or select the profile.")
    editor.testStatus?.let { CopyableMessage(it) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = viewModel::testProfile, enabled = locallyValid && !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Test provider") }
        Button(onClick = { viewModel.saveProfile(false) }, enabled = locallyValid && !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save") }
    }
    TextButton(
        onClick = { if (editor.hasUnsavedChanges) confirmDiscard = true else viewModel.cancelEdit() },
        enabled = !busy,
        modifier = Modifier.heightIn(min = 48.dp),
    ) { Text("Cancel") }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard unsaved changes?") },
            text = { Text("Your profile edits and unsaved API key entry will be lost.") },
            confirmButton = { Button(onClick = { confirmDiscard = false; viewModel.cancelEdit() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep editing") } },
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
internal fun EnumChoice(selected: Boolean, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, Modifier.weight(1f).padding(start = 8.dp, end = 8.dp))
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
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "Choose model ${model.id}" },
                        ) { Text(model.id) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::dismissModelPicker, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close picker") } },
    )
}
