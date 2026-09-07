package com.hugo.smartexpense.extraction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LmStudioTestProfileTest {
    @Test
    fun providesTheConfiguredEmulatorTestEndpointAndModel() {
        val provider = LmStudioTestProfile.provider()

        assertEquals("http://10.0.0.207:1234/v1", provider.baseUrl)
        assertEquals("google/gemma-4-e2b", provider.modelId)
        assertTrue(provider.inputMode == RemoteInputMode.DIRECT_IMAGE)
        assertTrue(provider.structuredOutputFormat == RemoteStructuredOutputFormat.JSON_SCHEMA)
    }
}
