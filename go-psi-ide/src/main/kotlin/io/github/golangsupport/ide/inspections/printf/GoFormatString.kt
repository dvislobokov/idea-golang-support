package io.github.golangsupport.ide.inspections.printf

/**
 * The value of a Go string literal with, for every decoded char, the range of source text it came from (offsets relative to the
 * literal's text, quotes included), so a problem inside the value points at the exact source characters even after escapes.
 */
class GoStringValue private constructor(val value: String, private val starts: IntArray, private val ends: IntArray, private val sourceLength: Int?) {

    /** The source range `[start, end)` of the decoded chars `[from, to)`; null when the value has no source (a constant expression). */
    fun sourceRange(from: Int, to: Int): IntRange? {
        if (starts.isEmpty() || from < 0 || to > value.length || from >= to) return null
        return starts[from] until ends[to - 1]
    }

    /** Whether the decoded char at [index] is written as itself in the source (not an escape), so it can be replaced in place. */
    fun isPlain(index: Int): Boolean = starts.isNotEmpty() && ends[index] - starts[index] == 1

    /** The source offset of the closing quote (where text appended to the value goes); null without source. */
    val closingQuote: Int? get() = sourceLength?.minus(1)

    companion object {
        /** A value without source text (a constant from an expression): ranges are unavailable. */
        fun ofConstant(value: String): GoStringValue = GoStringValue(value, IntArray(0), IntArray(0), null)

        /**
         * Decodes a literal's source text: `` `raw` `` (carriage returns are dropped, as the spec says) or `"interpreted"` with the escapes
         * of the spec. Null for malformed text (no quotes). Byte escapes (`\xhh`, octal) decode to the char with that code: verbs and
         * flags are ASCII, so the parse is the same as over bytes.
         */
        fun decode(source: String): GoStringValue? {
            if (source.length < 2) return null
            val quote = source[0]
            if (quote != '"' && quote != '`') return null
            val close = if (source.last() == quote) source.length - 1 else source.length
            val out = StringBuilder()
            val starts = ArrayList<Int>()
            val ends = ArrayList<Int>()
            fun emit(c: Char, start: Int, end: Int) {
                out.append(c); starts += start; ends += end
            }
            var i = 1
            if (quote == '`') {
                while (i < close) {
                    if (source[i] != '\r') emit(source[i], i, i + 1)
                    i++
                }
            } else {
                while (i < close) {
                    val c = source[i]
                    if (c != '\\' || i + 1 >= close) {
                        emit(c, i, i + 1); i++; continue
                    }
                    val start = i
                    when (val e = source[i + 1]) {
                        'a' -> { emit('\u0007', start, i + 2); i += 2 }
                        'b' -> { emit('\b', start, i + 2); i += 2 }
                        'f' -> { emit('\u000c', start, i + 2); i += 2 }
                        'n' -> { emit('\n', start, i + 2); i += 2 }
                        'r' -> { emit('\r', start, i + 2); i += 2 }
                        't' -> { emit('\t', start, i + 2); i += 2 }
                        'v' -> { emit('\u000b', start, i + 2); i += 2 }
                        '\\', '\'', '"' -> { emit(e, start, i + 2); i += 2 }
                        'x', 'u', 'U' -> {
                            val n = when (e) { 'x' -> 2; 'u' -> 4; else -> 8 }
                            val digits = source.substring(i + 2, minOf(close, i + 2 + n))
                            val code = digits.takeIf { it.length == n }?.toIntOrNull(16)
                            if (code == null) { emit(c, i, i + 1); i++; continue }
                            val end = i + 2 + n
                            if (Character.isValidCodePoint(code)) Character.toChars(code).forEach { emit(it, start, end) } else emit('�', start, end)
                            i = end
                        }
                        in '0'..'7' -> {
                            val digits = source.substring(i + 1, minOf(close, i + 4))
                            val code = digits.takeIf { it.length == 3 && it.all { d -> d in '0'..'7' } }?.toInt(8)
                            if (code == null) { emit(c, i, i + 1); i++; continue }
                            emit(code.toChar(), start, i + 4)
                            i += 4
                        }
                        else -> { emit(c, i, i + 1); i++ }
                    }
                }
            }
            return GoStringValue(out.toString(), starts.toIntArray(), ends.toIntArray(), source.length)
        }
    }
}

