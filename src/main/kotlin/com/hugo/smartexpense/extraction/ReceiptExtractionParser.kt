package com.hugo.smartexpense.extraction

import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException

class ReceiptExtractionParser {
    fun parse(rawModelOutput: String): ParseResult {
        val json = extractJsonObject(rawModelOutput)
            ?: return ParseResult.Invalid(listOf("Model output must contain one JSON object."), rawModelOutput)

        val fields = FlatJsonObjectParser.parse(json)
            ?: return ParseResult.Invalid(listOf("Model output JSON could not be parsed."), rawModelOutput)

        val errors = mutableListOf<String>()
        val receiptDate = parseDate(fields["receiptDate"], "receiptDate", errors)
        val merchantName = fields["merchantName"]?.trim().orEmpty()
        if (merchantName.isBlank()) {
            errors += "merchantName is required."
        }

        val totalAmount = parseMoney(fields["totalAmount"], "totalAmount", errors)
        val currency = fields["currency"]?.trim().orEmpty()
        if (!Regex("^[A-Z]{3}$").matches(currency)) {
            errors += "currency must be a three-letter uppercase code."
        }

        val extractionStatus = fields["extractionStatus"]?.let(ExtractionStatus::fromWireName)
        if (extractionStatus == null) {
            errors += "extractionStatus must be confirmed, manual, low_confidence, or failed."
        }

        val confidence = fields["confidence"]?.takeUnless { it == "null" }?.let {
            parseMoney(it, "confidence", errors)
        }
        if (confidence != null && (confidence < BigDecimal.ZERO || confidence > BigDecimal.ONE)) {
            errors += "confidence must be between 0 and 1."
        }

        if (errors.isNotEmpty() || receiptDate == null || totalAmount == null || extractionStatus == null) {
            return ParseResult.Invalid(errors, rawModelOutput)
        }

        return ParseResult.Valid(
            ReceiptExtractionResult(
                receiptDate = receiptDate,
                merchantName = merchantName,
                totalAmount = totalAmount,
                currency = currency,
                extractionStatus = extractionStatus,
                confidence = confidence,
                merchantLocation = fields["merchantLocation"]?.takeUnless { it == "null" }?.trim(),
                rawModelOutput = rawModelOutput,
            )
        )
    }

    private fun parseDate(value: String?, field: String, errors: MutableList<String>): LocalDate? {
        if (value.isNullOrBlank()) {
            errors += "$field is required."
            return null
        }

        return try {
            LocalDate.parse(value)
        } catch (_: DateTimeParseException) {
            errors += "$field must be an ISO date in yyyy-MM-dd format."
            null
        }
    }

    private fun parseMoney(value: String?, field: String, errors: MutableList<String>): BigDecimal? {
        if (value.isNullOrBlank()) {
            errors += "$field is required."
            return null
        }

        return try {
            BigDecimal(value).also {
                if (it < BigDecimal.ZERO) {
                    errors += "$field must be non-negative."
                }
            }
        } catch (_: NumberFormatException) {
            errors += "$field must be numeric."
            null
        }
    }

    private fun extractJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) {
            return null
        }
        return raw.substring(start, end + 1)
    }
}

sealed class ParseResult {
    data class Valid(val value: ReceiptExtractionResult) : ParseResult()
    data class Invalid(val errors: List<String>, val rawOutput: String = "") : ParseResult()
}
