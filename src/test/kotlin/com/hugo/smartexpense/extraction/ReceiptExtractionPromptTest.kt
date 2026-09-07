package com.hugo.smartexpense.extraction

import kotlin.test.Test
import kotlin.test.assertContains

class ReceiptExtractionPromptTest {
    @Test
    fun multimodalPromptUsesFinalizedAmountForMatchingReceiptDocuments() {
        assertMultipleReceiptPolicy(ReceiptExtractionPrompt.multimodal)
        assertContains(ReceiptExtractionPrompt.multimodal, "If the image contains multiple receipt documents")
    }

    @Test
    fun textPromptUsesFinalizedAmountForMatchingReceiptDocuments() {
        assertMultipleReceiptPolicy(ReceiptExtractionPrompt.text)
        assertContains(ReceiptExtractionPrompt.text, "If the text contains multiple receipt documents")
    }

    private fun assertMultipleReceiptPolicy(prompt: String) {
        assertContains(prompt, "represent the same transaction")
        assertContains(prompt, "use the finalized amount charged or payable, including tax and tip")
        assertContains(prompt, "Do not add together totals")
        assertContains(prompt, "Use \"low_confidence\"")
    }
}