/**
 * A parsed `fmt` format string, following `golang.org/x/tools/go/analysis/passes/printf` (`parsePrintfVerb`): flags `#0+- `,
 * an argument index `[n]`, a width (digits, `*` or `[n]*`), a precision `.` (digits, `*` or `[n]*`), an index before the verb,
 * then the verb. Argument numbers are 0-based and relative to the first value argument after the format; `*` consumes an
 * argument, and an index moves the position (`%[2]d %d` reads arguments 1 and 2). Parsing stops at the first malformed
 * directive ([error]), as vet stops checking the call there.
 */
class GoFormatString private constructor(val text: String, val directives: List<Directive>, val error: Error?, val nextArg: Int) {

    /**
     * One `%...` directive over `text[start, end)`. [argNums] are the arguments it reads: those of `*` first, then the verb's
     * (none for `%%`). [flags] include `.` when a precision is present (vet treats precision as a flag).
     */
    class Directive(
        val start: Int,
        val end: Int,
        val text: String,
        val flags: String,
        val verb: Char,
        val verbOffset: Int,
        val argNums: List<Int>,
        val indexed: Boolean,
    ) {
        /** The argument of the verb, or null for `%%`. */
        val verbArg: Int? get() = if (verb == '%') null else argNums.lastOrNull()

        /** The arguments consumed by `*` width and precision. */
        val starArgs: List<Int> get() = if (verb == '%') argNums else argNums.dropLast(1)

        override fun toString(): String = text
    }

    /** A malformed directive: what is wrong ([Kind]), its range in [text] and the directive text so far. */
    class Error(val kind: Kind, val start: Int, val end: Int, val directive: String, val index: String? = null) {
        enum class Kind { MISSING_VERB, MISSING_BRACKET, BAD_INDEX }
    }

    /** The highest argument read plus one (0 without arguments). */
    val argsNeeded: Int get() = directives.flatMap { it.argNums }.maxOrNull()?.plus(1) ?: 0

    val anyIndex: Boolean get() = directives.any { it.indexed }

    companion object {
        fun parse(text: String): GoFormatString {
            val directives = ArrayList<Directive>()
            var argNum = 0
            var i = 0
            while (i < text.length) {
                if (text[i] != '%') { i++; continue }
                val p = DirectiveParser(text, i, argNum)
                val result = p.parse()
                if (result is Error) return GoFormatString(text, directives, result, p.argNum)
                directives += result as Directive
                argNum = p.argNum
                i = result.end
            }
            return GoFormatString(text, directives, null, argNum)
        }

        private const val FLAGS = "#0+- "
    }

    private class DirectiveParser(val text: String, val start: Int, var argNum: Int) {
        var pos = start + 1
        val flags = StringBuilder()
        val argNums = ArrayList<Int>()
        var indexed = false
        var indexPending = false

        fun parse(): Any {
            while (pos < text.length && text[pos] in FLAGS) flags.append(text[pos++])
            parseIndex()?.let { return it }
            parseNum()
            if (pos < text.length && text[pos] == '.') {
                flags.append('.')
                pos++
                parseIndex()?.let { return it }
                parseNum()
            }
            if (!indexPending) parseIndex()?.let { return it }
            if (pos >= text.length) return Error(Error.Kind.MISSING_VERB, start, pos, text.substring(start, pos))
            val verbOffset = pos
            val cp = text.codePointAt(pos)
            pos += Character.charCount(cp)
            val verb = if (Character.charCount(cp) == 1) cp.toChar() else '�'
            if (verb != '%') {
                argNums += argNum
                argNum++
            }
            return Directive(start, pos, text.substring(start, pos), flags.toString(), verb, verbOffset, argNums, indexed)
        }

        /** `[n]`: moves the argument position; null when absent or well-formed. */
        private fun parseIndex(): Error? {
            if (pos >= text.length || text[pos] != '[') return null
            pos++
            val numStart = pos
            while (pos < text.length && text[pos].isDigit()) pos++
            var ok = true
            if (pos >= text.length || pos == numStart || text[pos] != ']') {
                ok = false
                val close = text.indexOf(']', numStart)
                if (close < 0) return Error(Error.Kind.MISSING_BRACKET, start, text.length, text.substring(start))
                pos = close
            }
            val digits = text.substring(numStart, pos)
            val index = digits.toIntOrNull()
            pos++
            if (!ok || index == null || index <= 0) return Error(Error.Kind.BAD_INDEX, start, pos, text.substring(start, pos), digits)
            argNum = index - 1
            indexed = true
            indexPending = true
            return null
        }

        /** Width or precision: digits, or `*`, which reads an argument. */
        private fun parseNum() {
            if (pos < text.length && text[pos] == '*') {
                indexPending = false
                pos++
                argNums += argNum
                argNum++
            } else {
                while (pos < text.length && text[pos].isDigit()) pos++
            }
        }
    }
}
