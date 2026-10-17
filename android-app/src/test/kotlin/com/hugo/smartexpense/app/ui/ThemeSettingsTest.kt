package com.hugo.smartexpense.app.ui

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.test.core.app.ApplicationProvider
import com.hugo.smartexpense.app.settings.data.AppThemeMode
import com.hugo.smartexpense.app.settings.data.SharedPreferencesAppSettingsRepository
import com.hugo.smartexpense.app.settings.ui.ThemeSettingsCard
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectingThemeUpdatesRootImmediatelyAndPreservesContentState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("theme-ui-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val repository = SharedPreferencesAppSettingsRepository(preferences)
        val systemDark = mutableStateOf(false)
        var scheme: ColorScheme? = null
        var contentCreations = 0
        compose.setContent {
            val settings by repository.settings.collectAsState()
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (systemDark.value) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                val darkTheme = settings.themeMode.isDarkTheme(androidx.compose.foundation.isSystemInDarkTheme())
                SmartExpenseAppContent(contentWindowInsets = WindowInsets(0), darkTheme = darkTheme) {
                    remember { contentCreations++ }
                    scheme = MaterialTheme.colorScheme
                    ThemeSettingsCard(settings.themeMode, repository::setThemeMode)
                }
            }
        }
        compose.onNodeWithText("System").assertIsSelected()
        compose.waitForIdle()
        val lightSurface = requireNotNull(scheme).surface
        compose.onNodeWithText("Dark").assertHeightIsAtLeast(48.dp).performClick().assertIsSelected()
        compose.runOnIdle {
            assertTrue(lightSurface.luminance() > requireNotNull(scheme).surface.luminance())
            assertEquals(AppThemeMode.DARK, SharedPreferencesAppSettingsRepository(preferences).settings.value.themeMode)
        }
        compose.onNodeWithText("Light").performClick().assertIsSelected()
        compose.runOnIdle { systemDark.value = true }
        compose.runOnIdle { assertEquals(lightSurface, requireNotNull(scheme).surface) }
        compose.onNodeWithText("System").performClick().assertIsSelected()
        compose.runOnIdle { assertTrue(lightSurface.luminance() > requireNotNull(scheme).surface.luminance()) }
        compose.runOnIdle { systemDark.value = false }
        compose.runOnIdle {
            assertEquals(lightSurface, requireNotNull(scheme).surface)
            assertEquals(1, contentCreations)
        }
    }

    @Suppress("DEPRECATION")
    @Test @Config(sdk = [28, 35])
    fun systemBarIconsFollowAppAppearance() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val window = controller.get().window
            val insets = WindowCompat.getInsetsController(window, window.decorView)
            updateSystemBarAppearance(window, darkTheme = true)
            assertFalse(insets.isAppearanceLightStatusBars)
            assertFalse(insets.isAppearanceLightNavigationBars)
            if (Build.VERSION.SDK_INT == 28) assertEquals(Color.argb(0x80, 0x1b, 0x1b, 0x1b), window.navigationBarColor)
            updateSystemBarAppearance(window, darkTheme = false)
            assertTrue(insets.isAppearanceLightStatusBars)
            assertTrue(insets.isAppearanceLightNavigationBars)
            if (Build.VERSION.SDK_INT == 28) assertEquals(Color.argb(0xe6, 0xff, 0xff, 0xff), window.navigationBarColor)
        }
    }
}
