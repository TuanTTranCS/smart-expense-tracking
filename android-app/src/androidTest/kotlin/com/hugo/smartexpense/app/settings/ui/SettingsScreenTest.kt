package com.hugo.smartexpense.app.settings.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import com.hugo.smartexpense.app.modelprofile.domain.AvailableProviderModel
import com.hugo.smartexpense.app.modelprofile.domain.ProviderModelCatalogResult
import com.hugo.smartexpense.app.modelprofile.domain.ProviderModelCatalogService
import com.hugo.smartexpense.app.modelprofile.ui.ModelCatalogState
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import org.junit.Assert.assertEquals

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun settingsContainsConfigurationButNotReceiptWorkflow() {
        val repository = UiRepository()
        val viewModel = ModelProfilesViewModel(
            repository, UiCredentials(), SelectedReceiptModelProviderResolver(repository),
            ModelProfileTestService { _, _ -> ProviderTestResult.Success },
        )
        var debugEnabled: Boolean? = null
        compose.setContent {
            val state by viewModel.uiState.collectAsState()
            MaterialTheme {
                SettingsScreen(
                    profileState = state,
                    profilesViewModel = viewModel,
                    settings = AppSettings(),
                    onReduceOversizedImagesChange = {},
                    onDebugOutputEnabledChange = { debugEnabled = it },
                    onDeviceNameChange = {},
                    onExportProfiles = {},
                    onImportProfiles = {},
                    onNavigateBack = {},
                )
            }
        }

        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("Model Selector").assertIsDisplayed()
        compose.onNodeWithText("Receipt image preprocessing").assertIsDisplayed()
        compose.onNodeWithContentDescription("Debug output").performScrollTo().performClick()
        assertEquals(true, debugEnabled)
        compose.onNodeWithText("Choose receipt").assertDoesNotExist()
        compose.onNodeWithText("Review extracted receipt").assertDoesNotExist()
        compose.onNodeWithText("Confirm and export").assertDoesNotExist()
    }
    @Test fun leavingSettingsCancelsDiscoveryAndRetainsUnsavedDraftDespiteLateResult() {
        val repository = UiRepository()
        var pending: Continuation<ProviderModelCatalogResult>? = null
        val vm = ModelProfilesViewModel(repository, UiCredentials(), SelectedReceiptModelProviderResolver(repository),
            ModelProfileTestService { _, _ -> ProviderTestResult.Success },
            catalogService = ProviderModelCatalogService { _, _ -> suspendCoroutine { pending = it } })
        var settingsVisible by mutableStateOf(true)
        compose.setContent {
            val state by vm.uiState.collectAsState()
            MaterialTheme {
                if (settingsVisible) SettingsScreen(state, vm, AppSettings(), {}, {}, {}, {}, {}, {})
                else Text("Receipt screen")
            }
        }
        compose.onNodeWithText("Add profile").performScrollTo().performClick()
        compose.onNodeWithText("Base URL").performTextInput("https://example.test/v1")
        compose.onNodeWithText("Model ID").performTextInput("manual-id")
        compose.onNodeWithText("Load models").performScrollTo().performClick()
        compose.waitUntil(5_000) { pending != null }
        compose.onNodeWithText("Loading models...").assertIsDisplayed()
        compose.runOnIdle { settingsVisible = false }
        compose.onNodeWithText("Receipt screen").assertIsDisplayed()
        compose.runOnIdle {
            pending!!.resume(ProviderModelCatalogResult.Loaded(listOf(AvailableProviderModel("stale"))))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("manual-id", vm.uiState.value.editor!!.draft.modelId)
            check(vm.uiState.value.editor!!.catalogState is ModelCatalogState.Cancelled)
            check(repository.profiles.value.isEmpty())
            settingsVisible = true
        }
        compose.onNodeWithText("Model loading cancelled. Your draft was kept.").performScrollTo().assertIsDisplayed()
    }

    @Test fun pauseCancelsDiscoveryAndBackProtectsDraftUntilDiscard() {
        val owner = TestLifecycleOwner()
        compose.runOnIdle {
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        val repository = UiRepository()
        val requests = mutableListOf<Continuation<ProviderModelCatalogResult>>()
        val vm = ModelProfilesViewModel(repository, UiCredentials(), SelectedReceiptModelProviderResolver(repository),
            ModelProfileTestService { _, _ -> ProviderTestResult.Success },
            catalogService = ProviderModelCatalogService { _, _ -> suspendCoroutine { requests += it } })
        compose.setContent {
            val state by vm.uiState.collectAsState()
            MaterialTheme {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    SettingsScreen(state, vm, AppSettings(), {}, {}, {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithText("Add profile").performScrollTo().performClick()
        compose.onNodeWithText("Base URL").performTextInput("https://example.test/v1")
        compose.onNodeWithText("Load models").performScrollTo().performClick()
        compose.waitUntil(5_000) { requests.size == 1 }
        compose.runOnIdle {
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            check(vm.uiState.value.editor!!.catalogState is ModelCatalogState.Cancelled)
            assertEquals("https://example.test/v1", vm.uiState.value.editor!!.draft.baseUrl)
            requests[0].resume(ProviderModelCatalogResult.Loaded(listOf(AvailableProviderModel("stale-pause"))))
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        compose.onNodeWithText("Load models").performScrollTo().performClick()
        compose.waitUntil(5_000) { requests.size == 2 }
        compose.onNodeWithContentDescription("Back to receipt workflow").performScrollTo().performClick()
        compose.onNodeWithText("Discard unsaved changes?").assertIsDisplayed()
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithContentDescription("Back to receipt workflow").performScrollTo().performClick()
        compose.onNodeWithText("Discard").performClick()
        compose.runOnIdle { requests[1].resume(ProviderModelCatalogResult.Loaded(listOf(AvailableProviderModel("stale-close")))) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(null, vm.uiState.value.editor)
            check(repository.profiles.value.isEmpty())
        }
    }

}

private class TestLifecycleOwner : LifecycleOwner {
    val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
}
