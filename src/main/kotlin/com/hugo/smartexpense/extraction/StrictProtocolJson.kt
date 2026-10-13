package com.hugo.smartexpense.extraction

/** Small bounded RFC 8259 decoder; duplicate fields and trailing data are rejected. */
internal object StrictProtocolJson {
    fun objectValue(text: String): Map<String, Any?> {
        require(text.toByteArray(Charsets.UTF_8).size <= 1_048_576) { "Response exceeds 1 MiB." }
        val parser = Parser(text)
        val value = parser.value(0)
        parser.space()
        require(parser.index == text.length && value is Map<*, *>) { "Expected one JSON object." }
        @Suppress("UNCHECKED_CAST") return value as Map<String, Any?>
    }
    private class Parser(val text: String) {
        var index = 0
        fun space() { while (index < text.length && text[index] in " \t\r\n") index++ }
        fun value(depth: Int): Any? {
            require(depth <= 64)
            space(); require(index < text.length)
            return when (text[index]) {
                '{' -> {
                    index++; space(); val result = linkedMapOf<String, Any?>()
                    if (take('}')) result else {
                        do {
                            space(); val key = string(); space(); require(take(':'))
                            require(!result.containsKey(key)); result[key] = value(depth + 1); space()
                        } while (take(','))
                        require(take('}')); result
                    }
                }
                '[' -> {
                    index++; space(); val result = mutableListOf<Any?>()
                    if (take(']')) result else {
                        do { result += value(depth + 1); space() } while (take(','))
                        require(take(']')); result
                    }
                }
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> {
                    val match = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?").find(text, index)
                    require(match != null && match.range.first == index)
                    index = match.range.last + 1; match.value.toBigDecimal()
                }
            }
        }
        fun take(char: Char): Boolean = if (index < text.length && text[index] == char) { index++; true } else false
        fun literal(word: String, value: Any?): Any? { require(text.startsWith(word, index)); index += word.length; return value }
        fun string(): String {
            require(take('"')); val result = StringBuilder()
            while (index < text.length) {
                val char = text[index++]
                if (char == '"') return result.toString()
                require(char.code >= 32)
                if (char != '\\') result.append(char) else {
                    require(index < text.length)
                    result.append(when (val escaped = text[index++]) {
                        '"', '\\', '/' -> escaped
                        'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                        'u' -> {
                            require(index + 4 <= text.length)
                            val digits = text.substring(index, index + 4)
                            require(digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "Invalid Unicode escape." }
                            index += 4
                            digits.toInt(16).toChar()
                        }
                        else -> error("Invalid JSON escape.")
                    })
                }
            }
            error("Unclosed JSON string.")
        }
    }
}
