package com.hugo.smartexpense.app

import kotlin.test.Test
import kotlin.test.assertTrue

class ReceiptReviewValidationTest {
    private val valid = ReceiptReviewState(merchantName = "Walmart", totalAmount = "65", currency = "CAD", receiptDate = "2026-10-07")

    @Test fun validatesOnlyStructuredReviewAndAcceptsZero() {
        assertTrue(valid.expenseValidationErrors().isEmpty())
        assertTrue(valid.copy(totalAmount = "0").expenseValidationErrors().isEmpty())
    }

    @Test fun invalidOrUnresolvedReviewFieldsCannotExport() {
        val invalid = listOf(
            valid.copy(merchantName = " "), valid.copy(totalAmount = "15+50"),
            valid.copy(totalAmount = "NaN"), valid.copy(totalAmount = "Infinity"), valid.copy(totalAmount = "-1"),
            valid.copy(currency = "Canadian dollars"), valid.copy(currency = "cad"),
            valid.copy(receiptDate = "Oct 7 2026"), valid.copy(receiptDate = "2026-02-30"),
            valid.copy(receiptDate = "+2026-10-07"),
        )
        invalid.forEach { assertTrue(it.expenseValidationErrors().isNotEmpty(), it.toString()) }
    }
}
