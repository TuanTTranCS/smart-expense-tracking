package com.hugo.smartexpense.extraction

import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException

class ReceiptExtractionParser {
    fun parse(rawModelOutput: String, requireReviewMetadata: Boolean = false): ParseResult {
        val objects = extractObjects(rawModelOutput)
            ?: return ParseResult.Invalid(listOf("Model output must contain a JSON object or a non-empty JSON array of objects."), rawModelOutput)
        val results = objects.mapIndexed { index, json -> parseObject(json, rawModelOutput, index, requireReviewMetadata) }
        val invalid = results.filterIsInstance<ParseResult.Invalid>()
        if (invalid.isNotEmpty()) return ParseResult.Invalid(invalid.flatMap { it.errors }, rawModelOutput)
        return ParseResult.Valid(results.map { (it as ParseResult.Valid).value })
    }

    private fun parseObject(json: String, rawModelOutput: String, index: Int, requireReviewMetadata: Boolean): ParseResult {

        val fields = FlatJsonObjectParser.parse(json)
            ?: return ParseResult.Invalid(listOf("Model output JSON could not be parsed."), rawModelOutput)

        val errors = mutableListOf<String>()
        if (requireReviewMetadata) {
            val rawFields = requireNotNull(FlatJsonObjectParser.parse(json, preserveStringQuotes = true))
            listOf("receiptDate", "merchantName", "currency", "extractionStatus").forEach { field ->
                if (rawFields[field]?.startsWith('"') != true) errors += "$field must be a JSON string."
            }
            listOf("issues", "suggestedCorrections").forEach { field ->
                if (rawFields[field]?.startsWith('[') != true) errors += "$field must be a JSON array."
            }
            if (fields["currency"]?.let { Regex("[A-Z]{3}").matches(it) } != true)
                errors += "currency must be a three-letter uppercase code."
            val jsonNumber = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
            if (rawFields["totalAmount"]?.let(jsonNumber::matches) != true)
                errors += "totalAmount must be a finite JSON number."
            rawFields["confidence"]?.takeUnless { it == "null" }?.let {
                if (!jsonNumber.matches(it)) errors += "confidence must be a JSON number or null."
            }
            if (fields["receiptDate"]?.let { Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(it) } != true)
                errors += "receiptDate must be an ISO date in yyyy-MM-dd format."
            if (fields["extractionStatus"] !in setOf("confirmed", "low_confidence", "failed"))
                errors += "Typed review extractionStatus must be confirmed, low_confidence, or failed."
        }
        if (requireReviewMetadata && (!fields.containsKey("issues") || !fields.containsKey("suggestedCorrections")))
            errors += "Typed review must include issues and suggestedCorrections arrays."
        val issues = FlatJsonObjectParser.stringArray(fields["issues"])
        val suggestions = FlatJsonObjectParser.stringArray(fields["suggestedCorrections"])
        if (issues == null || suggestions == null) errors += "issues and suggestedCorrections must be arrays of strings."
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
            return ParseResult.Invalid(errors.map { "Receipt ${index + 1}: $it" }, rawModelOutput)
        }

        return ParseResult.Valid(listOf(
            ReceiptExtractionResult(
                receiptDate = receiptDate,
                merchantName = merchantName,
                totalAmount = totalAmount,
                currency = currency,
                extractionStatus = extractionStatus,
                confidence = confidence,
                merchantLocation = fields["merchantLocation"]?.takeUnless { it == "null" }?.trim(),
                rawModelOutput = rawModelOutput,
                issues = issues.orEmpty(),
                suggestedCorrections = suggestions.orEmpty(),
            )
        ))
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

    private fun extractObjects(raw: String): List<String>? {
        val stripped = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val value = if (stripped.startsWith('{') && Regex("^\\{\\s*\"receipts\"\\s*:").containsMatchIn(stripped)) {
            stripped.substringAfter(':').trim().removeSuffix("}").trim()
        } else stripped
        if (value.startsWith('{') && value.endsWith('}')) return listOf(value)
        if (!value.startsWith('[') || !value.endsWith(']')) return null
        val objects = mutableListOf<String>()
        var start = -1
        var depth = 0
        var quoted = false
        var escaped = false
        var expectsObject = true
        for (index in 1 until value.lastIndex) {
            val char = value[index]
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> { if (depth == 0) return null; quoted = true }
                '{' -> {
                    if (depth == 0) { if (!expectsObject) return null; start = index }
                    depth++
                }
                '}' -> {
                    if (--depth < 0) return null
                    if (depth == 0) { objects += value.substring(start, index + 1); expectsObject = false }
                }
                ',' -> if (depth == 0) { if (expectsObject) return null; expectsObject = true }
                ' ', '\n', '\r', '\t' -> Unit
                else -> if (depth == 0) return null
            }
        }
        return objects.takeIf { it.isNotEmpty() && depth == 0 && !quoted && !expectsObject }
    }
}

sealed class ParseResult {
    data class Valid(val values: List<ReceiptExtractionResult>) : ParseResult() {
        val value: ReceiptExtractionResult get() = values.first()
    }
    data class Invalid(val errors: List<String>, val rawOutput: String = "") : ParseResult()
}
