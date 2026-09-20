package com.hugo.smartexpense.app.settings.data

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val reduceOversizedImages: Boolean = true,
    val debugOutputEnabled: Boolean = false,
    val deviceNameOverride: String = "",
    val detectedDeviceName: String = "Android device",
) {
    val deviceName: String get() = deviceNameOverride.ifBlank { detectedDeviceName }
}

interface AppSettingsRepository {
    val settings: StateFlow<AppSettings>
    fun setReduceOversizedImages(enabled: Boolean)
    fun setDebugOutputEnabled(enabled: Boolean)
    fun setDeviceNameOverride(name: String)
}

class SharedPreferencesAppSettingsRepository(
    private val preferences: SharedPreferences,
    detectedDeviceName: String = "Android device",
) : AppSettingsRepository {
    private val mutableSettings = MutableStateFlow(
        AppSettings(
            reduceOversizedImages = preferences.getBoolean(REDUCE_OVERSIZED_IMAGES, true),
            debugOutputEnabled = preferences.getBoolean(DEBUG_OUTPUT_ENABLED, false),
            deviceNameOverride = preferences.getString(DEVICE_NAME_OVERRIDE, "").orEmpty(),
            detectedDeviceName = detectedDeviceName,
        ),
    )

    override val settings: StateFlow<AppSettings> = mutableSettings.asStateFlow()

    override fun setReduceOversizedImages(enabled: Boolean) {
        preferences.edit().putBoolean(REDUCE_OVERSIZED_IMAGES, enabled).apply()
        mutableSettings.value = mutableSettings.value.copy(reduceOversizedImages = enabled)
    }

    override fun setDebugOutputEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(DEBUG_OUTPUT_ENABLED, enabled).apply()
        mutableSettings.value = mutableSettings.value.copy(debugOutputEnabled = enabled)
    }

    override fun setDeviceNameOverride(name: String) {
        val normalized = name.trim().take(100)
        preferences.edit().putString(DEVICE_NAME_OVERRIDE, normalized).apply()
        mutableSettings.value = mutableSettings.value.copy(deviceNameOverride = normalized)
    }

    private companion object {
        const val REDUCE_OVERSIZED_IMAGES = "reduce_oversized_images"
        const val DEBUG_OUTPUT_ENABLED = "debug_output_enabled"
        const val DEVICE_NAME_OVERRIDE = "device_name_override"
    }
}
