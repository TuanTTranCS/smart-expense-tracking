package com.hugo.smartexpense.extraction

data class ReceiptImage(
    val sourceName: String,
    val bytes: ByteArray,
    val mimeType: String,
)
