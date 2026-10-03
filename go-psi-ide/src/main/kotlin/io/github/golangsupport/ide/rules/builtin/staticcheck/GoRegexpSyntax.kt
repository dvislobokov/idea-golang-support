package io.github.golangsupport.ide.rules.builtin.staticcheck

/**
 * The syntax errors of Go's `regexp.Compile` (package `regexp/syntax`, Perl flags), re-implemented from its behaviour: the same
 * error codes and the same offending fragment, so a message reads like the compiler's
 * (`error parsing regexp: missing closing ]: `[``). Size and nesting limits (`expression too large`, nested repeat counts over
 * 1000) are not checked: missing them only means no report.
 */
internal object GoRegexpSyntax {

    /** Go's error text for [expr], or null when it compiles. */
    fun error(expr: String): String? = try {
        Parser(expr).parse()
        null
    } catch (e: RegexpError) {
        "error parsing regexp: ${e.code}: `${e.expr}`"
    }

    const val INVALID_CHAR_RANGE = "invalid character class range"
    const val INVALID_ESCAPE = "invalid escape sequence"
    const val INVALID_NAMED_CAPTURE = "invalid named capture"
    const val INVALID_PERL_OP = "invalid or unsupported Perl syntax"
    const val INVALID_REPEAT_OP = "invalid nested repetition operator"
    const val INVALID_REPEAT_SIZE = "invalid repeat count"
    const val MISSING_BRACKET = "missing closing ]"
    const val MISSING_PAREN = "missing closing )"
    const val MISSING_REPEAT_ARGUMENT = "missing argument to repetition operator"
    const val TRAILING_BACKSLASH = "trailing backslash at end of expression"
    const val UNEXPECTED_PAREN = "unexpected )"

    private class RegexpError(val code: String, val expr: String) : Exception(code, null, false, false)

    private val POSIX = setOf("alnum", "alpha", "ascii", "blank", "cntrl", "digit", "graph", "lower", "print", "punct", "space", "upper", "word", "xdigit")
        .flatMap { listOf("[:$it:]", "[:^$it:]") }.toSet()

    /** General categories with their long aliases, and the extra names Go 1.25 accepts; compared normalized ([normalize]). */
    private val UNICODE_NAMES: Set<String> = (listOf(
        "Any", "ASCII", "Assigned",
        "C", "Other", "Cc", "Control", "cntrl", "Cf", "Format", "Cn", "Unassigned", "Co", "Private_Use", "Cs", "Surrogate",
        "L", "Letter", "LC", "Cased_Letter", "Ll", "Lowercase_Letter", "Lm", "Modifier_Letter", "Lo", "Other_Letter",
        "Lt", "Titlecase_Letter", "Lu", "Uppercase_Letter",
        "M", "Mark", "Combining_Mark", "Mc", "Spacing_Mark", "Me", "Enclosing_Mark", "Mn", "Nonspacing_Mark",
        "N", "Number", "Nd", "Decimal_Number", "digit", "Nl", "Letter_Number", "No", "Other_Number",
        "P", "Punctuation", "punct", "Pc", "Connector_Punctuation", "Pd", "Dash_Punctuation", "Pe", "Close_Punctuation",
        "Pf", "Final_Punctuation", "Pi", "Initial_Punctuation", "Po", "Other_Punctuation", "Ps", "Open_Punctuation",
        "S", "Symbol", "Sc", "Currency_Symbol", "Sk", "Modifier_Symbol", "Sm", "Math_Symbol", "So", "Other_Symbol",
        "Z", "Separator", "Zl", "Line_Separator", "Zp", "Paragraph_Separator", "Zs", "Space_Separator",
    ) + Character.UnicodeScript.entries.map { it.name }).map(::normalize).toSet()

    private fun normalize(name: String): String = name.filter { it != '_' && it != ' ' && it != '-' }.lowercase()

