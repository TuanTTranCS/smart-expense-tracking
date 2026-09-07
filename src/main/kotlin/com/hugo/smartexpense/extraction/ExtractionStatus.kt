package com.hugo.smartexpense.extraction

enum class ExtractionStatus {
    CONFIRMED,
    MANUAL,
    LOW_CONFIDENCE,
    FAILED;

    fun wireName(): String = name.lowercase()

    companion object {
        fun fromWireName(value: String): ExtractionStatus? =
            entries.firstOrNull { it.wireName() == value.trim().lowercase() }
    }
}

