package com.hugo.smartexpense.app.settings.data

enum class AppThemeMode(val preferenceValue: String) {
    LIGHT("light"),
    DARK("dark"),
    SYSTEM("system");

    fun isDarkTheme(systemDarkTheme: Boolean): Boolean = when (this) {
        LIGHT -> false
        DARK -> true
        SYSTEM -> systemDarkTheme
    }

    companion object {
        fun fromPreference(value: String?): AppThemeMode =
            entries.firstOrNull { it.preferenceValue == value } ?: SYSTEM
    }
}
