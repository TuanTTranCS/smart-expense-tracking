package com.hugo.smartexpense.extraction

internal object FlatJsonObjectParser {
    fun parse(json: String): Map<String, String>? {
        val trimmed = json.trim()
        if (!trimmed.startsWith('{') || !trimmed.endsWith('}')) {
            return null
        }

        val body = trimmed.substring(1, trimmed.length - 1)
        val result = linkedMapOf<String, String>()
        var index = 0

        while (index < body.length) {
            index = body.skipWhitespaceAndCommas(index)
            if (index >= body.length) break

            val key = body.readString(index) ?: return null
            index = key.nextIndex.skipWhitespace(body)
            if (index >= body.length || body[index] != ':') return null
            index++
            index = index.skipWhitespace(body)

            val value = if (index < body.length && body[index] == '"') {
                val parsed = body.readString(index) ?: return null
                index = parsed.nextIndex
                parsed.value
            } else {
                val start = index
                while (index < body.length && body[index] != ',') index++
                body.substring(start, index).trim()
            }

            result[key.value] = value
            index = body.skipWhitespaceAndCommas(index)
        }

        return result
    }

    private fun String.readString(start: Int): ParsedString? {
        if (start >= length || this[start] != '"') return null
        val builder = StringBuilder()
        var index = start + 1
        while (index < length) {
            val char = this[index]
            when {
                char == '"' -> return ParsedString(builder.toString(), index + 1)
                char == '\\' -> {
                    index++
                    if (index >= length) return null
                    builder.append(
                        when (val escaped = this[index]) {
                            '"', '\\', '/' -> escaped
                            'b' -> '\b'
                            'f' -> '\u000C'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            else -> return null
                        }
                    )
                }
                else -> builder.append(char)
            }
            index++
        }
        return null
    }

    private fun String.skipWhitespaceAndCommas(start: Int): Int {
        var index = start
        while (index < length && (this[index].isWhitespace() || this[index] == ',')) index++
        return index
    }

    private fun Int.skipWhitespace(input: String): Int {
        var index = this
        while (index < input.length && input[index].isWhitespace()) index++
        return index
    }

    private data class ParsedString(val value: String, val nextIndex: Int)
}

