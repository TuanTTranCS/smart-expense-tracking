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
    fun parsesTwoDistinctTransactionsWithoutMergingAmounts() {
        val raw = """{"receipts":[
          {"receiptDate":"2026-09-15","merchantName":"Shop A","totalAmount":12.34,"currency":"CAD","extractionStatus":"confirmed","confidence":0.9,"merchantLocation":null},
          {"receiptDate":"2026-09-16","merchantName":"Shop B","totalAmount":56.78,"currency":"CAD","extractionStatus":"low_confidence","confidence":0.6,"merchantLocation":null}
        ]}"""
        val values = assertIs<ParseResult.Valid>(parser.parse(raw)).values
        assertEquals(2, values.size)
        assertEquals("Shop A", values[0].merchantName)
        assertEquals(BigDecimal("12.34"), values[0].totalAmount)
        assertEquals("Shop B", values[1].merchantName)
        assertEquals(BigDecimal("56.78"), values[1].totalAmount)
    }

    @Test
    fun rejectsWholeBatchWhenOneReceiptIsInvalid() {
        val raw = """{"receipts":[{"receiptDate":"2026-09-15","merchantName":"A","totalAmount":1,"currency":"CAD","extractionStatus":"confirmed"},{"receiptDate":"2026-09-16","merchantName":"","totalAmount":2,"currency":"CAD","extractionStatus":"confirmed"}]}"""
        val invalid = assertIs<ParseResult.Invalid>(parser.parse(raw))
        assertTrue(invalid.errors.any { it.contains("Receipt 2: merchantName") })
    }

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