    /** Unicode class names: exact for the usual spellings, lenient (TR18 loose matching, scripts by the JDK) for the rest. */
    private fun isUnicodeName(name: String): Boolean {
        if (normalize(name) in UNICODE_NAMES) return true
        return runCatching { Character.UnicodeScript.forName(name) }.isSuccess
    }

    private fun isAlnum(c: Int): Boolean = c in '0'.code..'9'.code || c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code

    private fun unhex(c: Int): Int = when (c) {
        in '0'.code..'9'.code -> c - '0'.code
        in 'a'.code..'f'.code -> c - 'a'.code + 10
        in 'A'.code..'F'.code -> c - 'A'.code + 10
        else -> -1
    }

    private class Parser(val s: String) {
        private var pos = 0
        private var depth = 0

        /** Whether the top of Go's parse stack is an operand (not empty, not `(` or `|`): a repetition operator needs one. */
        private var hasOperand = false

        /** The value of the last class character or escape. */
        private var rune = 0

        fun parse() {
            var lastRepeat = -1
            while (pos < s.length) {
                var repeat = -1
                when (s[pos]) {
                    '(' -> if (pos + 1 < s.length && s[pos + 1] == '?') parsePerlFlags() else {
                        depth++
                        hasOperand = false
                        pos++
                    }
                    '|' -> {
                        hasOperand = false
                        pos++
                    }
                    ')' -> {
                        if (depth == 0) throw RegexpError(UNEXPECTED_PAREN, s)
                        depth--
                        hasOperand = true
                        pos++
                    }
                    '^', '$', '.' -> {
                        hasOperand = true
                        pos++
                    }
                    '[' -> {
                        parseClass()
                        hasOperand = true
                    }
                    '*', '+', '?' -> {
                        val before = pos
                        pos++
                        repeat(before, lastRepeat)
                        repeat = before
                    }
                    '{' -> {
                        val before = pos
                        val r = parseRepeat(pos)
                        if (r == null) {
                            hasOperand = true
                            pos++
                        } else {
                            val (min, max, after) = r
                            if (min < 0 || min > 1000 || max > 1000 || max >= 0 && min > max) throw RegexpError(INVALID_REPEAT_SIZE, s.substring(before, after))
                            pos = after
                            repeat(before, lastRepeat)
                            repeat = before
                        }
                    }
                    '\\' -> parseBackslash()
                    else -> {
                        pos += Character.charCount(s.codePointAt(pos))
                        hasOperand = true
                    }
                }
                lastRepeat = repeat
            }
            if (depth != 0) throw RegexpError(MISSING_PAREN, s)
        }

        /** After a repetition operator starting at [before]; [pos] is past it. */
        private fun repeat(before: Int, lastRepeat: Int) {
            if (pos < s.length && s[pos] == '?') pos++
            if (lastRepeat >= 0) throw RegexpError(INVALID_REPEAT_OP, s.substring(lastRepeat, pos))
            if (!hasOperand) throw RegexpError(MISSING_REPEAT_ARGUMENT, s.substring(before, pos))
            hasOperand = true
        }

        /** `{min}`, `{min,}`, `{min,max}` at [start]: (min, max, end) with -1 for an overflow / no max; null when it is a literal `{`. */
        private fun parseRepeat(start: Int): Triple<Int, Int, Int>? {
            var t = start + 1
            val (min, afterMin) = parseInt(t) ?: return null
            t = afterMin
            if (t >= s.length) return null
            var max: Int
            var lo = min
            if (s[t] != ',') {
                max = min
            } else {
                t++
                if (t >= s.length) return null
                if (s[t] == '}') {
                    max = -1
                } else {
                    val (m, afterMax) = parseInt(t) ?: return null
                    max = m
                    t = afterMax
                    if (max < 0) lo = -1
                }
            }
            if (t >= s.length || s[t] != '}') return null
            return Triple(lo, max, t + 1)
        }

