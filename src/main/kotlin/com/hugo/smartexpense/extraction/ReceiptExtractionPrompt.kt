package com.hugo.smartexpense.extraction

object ReceiptExtractionPrompt {
    val multimodal: String = """
        Extract expenses from the receipt image.
        Return only a JSON object containing a "receipts" array of expense objects with these fields:
        {"receipts":[{
          "receiptDate": "yyyy-MM-dd",
          "merchantName": "merchant, shop, service, or payee name",
          "totalAmount": 0.00,
          "currency": "CAD",
          "extractionStatus": "confirmed | low_confidence | failed",
          "confidence": 0.0,
          "merchantLocation": null
        }]}
        Use the receipt image as the primary source.
        If the image contains multiple receipt documents, first determine whether they represent the same transaction by comparing the merchant, date/time, order or transaction identifiers, line items, and amounts.
        When an itemized receipt and a finalized receipt represent the same transaction, extract one expense and use the finalized amount charged or payable, including tax and tip, as "totalAmount".
        Do not add together totals from receipt documents that represent the same transaction.
        When receipt documents clearly represent different transactions, return one separate array element for each transaction. Never merge their amounts.
        When documents may represent different transactions, return separate elements and use "low_confidence" for uncertain fields.
        Use "low_confidence" if the finalized amount cannot be identified reliably.
        Use "low_confidence" when any required field is uncertain.
        Use "failed" when the receipt does not contain enough information.
        Do not include markdown, comments, explanations, or extra keys.
    """.trimIndent()

    val text: String = """
        Extract expenses from the receipt text.
        Return only a JSON object containing a "receipts" array of expense objects with these fields:
        {"receipts":[{
          "receiptDate": "yyyy-MM-dd",
          "merchantName": "merchant, shop, service, or payee name",
          "totalAmount": 0.00,
          "currency": "CAD",
          "extractionStatus": "confirmed | low_confidence | failed",
          "confidence": 0.0,
          "merchantLocation": null
        }]}
        If the text contains multiple receipt documents, first determine whether they represent the same transaction by comparing the merchant, date/time, order or transaction identifiers, line items, and amounts.
        When an itemized receipt and a finalized receipt represent the same transaction, extract one expense and use the finalized amount charged or payable, including tax and tip, as "totalAmount".
        Do not add together totals from receipt documents that represent the same transaction.
        When receipt documents clearly represent different transactions, return one separate array element for each transaction. Never merge their amounts.
        When documents may represent different transactions, return separate elements and use "low_confidence" for uncertain fields.
        Use "low_confidence" if the finalized amount cannot be identified reliably.
        Use "low_confidence" when any required field is uncertain.
        Use "failed" when the receipt does not contain enough information.
        Do not include markdown, comments, explanations, or extra keys.
    """.trimIndent()
}
