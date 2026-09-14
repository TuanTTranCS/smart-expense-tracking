package com.hugo.smartexpense.app.modelprofile.transfer

import android.content.ContentResolver
import android.net.Uri
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

class ProviderConfigDocumentStore(
    private val contentResolver: ContentResolver,
) {
    fun read(uri: Uri): String {
        val stream = contentResolver.openInputStream(uri)
            ?: throw ProviderConfigException("The selected provider configuration file could not be opened.")
        return stream.use { input ->
            InputStreamReader(input, StandardCharsets.UTF_8).use { it.readText() }
        }
    }

    fun write(uri: Uri, json: String) {
        val stream = contentResolver.openOutputStream(uri, "wt")
            ?: throw ProviderConfigException("The provider configuration destination could not be opened.")
        stream.use { output ->
            OutputStreamWriter(output, StandardCharsets.UTF_8).use { writer ->
                writer.write(json)
            }
        }
    }
}
