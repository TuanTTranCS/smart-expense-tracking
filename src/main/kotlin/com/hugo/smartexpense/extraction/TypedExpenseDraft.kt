package com.hugo.smartexpense.extraction

/** Original user strings are retained separately from model suggestions. */
data class TypedExpenseDraft(
    val merchantName: String = "",
    val amount: String = "",
    val currency: String = "CAD",
    val date: String = "",
    val notes: String = "",
) {
    fun validationErrors(): Map<String, String> = buildMap {
        val hasExpenseText = listOf(merchantName, amount, date, notes).any { it.isNotBlank() } ||
            (currency.isNotBlank() && currency.trim() != "CAD")
        if (!hasExpenseText) put("draft", "Describe the expense in any field before extraction.")
    }

    fun modelInput(): String = listOf("merchantName" to merchantName, "amount" to amount,
        "currency" to currency, "date" to date, "notes" to notes).joinToString(",", "{", "}") { (key,value) ->
        "\"$key\":" + jsonString(value)
    }

    private fun jsonString(value: String) = buildString {
        append('"')
        value.forEach { ch -> when(ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            else -> if (ch.code < 32) append("\\u%04x".format(ch.code)) else append(ch)
        } }
        append('"')
    }
}
