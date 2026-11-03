package org.isoron.uhabits.core.sync

/**
 * Reads and writes flat JSON objects (one per line) whose values are strings, numbers, booleans
 * or null. Enough for the sync file, and it avoids a serialization dependency in the shared core.
 */
internal object MiniJson {
    fun encode(map: Map<String, Any?>): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append(',')
            first = false
            appendString(sb, k)
            sb.append(':')
            when (v) {
                null -> sb.append("null")
                is String -> appendString(sb, v)
                is Boolean -> sb.append(if (v) "true" else "false")
                is Int, is Long -> sb.append(v.toString())
                is Double -> sb.append(v.toString())
                else -> throw IllegalArgumentException("unsupported value type: ${v::class}")
            }
        }
        return sb.append('}').toString()
    }

    fun decode(line: String): Map<String, Any?> {
        val p = Parser(line)
        val result = p.parseObject()
        p.skipSpaces()
        require(p.atEnd()) { "unexpected trailing characters" }
        return result
    }

    private fun appendString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0

        fun atEnd() = i >= s.length

        fun skipSpaces() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun parseObject(): Map<String, Any?> {
            skipSpaces()
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipSpaces()
            if (peek() == '}') {
                i++
                return result
            }
            while (true) {
                skipSpaces()
                val key = parseString()
                skipSpaces()
                expect(':')
                result[key] = parseValue()
                skipSpaces()
                when (val c = next()) {
                    ',' -> continue
                    '}' -> return result
                    else -> throw IllegalArgumentException("expected , or } but found $c")
                }
            }
        }

        fun parseValue(): Any? {
            skipSpaces()
            return when (val c = peek()) {
                '"' -> parseString()
                'n' -> literal("null", null)
                't' -> literal("true", true)
                'f' -> literal("false", false)
                else -> if (c == '-' || c.isDigit()) parseNumber() else throw IllegalArgumentException("unexpected $c")
            }
        }

        fun literal(word: String, value: Any?): Any? {
            require(s.startsWith(word, i)) { "bad literal at $i" }
            i += word.length
            return value
        }

        fun parseNumber(): Any {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val text = s.substring(start, i)
            return if (text.any { it in ".eE" }) text.toDouble() else text.toLong()
        }

        fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "unterminated string" }
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = next()) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                require(i + 4 <= s.length) { "bad unicode escape" }
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun peek(): Char {
            require(i < s.length) { "unexpected end" }
            return s[i]
        }

        fun next(): Char {
            val c = peek()
            i++
            return c
        }

        fun expect(c: Char) {
            val found = next()
            require(found == c) { "expected $c but found $found" }
        }
    }
}
