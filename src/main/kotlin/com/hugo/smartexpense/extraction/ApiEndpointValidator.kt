package com.hugo.smartexpense.extraction

import java.net.URI

/** Validates the lexical endpoint before any networking; never resolves DNS. */
object ApiEndpointValidator {
    const val SAME_PHONE_DEFAULT = "http://127.0.0.1:8080/v1"
    fun normalize(baseUrl: String, category: ApiProfileCategory): String {
        val value = baseUrl.trim()
        if (category == ApiProfileCategory.SAME_PHONE_SERVER) {
            val match = Regex("http://127\\.0\\.0\\.1:([0-9]+)/([A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*)/?").matchEntire(value)
            require(match != null && match.groupValues[1].toIntOrNull() in 1..65535 &&
                match.groupValues[2].split('/').none { it == "." || it == ".." }) {
                "Use http://127.0.0.1:<port>/<API path> with port 1–65535 and simple ASCII path segments."
            }
        } else {
            val uri = runCatching { URI(value) }.getOrNull()
            require(uri != null && (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
                !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "Enter an absolute HTTP or HTTPS URL without credentials, query or fragment."
            }
        }
        return value.trimEnd('/')
    }
}
