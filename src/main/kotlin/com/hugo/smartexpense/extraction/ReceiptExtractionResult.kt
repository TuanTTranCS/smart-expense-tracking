package com.hugo.smartexpense.extraction

import java.math.BigDecimal
import java.time.LocalDate

data class ReceiptExtractionResult(
    val receiptDate: LocalDate,
    val merchantName: String,
    val totalAmount: BigDecimal,
    val currency: String,
    val extractionStatus: ExtractionStatus,
    val confidence: BigDecimal? = null,
    val merchantLocation: String? = null,
    val rawModelOutput: String,
)

