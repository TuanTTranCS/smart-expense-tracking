package com.hugo.smartexpense.extraction

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Immutable reference captured once for an extraction, including fallback and recovery. */
data class ExtractionDateContext(val referenceDate: LocalDate) {
    fun guidance(): String = """
        Current reference date: ${referenceDate.format(englishDate)}.
        If a date in the expense text or receipt image is unclear or uses an ambiguous numeric format
        or two-digit year, use this reference date and the surrounding expense evidence to infer the
        most plausible intended date. For example, 10/15/26 with a reference date of Oct 16, 2026
        means October 15, 2026 (2026-10-15).
        Preserve clearly stated dates, including older dates. Do not substitute today's date for a
        missing date or turn an impossible date into a valid one. Flag uncertain inferences with
        low_confidence for explicit user review; conflicting evidence or no plausible interpretation
        still requires correction.
    """.trimIndent()

    companion object {
        private val englishDate = DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.ENGLISH)

        // Resolve the local zone at capture time, including device timezone changes after app launch.
        fun capture(): ExtractionDateContext = ExtractionDateContext(LocalDate.now())
    }
}
