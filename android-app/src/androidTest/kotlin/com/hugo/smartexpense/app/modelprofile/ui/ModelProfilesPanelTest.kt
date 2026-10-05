package com.hugo.smartexpense.app.modelprofile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hugo.smartexpense.app.modelprofile.domain.AvailableProviderModel
import com.hugo.smartexpense.app.modelprofile.domain.ProviderModelCatalogResult
import com.hugo.smartexpense.app.modelprofile.domain.ProviderModelCatalogService
import com.hugo.smartexpense.app.modelprofile.domain.SelectedReceiptModelProviderResolver
import com.hugo.smartexpense.extraction.DurableCredentialWriteResult
import com.hugo.smartexpense.extraction.RemoteInputMode
import com.hugo.smartexpense.extraction.RemoteStructuredOutputFormat
import com.hugo.smartexpense.extraction.ModelProfile
import com.hugo.smartexpense.extraction.ModelProfileRepository
import com.hugo.smartexpense.extraction.ModelProfileSelectorState
import com.hugo.smartexpense.extraction.ProviderTestResult
import com.hugo.smartexpense.extraction.WritableApiKeyStore
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelProfilesPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun createsAndSelectsAProfileWithoutPersistingTheSecretInUiState() {
        val repository = UiRepository()
        val credentials = UiCredentials()
        var exportRequests = 0
        var importRequests = 0
        val viewModel = ModelProfilesViewModel(
            repository, credentials, SelectedReceiptModelProviderResolver(repository),
            ModelProfileTestService { _, _ -> ProviderTestResult.Success },
            idFactory = { "ui-profile" }, clock = { 10 },
        )
        compose.setContent {
            val state by viewModel.uiState.collectAsState()
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ModelProfilesPanel(
                        state,
                        viewModel,
                        onExportProfiles = { exportRequests++ },
                        onImportProfiles = { importRequests++ },
                    )
                }
            }
        }

        compose.onNodeWithText("No remote profiles are saved. Local extraction remains selected.").assertIsDisplayed()
        compose.onNodeWithText("Import profiles").performClick()
        check(importRequests == 1)
        compose.onNodeWithText("Add profile").performClick()
        compose.onNodeWithText("Display name").performTextInput("Home server")
        compose.onNodeWithText("Base URL").performTextInput("https://example.test/v1")
        compose.onNodeWithText("Model ID").performTextInput("model-a")
        compose.onNodeWithText("API key").performTextInput("secret-value")
        compose.onNodeWithContentDescription("Show Tailscale control on Main").performScrollTo().performClick()
        compose.onNodeWithText("Save and select").performScrollTo().performClick()

        compose.onNodeWithText("Home server").assertIsDisplayed()
        compose.onNodeWithText("Selected for next extraction").assertIsDisplayed()
        compose.onNodeWithText("Home server (model-a) will be used for the next extraction.").assertIsDisplayed()
        compose.onNodeWithText("Export all profiles").performClick()
        check(exportRequests == 1)
        check(credentials.values["remote-provider:ui-profile"] == "secret-value")
        check(repository.profiles.value.single().showTailscaleToggle)
    }
    @Test fun duplicateActionIdentifiesSourceAndKeepsSelectionAndCredentialsIndependent() {
        val repository = UiRepository()
        val source = ModelProfile("source", "Office", "https://example.test/v1", "org/source", RemoteInputMode.OCR_TEXT,
            RemoteStructuredOutputFormat.JSON_OBJECT, "actual-source-alias", 1, 2, true)
        repository.profiles.value = listOf(source)
        val credentials = UiCredentials().apply { values[source.credentialAlias] = "source-secret" }
        val vm = ModelProfilesViewModel(repository, credentials, SelectedReceiptModelProviderResolver(repository),
            ModelProfileTestService { _, _ -> ProviderTestResult.Success }, idFactory = { "copy" }, clock = { 10 })
        panel(vm)
        compose.onNodeWithContentDescription("Duplicate profile Office").performScrollTo().performClick()
        compose.waitUntil(5_000) { repository.profiles.value.size == 2 }
        compose.onNodeWithText("Duplicated Office as Office (copy).").performScrollTo().assertIsDisplayed()
        check(repository.profiles.value.single { it.id == "source" } == source)
        val copy = repository.profiles.value.single { it.id == "copy" }
        check(copy.showTailscaleToggle && copy.modelId == source.modelId)
        check(credentials.values[copy.credentialAlias] == "source-secret")
        credentials.remove(copy.credentialAlias)
        check(credentials.values[source.credentialAlias] == "source-secret")
        check(repository.selector.value == ModelProfileSelectorState())
    }

    @Test fun searchablePickerKeepsExactIdAndDoesNotSaveOrSelect() {
        val repository = UiRepository()
        val vm = ModelProfilesViewModel(repository, UiCredentials(), SelectedReceiptModelProviderResolver(repository),
            ModelProfileTestService { _, _ -> ProviderTestResult.Success },
            catalogService = ProviderModelCatalogService { _, _ ->
                ProviderModelCatalogResult.Loaded(listOf(AvailableProviderModel("org/Model:V2"), AvailableProviderModel("other")))
            })
        panel(vm)
        compose.onNodeWithText("Add profile").performScrollTo().performClick()
        compose.onNodeWithText("Base URL").performTextInput("https://example.test/v1")
        compose.onNodeWithText("Model ID").performTextInput("manual")
        compose.onNodeWithText("Load models").performScrollTo().performClick()
        compose.waitUntil(5_000) { vm.uiState.value.editor?.modelPickerOpen == true }
        compose.onNodeWithText("Search models").performTextInput("Model:V2")
        compose.onNodeWithContentDescription("Choose model other").assertDoesNotExist()
        compose.onNodeWithContentDescription("Choose model org/Model:V2").performClick()
        compose.onNodeWithText("Model ID").assertTextContains("org/Model:V2")
        check(repository.profiles.value.isEmpty())
        check(repository.selector.value == ModelProfileSelectorState())
    }

    @Test fun emptyAndFailedCatalogRetainManualEntry() {
        val repository = UiRepository()
        var response: ProviderModelCatalogResult = ProviderModelCatalogResult.Loaded(emptyList())
        val vm = ModelProfilesViewModel(repository, UiCredentials(), SelectedReceiptModelProviderResolver(repository),
            ModelProfileTestService { _, _ -> ProviderTestResult.Success },
            catalogService = ProviderModelCatalogService { _, _ -> response })
        panel(vm)
        compose.onNodeWithText("Add profile").performScrollTo().performClick()
        compose.onNodeWithText("Base URL").performTextInput("https://example.test/v1")
        compose.onNodeWithText("Model ID").performTextInput("manual")
        compose.onNodeWithText("Load models").performScrollTo().performClick()
        compose.waitUntil(5_000) { vm.uiState.value.editor?.catalogState is ModelCatalogState.Empty }
        compose.onNodeWithText("The endpoint is reachable but returned no models. Enter a model ID manually.").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { response = ProviderModelCatalogResult.Failed("Access denied") }
        compose.onNodeWithText("Load models").performScrollTo().performClick()
        compose.waitUntil(5_000) { vm.uiState.value.editor?.catalogState is ModelCatalogState.Failed }
        compose.onNodeWithText("Models could not be loaded: Access denied Manual model entry is available.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Model ID").assertTextContains("manual")
    }

    private fun panel(vm: ModelProfilesViewModel) {
        compose.setContent {
            val state by vm.uiState.collectAsState()
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ModelProfilesPanel(state, vm, onExportProfiles = {}, onImportProfiles = {})
                }
            }
        }
    }

}

