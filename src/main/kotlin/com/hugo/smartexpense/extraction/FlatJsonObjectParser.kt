package com.hugo.smartexpense.extraction

internal object FlatJsonObjectParser {
    fun parse(json: String, preserveStringQuotes: Boolean = false): Map<String, String>? {
        val trimmed = json.trim()
        if (!trimmed.startsWith('{') || !trimmed.endsWith('}')) {
            return null
        }

        val body = trimmed.substring(1, trimmed.length - 1)
        val result = linkedMapOf<String, String>()
        var index = 0

        while (index < body.length) {
            index = index.skipWhitespace(body)
            if (index >= body.length) break

            val key = body.readString(index) ?: return null
            index = key.nextIndex.skipWhitespace(body)
            if (index >= body.length || body[index] != ':') return null
            index++
            index = index.skipWhitespace(body)

            val value = if (index < body.length && body[index] == '"') {
                val start = index
                val parsed = body.readString(index) ?: return null
                index = parsed.nextIndex
                if (preserveStringQuotes) body.substring(start, index) else parsed.value
            } else {
                val start = index
                var depth = 0
                var quoted = false
                var escaped = false
                while (index < body.length) {
                    val character = body[index]
                    if (quoted) {
                        if (escaped) escaped = false
                        else if (character == '\\') escaped = true
                        else if (character == '"') quoted = false
                    } else when (character) {
                        '"' -> quoted = true
                        '[', '{' -> depth++
                        ']', '}' -> depth--
                        ',' -> if (depth == 0) break
                    }
                    index++
                }
                if (depth != 0 || quoted) return null
                body.substring(start, index).trim().takeIf { it.isNotEmpty() } ?: return null
            }

            if (result.containsKey(key.value)) return null
            result[key.value] = value
            index = index.skipWhitespace(body)
            if (index == body.length) break
            if (body[index] != ',') return null
            index = (index + 1).skipWhitespace(body)
            if (index == body.length) return null
        }

        return result
    }

    fun stringArray(raw: String?): List<String>? {
        if (raw == null) return emptyList()
        val value = raw.trim()
        if (!value.startsWith('[') || !value.endsWith(']')) return null
        var index = 1
        val values = mutableListOf<String>()
        while (true) {
            index = index.skipWhitespace(value)
            if (index == value.lastIndex) return values
            val parsed = value.readString(index) ?: return null
            values += parsed.value
            index = parsed.nextIndex.skipWhitespace(value)
            if (index == value.lastIndex) return values
            if (value[index] != ',') return null
            index = (index + 1).skipWhitespace(value)
            if (index == value.lastIndex) return null
        }
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
                            'u' -> {
                                if (index + 4 >= length) return null
                                val code = substring(index + 1, index + 5).toIntOrNull(16) ?: return null
                                index += 4
                                code.toChar()
                            }
                            else -> return null
                        }
                    )
                }
                else -> { if (char.code < 32) return null; builder.append(char) }
            }
            index++
        }
        return null
    }

    private fun Int.skipWhitespace(input: String): Int {
        var index = this
        while (index < input.length && input[index].isWhitespace()) index++
        return index
    }

    private data class ParsedString(val value: String, val nextIndex: Int)
}