        /** Decimal digits without a leading zero: (value or -1 on overflow, end). */
        private fun parseInt(start: Int): Pair<Int, Int>? {
            if (start >= s.length || s[start] !in '0'..'9') return null
            if (start + 1 < s.length && s[start] == '0' && s[start + 1] in '0'..'9') return null
            var t = start
            while (t < s.length && s[t] in '0'..'9') t++
            var n = 0
            for (i in start until t) {
                if (n >= 100_000_000) {
                    n = -1
                    break
                }
                n = n * 10 + (s[i] - '0')
            }
            return n to t
        }

        private fun parsePerlFlags() {
            val start = pos
            val rest = s.length - start
            if (rest > 4 && s[start + 2] == 'P' && s[start + 3] == '<' || rest > 3 && s[start + 2] == '<') {
                val begin = if (s[start + 2] == '<') start + 3 else start + 4
                val end = s.indexOf('>', start)
                if (end < 0) throw RegexpError(INVALID_NAMED_CAPTURE, s.substring(start))
                val name = s.substring(begin, end)
                if (name.isEmpty() || name.any { it != '_' && !isAlnum(it.code) }) throw RegexpError(INVALID_NAMED_CAPTURE, s.substring(start, end + 1))
                depth++
                hasOperand = false
                pos = end + 1
                return
            }
            var t = start + 2
            var negated = false
            var sawFlag = false
            loop@ while (t < s.length) {
                val c = s.codePointAt(t)
                t += Character.charCount(c)
                when (c) {
                    'i'.code, 'm'.code, 's'.code, 'U'.code -> sawFlag = true
                    '-'.code -> {
                        if (negated) break@loop
                        negated = true
                        sawFlag = false
                    }
                    ':'.code, ')'.code -> {
                        if (negated && !sawFlag) break@loop
                        if (c == ':'.code) {
                            depth++
                            hasOperand = false
                        }
                        pos = t
                        return
                    }
                    else -> break@loop
                }
            }
            throw RegexpError(INVALID_PERL_OP, s.substring(start, t))
        }

        private fun parseBackslash() {
            if (pos + 1 < s.length) {
                when (s[pos + 1]) {
                    'A', 'b', 'B', 'z' -> {
                        pos += 2
                        hasOperand = true
                        return
                    }
                    'C' -> throw RegexpError(INVALID_ESCAPE, s.substring(pos, pos + 2))
                    'Q' -> {
                        val end = s.indexOf("\\E", pos + 2)
                        val literal = if (end < 0) s.length - (pos + 2) else end - (pos + 2)
                        pos = if (end < 0) s.length else end + 2
                        if (literal > 0) hasOperand = true
                        return
                    }
                    'p', 'P' -> {
                        pos = parseUnicodeClass(pos)
                        hasOperand = true
                        return
                    }
                    'd', 'D', 's', 'S', 'w', 'W' -> {
                        pos += 2
                        hasOperand = true
                        return
                    }
                }
            }
            pos = parseEscape(pos)
            hasOperand = true
        }

        /** `\p{Name}`, `\pL`, `\P{^Name}` at [start]; returns the end. */
        private fun parseUnicodeClass(start: Int): Int {
            var t = start + 2
            val seq: String
            var name: String
            if (t < s.length && s[t] == '{') {
                val end = s.indexOf('}', start)
                if (end < 0) throw RegexpError(INVALID_CHAR_RANGE, s.substring(start))
                seq = s.substring(start, end + 1)
                name = s.substring(start + 3, end)
                t = end + 1
            } else {
                if (t < s.length) t += Character.charCount(s.codePointAt(t))
                seq = s.substring(start, t)
                name = seq.substring(2)
            }
            if (name.startsWith("^")) name = name.substring(1)
            if (!isUnicodeName(name)) throw RegexpError(INVALID_CHAR_RANGE, seq)
            return t
        }

