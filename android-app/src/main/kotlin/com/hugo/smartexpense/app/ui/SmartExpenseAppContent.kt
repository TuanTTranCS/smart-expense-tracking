package com.hugo.smartexpense.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal fun SmartExpenseAppContent(
    modifier: Modifier = Modifier,
    contentWindowInsets: WindowInsets = WindowInsets.safeDrawing,
    content: @Composable () -> Unit,
) {
    MaterialTheme {
        Surface(modifier.fillMaxSize()) {
            // Reserve system UI space outside the scroll viewport and consume it once
            // for both destinations. safeDrawing also covers display cutouts and the IME.
            Box(Modifier.fillMaxSize().windowInsetsPadding(contentWindowInsets)) {
                content()
            }
        }
    }
}