internal class UiRepository : ModelProfileRepository {
    val profiles = MutableStateFlow<List<ModelProfile>>(emptyList())
    val selector = MutableStateFlow(ModelProfileSelectorState())
    override fun observeProfiles() = profiles
    override fun observeSelectorState() = selector
    override suspend fun getProfile(id: String) = profiles.value.firstOrNull { it.id == id }
    override suspend fun saveProfile(profile: ModelProfile) { profiles.value = profiles.value.filterNot { it.id == profile.id } + profile }
    override suspend fun credentialAliasInUse(alias: String) = profiles.value.any { it.credentialAlias == alias }
    override suspend fun insertDuplicate(sourceSnapshot: ModelProfile, copy: ModelProfile) {
        check(getProfile(sourceSnapshot.id) == sourceSnapshot)
        check(getProfile(copy.id) == null)
        profiles.value = profiles.value + copy
    }
    override suspend fun saveProfiles(profiles: List<ModelProfile>) {
        val ids = profiles.mapTo(mutableSetOf()) { it.id }
        this.profiles.value = this.profiles.value.filterNot { it.id in ids } + profiles
    }
    override suspend fun selectProfile(id: String?) { selector.value = selector.value.copy(selectedRemoteProfileId = id) }
    override suspend fun setRemoteProvidersEnabled(enabled: Boolean) { selector.value = selector.value.copy(remoteProvidersEnabled = enabled) }
    override suspend fun deleteProfile(id: String) {
        profiles.value = profiles.value.filterNot { it.id == id }
        if (selector.value.selectedRemoteProfileId == id) selectProfile(null)
    }
}

internal class UiCredentials : WritableApiKeyStore {
    val values = mutableMapOf<String, String>()
    override fun get(alias: String) = values[alias]
    override fun put(alias: String, value: String) { values[alias] = value }
    override fun remove(alias: String) { values.remove(alias) }
    override fun putDurablyIfAbsent(alias: String, value: String): DurableCredentialWriteResult {
        if (values.containsKey(alias)) return DurableCredentialWriteResult.ALREADY_EXISTS
        values[alias] = value
        return DurableCredentialWriteResult.STORED
    }
    override fun removeDurably(alias: String): Boolean { values.remove(alias); return true }
}
