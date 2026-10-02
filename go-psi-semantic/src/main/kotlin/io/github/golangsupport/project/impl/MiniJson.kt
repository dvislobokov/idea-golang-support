package io.github.golangsupport.project.impl

import org.jetbrains.annotations.ApiStatus

/**
 * A minimal JSON reader for `go env -json` and `go list -json` output (a stream of concatenated
 * objects). Objects become [Map]s, arrays [List]s, numbers [Double]s. Kept dependency-free so the
 * project model stays pure Kotlin.
 */
@ApiStatus.Internal
object MiniJson {

    class JsonException(message: String) : RuntimeException(message)

    /** Parses a single JSON value. */
    fun parse(text: CharSequence): Any? {
        val r = Reader(text)
        val v = r.value()
        r.ws()
        if (r.i != text.length) throw JsonException("trailing data at ${r.i}")
        return v
    }

    /** Parses a stream of whitespace-separated JSON values (as printed by `go list -json`). */
    fun parseStream(text: CharSequence): List<Any?> {
        val r = Reader(text)
        val result = mutableListOf<Any?>()
        while (true) {
            r.ws()
            if (r.i >= text.length) return result
            result += r.value()
        }
    }

    private class Reader(val s: CharSequence) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            ws()
            if (i >= s.length) throw JsonException("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw JsonException("unexpected '$c' at $i")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (s.subSequence(i, minOf(i + word.length, s.length)).toString() != word) throw JsonException("bad literal at $i")
            i += word.length
            return v
        }

        private fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.subSequence(start, i).toString().toDoubleOrNull() ?: throw JsonException("bad number at $start")
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (s.getOrNull(i) == '}') {
                i++
                return m
            }
            while (true) {
                ws()
                val k = str()
                ws()
                if (s.getOrNull(i) != ':') throw JsonException("expected ':' at $i")
                i++
                m[k] = value()
                ws()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return m
                    }
                    else -> throw JsonException("expected ',' or '}' at $i")
                }
            }
        }

        private fun arr(): List<Any?> {
            val l = mutableListOf<Any?>()
            i++
            ws()
            if (s.getOrNull(i) == ']') {
                i++
                return l
            }
            while (true) {
                l += value()
                ws()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return l
                    }
                    else -> throw JsonException("expected ',' or ']' at $i")
                }
            }
        }

        private fun str(): String {
            if (s.getOrNull(i) != '"') throw JsonException("expected string at $i")
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw JsonException("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = s[i++]
                        when (e) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                sb.append(s.subSequence(i, i + 4).toString().toInt(16).toChar())
                                i += 4
                            }
                            else -> throw JsonException("bad escape at $i")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
    }
}
