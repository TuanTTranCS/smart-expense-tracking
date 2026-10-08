package com.hugo.smartexpense.extraction

interface ReceiptModelClient {
    fun supportsDirectImageInput(): Boolean

    fun extractFromReceiptImage(
        receiptImage: ReceiptImage,
        prompt: String = ReceiptExtractionPrompt.multimodal,
    ): String

    fun extractFromReceiptText(receiptText: String, prompt: String = ReceiptExtractionPrompt.text): String

    fun reviewTypedExpense(draft: TypedExpenseDraft): String =
        extractFromReceiptText(draft.modelInput(), TypedExpenseReviewPrompt.text)
}
