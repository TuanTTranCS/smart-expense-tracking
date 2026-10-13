package com.hugo.smartexpense.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.unit.em
import com.hugo.smartexpense.app.R

@OptIn(ExperimentalTextApi::class)
private val Inter = FontFamily(
    Font(R.font.inter, weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.inter, weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.inter, weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.inter, weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

val ReceiptFontFamily = FontFamily(Font(R.font.roboto_mono))

private fun TextStyle.body() = copy(fontFamily = Inter)
private fun TextStyle.heading() = copy(
    fontFamily = Inter, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em,
)

internal val ExpenseTypography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.heading(),
        displayMedium = base.displayMedium.heading(),
        displaySmall = base.displaySmall.heading(),
        headlineLarge = base.headlineLarge.heading(),
        headlineMedium = base.headlineMedium.heading(),
        headlineSmall = base.headlineSmall.heading(),
        titleLarge = base.titleLarge.heading(),
        titleMedium = base.titleMedium.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.body(),
        bodyMedium = base.bodyMedium.body(),
        bodySmall = base.bodySmall.body(),
        labelLarge = base.labelLarge.body(),
        labelMedium = base.labelMedium.body(),
        labelSmall = base.labelSmall.body(),
    )
}

/** Follows system appearance; Android 12+ also supplies wallpaper-derived color roles. */
@Composable
internal fun SmartExpenseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, typography = ExpenseTypography, content = content)
}
