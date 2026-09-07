package com.hugo.smartexpense.app.modelprofile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hugo.smartexpense.app.modelprofile.domain.SelectedReceiptModelProviderResolver
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
        val viewModel = ModelProfilesViewModel(
            repository, credentials, SelectedReceiptModelProviderResolver(repository),
            ModelProfileTestService { _, _ -> ProviderTestResult.Success },
            idFactory = { "ui-profile" }, clock = { 10 },
        )
        compose.setContent {
            val state by viewModel.uiState.collectAsState()
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) { ModelProfilesPanel(state, viewModel) }
            }
        }

        compose.onNodeWithText("No remote profiles are saved. Local extraction remains selected.").assertIsDisplayed()
        compose.onNodeWithText("Add profile").performClick()
        compose.onNodeWithText("Display name").performTextInput("Home server")
        compose.onNodeWithText("Base URL").performTextInput("https://example.test/v1")
        compose.onNodeWithText("Model ID").performTextInput("model-a")
        compose.onNodeWithText("API key").performTextInput("secret-value")
        compose.onNodeWithText("Save and select").performClick()

        compose.onNodeWithText("Home server").assertIsDisplayed()
        compose.onNodeWithText("Selected for next extraction").assertIsDisplayed()
        compose.onNodeWithText("Home server (model-a) will be used for the next extraction.").assertTextContains("Home server")
        check(credentials.values["remote-provider:ui-profile"] == "secret-value")
    }
}

private class UiRepository : ModelProfileRepository {
    val profiles = MutableStateFlow<List<ModelProfile>>(emptyList())
    val selector = MutableStateFlow(ModelProfileSelectorState())
    override fun observeProfiles() = profiles
    override fun observeSelectorState() = selector
    override suspend fun getProfile(id: String) = profiles.value.firstOrNull { it.id == id }
    override suspend fun saveProfile(profile: ModelProfile) { profiles.value = profiles.value.filterNot { it.id == profile.id } + profile }
    override suspend fun selectProfile(id: String?) { selector.value = selector.value.copy(selectedRemoteProfileId = id) }
    override suspend fun setRemoteProvidersEnabled(enabled: Boolean) { selector.value = selector.value.copy(remoteProvidersEnabled = enabled) }
    override suspend fun deleteProfile(id: String) {
        profiles.value = profiles.value.filterNot { it.id == id }
        if (selector.value.selectedRemoteProfileId == id) selectProfile(null)
    }
}

private class UiCredentials : WritableApiKeyStore {
    val values = mutableMapOf<String, String>()
    override fun get(alias: String) = values[alias]
    override fun put(alias: String, value: String) { values[alias] = value }
    override fun remove(alias: String) { values.remove(alias) }
}
