package com.hugo.smartexpense.app.settings.data

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val reduceOversizedImages: Boolean = true,
)

interface AppSettingsRepository {
    val settings: StateFlow<AppSettings>
    fun setReduceOversizedImages(enabled: Boolean)
}

class SharedPreferencesAppSettingsRepository(
    private val preferences: SharedPreferences,
) : AppSettingsRepository {
    private val mutableSettings = MutableStateFlow(
        AppSettings(preferences.getBoolean(REDUCE_OVERSIZED_IMAGES, true)),
    )

    override val settings: StateFlow<AppSettings> = mutableSettings.asStateFlow()

    override fun setReduceOversizedImages(enabled: Boolean) {
        preferences.edit().putBoolean(REDUCE_OVERSIZED_IMAGES, enabled).apply()
        mutableSettings.value = mutableSettings.value.copy(reduceOversizedImages = enabled)
    }

    private companion object {
        const val REDUCE_OVERSIZED_IMAGES = "reduce_oversized_images"
    }
}
