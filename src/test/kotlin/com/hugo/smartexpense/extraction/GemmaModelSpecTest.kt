package com.hugo.smartexpense.extraction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GemmaModelSpecTest {
    @Test
    fun pinsWorkstreamOneToOfficialGemma4E2BArtifact() {
        val spec = GemmaModels.gemma4E2BItLiteRtLm

        assertEquals("Gemma 4", spec.family)
        assertEquals("E2B-it", spec.variant)
        assertEquals(
            "litert-community/gemma-4-E2B-it-litert-lm",
            spec.huggingFaceRepo,
        )
        assertEquals("gemma-4-E2B-it.litertlm", spec.defaultLiteRtLmFileName)
        assertEquals("gemma-4-E2B-it-web.litertlm", spec.webLiteRtLmFileName)
        assertTrue(spec.supportsDirectImageInput)
    }
}
