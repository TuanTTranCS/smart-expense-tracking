package com.hugo.smartexpense.app.settings.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AppSettingsRepositoryTest {
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
}
