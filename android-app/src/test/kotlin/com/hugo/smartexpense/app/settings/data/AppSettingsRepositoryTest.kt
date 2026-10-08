package com.hugo.smartexpense.app.settings.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class AppSettingsRepositoryTest {
    @Test fun batchPolicyDefaultsAndPersistedBounds() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("batch-policy-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val repository = SharedPreferencesAppSettingsRepository(preferences)
        assertEquals(1, repository.settings.value.batchMaxConcurrency)
        assertEquals(3500, repository.settings.value.batchStartSpacingMillis)
        repository.setBatchMaxConcurrency(3)
        repository.setBatchStartSpacingMillis(500)
        val restored = SharedPreferencesAppSettingsRepository(preferences).settings.value
        assertEquals(3, restored.batchMaxConcurrency)
        assertEquals(500, restored.batchStartSpacingMillis)
        kotlin.test.assertFailsWith<IllegalArgumentException> { repository.setBatchMaxConcurrency(4) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { repository.setBatchStartSpacingMillis(-1) }
    }
    @Test fun deviceNameDefaultsToDetectedNameAndOverridePersists() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("device-name-settings-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val repository = SharedPreferencesAppSettingsRepository(preferences, "Google Pixel 7")
        assertEquals("Google Pixel 7", repository.settings.value.deviceName)
        repository.setDeviceNameOverride(" Hugo's phone ")
        assertEquals("Hugo's phone", repository.settings.value.deviceName)
        assertEquals("Hugo's phone", SharedPreferencesAppSettingsRepository(preferences, "Other device").settings.value.deviceName)
        repository.setDeviceNameOverride("")
        assertEquals("Google Pixel 7", repository.settings.value.deviceName)
    }
    @Test fun imageReductionDefaultsOnAndPersistsChanges() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("app-settings-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val repository = SharedPreferencesAppSettingsRepository(preferences)
        assertTrue(repository.settings.value.reduceOversizedImages)

        repository.setReduceOversizedImages(false)

        assertFalse(repository.settings.value.reduceOversizedImages)
        assertFalse(SharedPreferencesAppSettingsRepository(preferences).settings.value.reduceOversizedImages)
    }

    @Test fun debugOutputDefaultsOffAndPersistsChanges() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("debug-output-settings-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val repository = SharedPreferencesAppSettingsRepository(preferences)
        assertFalse(repository.settings.value.debugOutputEnabled)

        repository.setDebugOutputEnabled(true)
        assertTrue(repository.settings.value.debugOutputEnabled)
        assertTrue(SharedPreferencesAppSettingsRepository(preferences).settings.value.debugOutputEnabled)

        repository.setDebugOutputEnabled(false)
        assertFalse(SharedPreferencesAppSettingsRepository(preferences).settings.value.debugOutputEnabled)
    }
}
