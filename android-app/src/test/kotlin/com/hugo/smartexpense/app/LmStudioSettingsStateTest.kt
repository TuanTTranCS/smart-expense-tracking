package com.hugo.smartexpense.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LmStudioSettingsStateTest {
    @Test
    fun defaultsToRemoteProvidersDisabled() {
        assertFalse(LmStudioSettingsState().remoteEnabled)
    }

    @Test
    fun requiresAnHttpOrHttpsUrlAndModelId() {
        assertTrue(LmStudioSettingsState().isValid())
        assertFalse(LmStudioSettingsState(baseUrl = "10.0.0.207", modelId = "model").isValid())
        assertFalse(LmStudioSettingsState(baseUrl = "http://10.0.0.207", modelId = "").isValid())
    }
}
