package com.aistudio.dieselstationsms.kxmpzq

/**
 * Validates and sanitizes payloads received through the WebView bridge.
 * This implementation deliberately avoids Android's JSONObject serialization APIs
 * so the same security contract is testable in local JVM unit tests.
 */
object SecurityValidator {
    private const val MAX_OPERATIONAL_JSON_BYTES = 256 * 1024

    private val FORBIDDEN_KEYS = setOf(
        "id", "created_at", "updated_at", "audit_log", "balance", "current_balance", "total_due"
    )

    private val BLOCKED_GENERAL_CRUD_TABLES = setOf(
        "fuel_sales", "payments", "invoices", "ledger",
        "stock_movements", "financial_idempotency_keys", "users", "permissions"
    )

    fun sanitizeOperationalJson(jsonString: String): String {
        if (jsonString.toByteArray(Charsets.UTF_8).size > MAX_OPERATIONAL_JSON_BYTES) return "{}"
        return try {
            val parser = JsonSanitizerParser(jsonString)
            val sanitized = parser.parseObject()
            parser.requireEnd()
            sanitized
        } catch (_: Exception) {
            "{}"
        }
    }

    fun isTableAllowedForGeneralCrud(tableName: String): Boolean {
        val normalizedTableName = tableName.trim().lowercase()
        return normalizedTableName.isNotEmpty() &&
            normalizedTableName.matches(Regex("[a-z0-9_]+")) &&
            !BLOCKED_GENERAL_CRUD_TABLES.contains(normalizedTableName)
    }

    private class JsonSanitizerParser(private val source: String) {
        private var index = 0

        fun parseObject(): String {
            skipWhitespace()
            expect('{')
            skipWhitespace()
            val fields = mutableListOf<String>()
            if (peek('}')) {
                index++
                return "{}"
            }
            while (true) {
                val key = parseString()
                skipWhitespace()
                expect(':')
                val value = parseValue()
                if (!FORBIDDEN_KEYS.contains(key.lowercase())) {
                    fields += quote(key) + ":" + value
                }
                skipWhitespace()
                when {
                    peek(',') -> index++
                    peek('}') -> {
                        index++
                        return "{" + fields.joinToString(",") + "}"
                    }
                    else -> error("Expected ',' or '}'")
                }
                skipWhitespace()
            }
        }

        fun requireEnd() {
            skipWhitespace()
            if (index != source.length) error("Trailing JSON content")
        }

        private fun parseValue(): String {
            skipWhitespace()
            if (index >= source.length) error("Missing JSON value")
            return when (source[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> quote(parseString())
                't' -> parseLiteral("true")
                'f' -> parseLiteral("false")
                'n' -> parseLiteral("null")
                '-', in '0'..'9' -> parseNumber()
                else -> error("Invalid JSON value")
            }
        }

        private fun parseArray(): String {
            expect('[')
            skipWhitespace()
            val values = mutableListOf<String>()
            if (peek(']')) {
                index++
                return "[]"
            }
            while (true) {
                values += parseValue()
                skipWhitespace()
                when {
                    peek(',') -> index++
                    peek(']') -> {
                        index++
                        return "[" + values.joinToString(",") + "]"
                    }
                    else -> error("Expected ',' or ']'")
                }
                skipWhitespace()
            }
        }

        private fun parseString(): String {
            expect('"')
            val result = StringBuilder()
            while (index < source.length) {
                when (val character = source[index++]) {
                    '"' -> return result.toString()
                    '\\' -> {
                        if (index >= source.length) error("Invalid string escape")
                        when (val escaped = source[index++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000C')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> {
                                if (index + 4 > source.length) error("Invalid unicode escape")
                                val code = source.substring(index, index + 4).toIntOrNull(16)
                                    ?: error("Invalid unicode escape")
                                result.append(code.toChar())
                                index += 4
                            }
                            else -> error("Invalid string escape: $escaped")
                        }
                    }
                    else -> {
                        if (character.code < 0x20) error("Control character in string")
                        result.append(character)
                    }
                }
            }
            error("Unterminated string")
        }

        private fun parseLiteral(literal: String): String {
            if (!source.regionMatches(index, literal, 0, literal.length)) error("Invalid literal")
            index += literal.length
            return literal
        }

        private fun parseNumber(): String {
            val start = index
            if (peek('-')) index++
            if (index >= source.length) error("Invalid number")
            if (peek('0')) {
                index++
            } else {
                requireDigit()
                while (index < source.length && source[index].isDigit()) index++
            }
            if (peek('.')) {
                index++
                requireDigit()
                while (index < source.length && source[index].isDigit()) index++
            }
            if (index < source.length && (source[index] == 'e' || source[index] == 'E')) {
                index++
                if (index < source.length && (source[index] == '+' || source[index] == '-')) index++
                requireDigit()
                while (index < source.length && source[index].isDigit()) index++
            }
            return source.substring(start, index)
        }

        private fun requireDigit() {
            if (index >= source.length || !source[index].isDigit()) error("Expected digit")
            index++
        }

        private fun skipWhitespace() {
            while (index < source.length && source[index].isWhitespace()) index++
        }

        private fun peek(expected: Char): Boolean = index < source.length && source[index] == expected

        private fun expect(expected: Char) {
            if (!peek(expected)) error("Expected '$expected'")
            index++
        }

        private fun quote(value: String): String {
            val escaped = buildString(value.length + 2) {
                value.forEach { character ->
                    when (character) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\b' -> append("\\b")
                        '\u000C' -> append("\\f")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
                    }
                }
            }
            return "\"$escaped\""
        }
    }
}
