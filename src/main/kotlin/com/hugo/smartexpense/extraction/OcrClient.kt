package com.hugo.smartexpense.extraction

interface OcrClient {
    fun extractText(receiptImage: ReceiptImage): String
}
