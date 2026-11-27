package dev.cinematic.p0.gpu.mesa

import java.math.BigDecimal

// Golden harness 専用。機械生成の基準 JSON を読み、候補の固定スキーマを書き出す。
internal object MesaGoldenJson {
    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> buildString {
            append('"')
            for (char in value) {
                when {
                    char == '"' || char == '\\' -> append('\\').append(char)
                    char < ' ' -> append("\\u").append(char.code.toString(16).padStart(4, '0'))
                    else -> append(char)
                }
            }
            append('"')
        }
        is Double -> BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(", ", "{", "}") { (key, item) ->
            "${encode(key as String)}: ${encode(item)}"
        }
        is List<*> -> value.joinToString(", ", "[", "]") { encode(it) }
        else -> error("Unsupported golden JSON value: $value")
    }

    fun decode(source: String): Any? = Parser(source).parse()

    private class Parser(private val source: String) {
        private var index = 0

        fun parse(): Any? {
            val result = value()
            whitespace()
            check(index == source.length) { "Trailing JSON at $index" }
            return result
        }

        private fun whitespace() {
            while (source.getOrNull(index)?.isWhitespace() == true) index++
        }

        private fun take(char: Char): Boolean {
            whitespace()
            if (source.getOrNull(index) != char) return false
            index++
            return true
        }

        private fun expect(char: Char) {
            check(take(char)) { "Expected '$char' at $index" }
        }

        private fun value(): Any? {
            whitespace()
            return when (source.getOrNull(index)) {
                '{' -> members()
                '[' -> elements()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        private fun members(): Map<String, Any?> = buildMap {
            expect('{')
            if (!take('}')) {
                do {
                    val key = string()
                    expect(':')
                    put(key, value())
                } while (take(','))
                expect('}')
            }
        }

        private fun elements(): List<Any?> = buildList {
            expect('[')
            if (!take(']')) {
                do { add(value()) } while (take(','))
                expect(']')
            }
        }

        private fun string(): String = buildString {
            expect('"')
            while (index < source.length) {
                val char = source[index++]
                if (char == '"') return@buildString
                check(char >= ' ') { "Invalid JSON string at $index" }
                append(if (char == '\\') escaped() else char)
            }
            error("Unterminated JSON string at $index")
        }

        private fun escaped(): Char = when (val char = source[index++]) {
            '"', '\\', '/' -> char
            'b' -> '\b'
            'f' -> '\u000c'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> source.substring(index, index + 4).toInt(16).toChar().also { index += 4 }
            else -> error("Invalid JSON escape at $index")
        }

        private fun literal(token: String, result: Any?): Any? {
            check(source.startsWith(token, index)) { "Expected '$token' at $index" }
            index += token.length
            return result
        }

        private fun number(): Double {
            val match = checkNotNull(NUMBER.matchAt(source, index)) { "Expected JSON value at $index" }
            index += match.value.length
            return match.value.toDouble()
        }
    }

    private val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
}
