package com.hugo.smartexpense.app.settings.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hugo.smartexpense.app.modelprofile.domain.SelectedReceiptModelProviderResolver
import com.hugo.smartexpense.app.modelprofile.ui.ModelProfileTestService
import com.hugo.smartexpense.app.modelprofile.ui.ModelProfilesViewModel
import com.hugo.smartexpense.app.modelprofile.ui.UiCredentials
import com.hugo.smartexpense.app.modelprofile.ui.UiRepository
import com.hugo.smartexpense.app.settings.data.AppSettings
import com.hugo.smartexpense.extraction.ProviderTestResult
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun settingsContainsConfigurationButNotReceiptWorkflow() {
        val repository = UiRepository()
        val viewModel = ModelProfilesViewModel(
            repository, UiCredentials(), SelectedReceiptModelProviderResolver(repository),
            ModelProfileTestService { _, _ -> ProviderTestResult.Success },
        )
        compose.setContent {
            val state by viewModel.uiState.collectAsState()
            MaterialTheme {
                SettingsScreen(
                    state, viewModel, AppSettings(), {}, {}, {}, {},
                )
            }
        }

        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("Model Selector").assertIsDisplayed()
        compose.onNodeWithText("Receipt image preprocessing").assertIsDisplayed()
        compose.onNodeWithText("Choose receipt").assertDoesNotExist()
        compose.onNodeWithText("Review extracted receipt").assertDoesNotExist()
        compose.onNodeWithText("Confirm and export").assertDoesNotExist()
    }
}
