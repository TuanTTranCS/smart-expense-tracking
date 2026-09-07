package com.hugo.smartexpense.extraction

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReceiptExtractionParserTest {
    private val parser = ReceiptExtractionParser()

    @Test
    fun parsesValidModelOutput() {
        val raw = """
            {
              "receiptDate": "2026-06-26",
              "merchantName": "Example Shop",
              "totalAmount": 42.35,
              "currency": "CAD",
              "extractionStatus": "confirmed",
              "confidence": 0.94,
              "merchantLocation": "Vancouver BC"
            }
        """.trimIndent()

        val parsed = assertIs<ParseResult.Valid>(parser.parse(raw)).value

        assertEquals(LocalDate.parse("2026-06-26"), parsed.receiptDate)
        assertEquals("Example Shop", parsed.merchantName)
        assertEquals(BigDecimal("42.35"), parsed.totalAmount)
        assertEquals("CAD", parsed.currency)
        assertEquals(ExtractionStatus.CONFIRMED, parsed.extractionStatus)
        assertEquals(BigDecimal("0.94"), parsed.confidence)
        assertEquals("Vancouver BC", parsed.merchantLocation)
    }

    @Test
    fun rejectsMalformedModelOutput() {
        val parsed = assertIs<ParseResult.Invalid>(parser.parse("merchant: Example Shop"))

        assertTrue(parsed.errors.any { it.contains("JSON object") })
    }

    @Test
    fun rejectsMissingRequiredFields() {
        val parsed = assertIs<ParseResult.Invalid>(
            parser.parse(
                """
                    {
                      "receiptDate": "2026-06-26",
                      "totalAmount": 42.35,
                      "currency": "CAD",
                      "extractionStatus": "confirmed"
                    }
                """.trimIndent()
            )
        )

        assertTrue(parsed.errors.any { it.contains("merchantName") })
    }

    @Test
    fun rejectsInvalidDateCurrencyAndAmount() {
        val parsed = assertIs<ParseResult.Invalid>(
            parser.parse(
                """
                    {
                      "receiptDate": "06/26/2026",
                      "merchantName": "Example Shop",
                      "totalAmount": -1,
                      "currency": "cad",
                      "extractionStatus": "confirmed"
                    }
                """.trimIndent()
            )
        )

        assertTrue(parsed.errors.any { it.contains("receiptDate") })
        assertTrue(parsed.errors.any { it.contains("totalAmount") })
        assertTrue(parsed.errors.any { it.contains("currency") })
    }

    @Test
    fun acceptsLowConfidenceAsManualReviewPath() {
        val parsed = assertIs<ParseResult.Valid>(
            parser.parse(
                """
                    {
                      "receiptDate": "2026-06-23",
                      "merchantName": "Daily Numbers",
                      "totalAmount": 60,
                      "currency": "CAD",
                      "extractionStatus": "low_confidence",
                      "confidence": 0.51,
                      "merchantLocation": null
                    }
                """.trimIndent()
            )
        ).value

        assertEquals(ExtractionStatus.LOW_CONFIDENCE, parsed.extractionStatus)
    }
}

