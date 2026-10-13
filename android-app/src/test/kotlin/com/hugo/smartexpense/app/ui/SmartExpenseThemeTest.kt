package com.hugo.smartexpense.app.ui

import android.content.res.Configuration
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmartExpenseThemeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fallbackAppearanceChangesWithoutRecreatingContentAndMeetsTextContrast() = verifyThemes(false)

    @Test fun dynamicAppearanceChangesWithoutRecreatingContentAndMeetsTextContrast() = verifyThemes(true)

    @Test @Config(sdk = [28])
    fun preAndroid12UsesAccessibleFallbackWhenDynamicColorIsRequested() = verifyThemes(true)

    @Test fun financialHeadingsUseBundledBoldTypographyAndTightTracking() {
        assertEquals(FontWeight.Bold, ExpenseTypography.headlineLarge.fontWeight)
        assertEquals((-0.02).em, ExpenseTypography.headlineLarge.letterSpacing)
        assertEquals(ExpenseTypography.bodyLarge.fontFamily, ExpenseTypography.headlineLarge.fontFamily)
        assertNotEquals(ReceiptFontFamily, ExpenseTypography.bodyLarge.fontFamily)
    }

    @Test fun defaultThemeFollowsSystemAppearanceChanges() {
        val night = mutableStateOf(false)
        var scheme: ColorScheme? = null
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night.value) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                SmartExpenseTheme { scheme = MaterialTheme.colorScheme }
            }
        }
        compose.waitForIdle()
        val lightSurface = requireNotNull(scheme).surface
        compose.runOnIdle { night.value = true }
        compose.waitForIdle()
        assertTrue(lightSurface.luminance() > requireNotNull(scheme).surface.luminance())
    }

    private fun verifyThemes(dynamic: Boolean) {
        val dark = mutableStateOf(false)
        var scheme: ColorScheme? = null
        compose.setContent {
            SmartExpenseTheme(darkTheme = dark.value, dynamicColor = dynamic) {
                scheme = MaterialTheme.colorScheme
            }
        }
        compose.waitForIdle()
        val light = requireNotNull(scheme)
        assertTextContrast(light)
        compose.runOnIdle { dark.value = true }
        compose.waitForIdle()
        val night = requireNotNull(scheme)
        assertTextContrast(night)
        assertNotEquals(light.surface, night.surface)
        assertTrue(light.surface.luminance() > night.surface.luminance())
    }

    private fun assertTextContrast(colors: ColorScheme) {
        val roles = mapOf(
            "surface" to (colors.onSurface to colors.surface),
            "receipt card" to (colors.onSurfaceVariant to colors.surfaceVariant),
            "primary status" to (colors.onPrimaryContainer to colors.primaryContainer),
            "secondary status" to (colors.onSecondaryContainer to colors.secondaryContainer),
            "error status" to (colors.onErrorContainer to colors.errorContainer),
            "primary button" to (colors.onPrimary to colors.primary),
            "surface action" to (colors.primary to colors.surface),
            "receipt error" to (colors.error to colors.surfaceVariant),
            "settings supporting text" to (colors.onSurfaceVariant to colors.surfaceContainerLow),
            "profile text" to (colors.onSurface to colors.surfaceContainer),
            "profile action" to (colors.primary to colors.surfaceContainer),
        )
        roles.forEach { (name, pair) ->
            assertTrue(contrast(pair.first, pair.second) >= 4.5, "$name text contrast must meet WCAG AA")
        }
    }

    private fun contrast(first: Color, second: Color): Double {
        val a = first.luminance().toDouble()
        val b = second.luminance().toDouble()
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }
}