        /** An escape at [start] (`\n`, `\x41`, `\x{10FFFF}`, `\101`, `\.`); sets [rune], returns the end. */
        private fun parseEscape(start: Int): Int {
            var t = start + 1
            if (t >= s.length) throw RegexpError(TRAILING_BACKSLASH, "")
            val c = s.codePointAt(t)
            t += Character.charCount(c)
            fun invalid(): Nothing = throw RegexpError(INVALID_ESCAPE, s.substring(start, t))
            when {
                c in '1'.code..'7'.code || c == '0'.code -> {
                    // a single non-zero digit would be a backreference: not supported
                    if (c != '0'.code && (t >= s.length || s[t] !in '0'..'7')) invalid()
                    var r = c - '0'.code
                    var i = 1
                    while (i < 3 && t < s.length && s[t] in '0'..'7') {
                        r = r * 8 + (s[t] - '0')
                        t++
                        i++
                    }
                    rune = r
                    return t
                }
                c == 'x'.code -> {
                    if (t >= s.length) invalid()
                    val c2 = s.codePointAt(t)
                    t += Character.charCount(c2)
                    if (c2 == '{'.code) {
                        var digits = 0
                        var r = 0
                        while (true) {
                            if (t >= s.length) invalid()
                            val d = s.codePointAt(t)
                            t += Character.charCount(d)
                            if (d == '}'.code) break
                            val v = unhex(d)
                            if (v < 0) invalid()
                            r = r * 16 + v
                            if (r > Character.MAX_CODE_POINT) invalid()
                            digits++
                        }
                        if (digits == 0) invalid()
                        rune = r
                        return t
                    }
                    val x = unhex(c2)
                    var y = -1
                    if (t < s.length) {
                        val c3 = s.codePointAt(t)
                        t += Character.charCount(c3)
                        y = unhex(c3)
                    }
                    if (x < 0 || y < 0) invalid()
                    rune = x * 16 + y
                    return t
                }
                c == 'a'.code -> rune = 7
                c == 'f'.code -> rune = 12
                c == 'n'.code -> rune = 10
                c == 'r'.code -> rune = 13
                c == 't'.code -> rune = 9
                c == 'v'.code -> rune = 11
                c < 0x80 && !isAlnum(c) -> rune = c
                else -> invalid()
            }
            return t
        }

        private fun parseClass() {
            val whole = s.substring(pos)
            var t = pos + 1
            if (t < s.length && s[t] == '^') t++
            var first = true
            while (t >= s.length || s[t] != ']' || first) {
                first = false
                if (s.length - t > 2 && s[t] == '[' && s[t + 1] == ':') {
                    val close = s.indexOf(":]", t + 2)
                    if (close >= 0) {
                        val name = s.substring(t, close + 2)
                        if (name !in POSIX) throw RegexpError(INVALID_CHAR_RANGE, name)
                        t = close + 2
                        continue
                    }
                }
                if (t + 1 < s.length && s[t] == '\\' && (s[t + 1] == 'p' || s[t + 1] == 'P')) {
                    t = parseUnicodeClass(t)
                    continue
                }
                if (t + 1 < s.length && s[t] == '\\' && s[t + 1] in "dDsSwW") {
                    t += 2
                    continue
                }
                val range = t
                t = parseClassChar(t, whole)
                val lo = rune
                if (s.length - t >= 2 && s[t] == '-' && s[t + 1] != ']') {
                    t = parseClassChar(t + 1, whole)
                    if (rune < lo) throw RegexpError(INVALID_CHAR_RANGE, s.substring(range, t))
                }
            }
            pos = t + 1
        }

        private fun parseClassChar(t: Int, wholeClass: String): Int {
            if (t >= s.length) throw RegexpError(MISSING_BRACKET, wholeClass)
            if (s[t] == '\\') return parseEscape(t)
            rune = s.codePointAt(t)
            return t + Character.charCount(rune)
        }
    }
}
