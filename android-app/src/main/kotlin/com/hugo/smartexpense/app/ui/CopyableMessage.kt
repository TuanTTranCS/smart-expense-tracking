package com.hugo.smartexpense.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle

/** Copies the displayed diagnostic verbatim, only on an explicit user action. */
@Suppress("DEPRECATION")
@Composable
internal fun CopyableMessage(
    message: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
) {
    val clipboard = LocalClipboardManager.current
    Column(modifier) {
        SelectionContainer { Text(message, color = color, style = style) }
        if (message.isNotBlank()) {
            TextButton(
                onClick = { clipboard.setText(AnnotatedString(message)) },
                modifier = Modifier.semantics { contentDescription = "Copy message" },
            ) { Text("Copy message") }
        }
    }
}
