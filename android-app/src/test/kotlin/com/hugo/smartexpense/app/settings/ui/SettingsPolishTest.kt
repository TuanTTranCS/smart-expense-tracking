package com.hugo.smartexpense.app.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import com.hugo.smartexpense.app.ui.SmartExpenseTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.hugo.smartexpense.app.modelprofile.domain.SelectedReceiptModelProviderResolver
import com.hugo.smartexpense.app.modelprofile.migration.FakeRepository
import com.hugo.smartexpense.app.modelprofile.ui.EnumChoice
import com.hugo.smartexpense.app.modelprofile.ui.ModelProfileTestService
import com.hugo.smartexpense.app.modelprofile.ui.ModelProfilesViewModel
import com.hugo.smartexpense.app.settings.data.AppSettings
import com.hugo.smartexpense.extraction.ProviderTestResult
import com.hugo.smartexpense.extraction.WritableApiKeyStore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsPolishTest {
    @get:Rule val compose = createComposeRule()

    @Test fun toggleRowsDispatchOnceAndReflectUpdatedSettings() {
        val settings = mutableStateOf(AppSettings())
        val changes = mutableListOf<Boolean>()
        val repository = FakeRepository()
        val vm = viewModel(repository)
        compose.setContent {
            val state by vm.uiState.collectAsState()
            SmartExpenseTheme {
                SettingsScreen(state, vm, settings.value,
                    { changes += it; settings.value = settings.value.copy(reduceOversizedImages = it) },
                    { changes += it; settings.value = settings.value.copy(debugOutputEnabled = it) },
                    {}, {}, {}, {})
            }
        }
        compose.onNodeWithContentDescription("Reduce oversized receipt images")
            .performScrollTo().assertIsOn().assertHeightIsAtLeast(48.dp).performClick().assertIsOff()
        compose.onNodeWithContentDescription("Debug output")
            .performScrollTo().assertIsOff().assertHeightIsAtLeast(48.dp).performClick().assertIsOn()
        compose.runOnIdle { assertEquals(listOf(false, true), changes) }
    }

    @Test fun narrowLargeTextRowsWrapAndRemainAccessible() {
        val enabled = mutableStateOf(false)
        var calls = 0
        compose.setContent {
            SmartExpenseTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                    Column(Modifier.width(240.dp)) {
                        SettingsToggleRow(enabled.value, { enabled.value = it; calls++ },
                            "Reduce input images larger than 200 KB",
                            "Oversized images are resized before the next extraction.", "Image toggle")
                        EnumChoice(false, "Same-phone server with a long profile description", {}, enabled = true)
                        SettingsToggleRow(false, { calls++ }, "Remote providers", "Unavailable while saving", "Busy toggle", enabled = false)
                    }
                }
            }
        }
        val row = compose.onNodeWithContentDescription("Image toggle")
        row.assertHeightIsAtLeast(48.dp).performClick().assertIsOn()
        val title = compose.onNodeWithText("Reduce input images larger than 200 KB", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val toggle = row.fetchSemanticsNode().boundsInRoot
        assertTrue(title.left >= toggle.left && title.right <= toggle.right)
        assertTrue(title.height > 48f, "Large text must wrap without truncation")
        compose.onNodeWithText("Same-phone server with a long profile description").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Busy toggle").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun backKeepsUnsavedProfileUntilExplicitDiscard() {
        val vm = viewModel(FakeRepository())
        var navigations = 0
        compose.setContent {
            val state by vm.uiState.collectAsState()
            SmartExpenseTheme { SettingsScreen(state, vm, AppSettings(), {}, {}, {}, {}, {}, { navigations++ }) }
        }
        compose.onNodeWithText("Add profile").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Display name").performScrollTo().performTextInput("Unsaved profile")
        compose.onNodeWithContentDescription("Back to receipt workflow").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Keep editing").performClick()
        compose.runOnIdle {
            assertEquals("Unsaved profile", vm.uiState.value.editor!!.draft.displayName)
            assertEquals(0, navigations)
        }
        compose.onNodeWithContentDescription("Back to receipt workflow").performClick()
        compose.onNodeWithText("Discard").performClick()
        compose.runOnIdle { assertEquals(null, vm.uiState.value.editor); assertEquals(0, navigations) }
    }

    private fun viewModel(repository: FakeRepository) = ModelProfilesViewModel(
        repository, object : WritableApiKeyStore {
            override fun get(alias: String): String? = null
            override fun put(alias: String, value: String) {}
            override fun remove(alias: String) {}
        }, SelectedReceiptModelProviderResolver(repository),
        ModelProfileTestService { _, _ -> ProviderTestResult.Success },
    )
}
