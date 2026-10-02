package io.github.golangsupport.lang

/**
 * The type the code wants at the caret, and whether a completion item is of it. gopls orders its items by this already (checked with
 * `tools/gopls/completion.py`: an argument of type `Config` gives `cfg`, `ptr`, `newConfig`, `Config{}` first), but it does not tell
 * which of them fit: the plugin finds the type again, to show the ones that fit in bold and to leave only them in smart completion.
 * By the text and by what the server says in words: no types of our own, so what cannot be told is "not known", not "does not fit".
 */
object GoExpectedTypes {
    private val RETURN = Regex("""^return[ \t]+(.*)$""", RegexOption.DOT_MATCHES_ALL)
    private val TYPED_VARIABLE = Regex("""^var\s+\w+\s+(\S.*?)\s*=\s*\w*$""")
    private val NOT_CALLS = setOf("func", "if", "for", "switch", "select", "return", "case", "range", "map", "chan", "struct", "interface")

    /**
     * What the text tells: a value of a `return` by its place among [results] (the result types of the function around, from its PSI), the
     * value of a `var` with a type.
     */
    fun byText(text: CharSequence, offset: Int, results: List<String>?): String? {
        if (offset < 0 || offset > text.length) return null
        var lineStart = offset
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        val before = text.subSequence(lineStart, offset).toString().trimStart()
        TYPED_VARIABLE.matchEntire(before)?.let { return it.groupValues[1] }
        val returned = RETURN.matchEntire(before)?.groupValues?.get(1) ?: return null
        // inside a call of the statement the type is the one of its parameter
        val index = topLevelCommas(returned) ?: return null
        return results?.getOrNull(index)
    }

    /** The number of commas outside brackets, null when a bracket is left open: the caret is inside of something else then. */
    private fun topLevelCommas(code: String): Int? {
        var depth = 0
        var commas = 0
        var quote: Char? = null
        for ((i, c) in code.withIndex()) {
            if (quote != null) {
                if (c == quote && code.getOrNull(i - 1) != '\\') quote = null
                continue
            }
            when (c) {
                '"', '`', '\'' -> quote = c
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) commas++
            }
        }
        return commas.takeIf { depth == 0 && quote == null }
    }

    /**
     * The offset of the `(` of the call the caret is an argument of, null outside of calls: whether the server is worth asking for the
     * signature. Looks back over the statement; a declaration (`func f(`) and a control statement (`if (`) are not calls.
     */
    fun enclosingCall(text: CharSequence, offset: Int): Int? {
        var depth = 0
        var i = offset.coerceIn(0, text.length) - 1
        val limit = (offset - MAX_LOOK_BACK).coerceAtLeast(0)
        while (i >= limit) {
            when (val c = text[i]) {
                '"', '`' -> {
                    // a string literal, skipped whole; an escaped quote inside is rare enough in an argument list
                    var j = i - 1
                    while (j >= limit && text[j] != c) j--
                    i = j
                }
                ')', ']' -> depth++
                '[' -> if (depth == 0) return null else depth--
                '}' -> depth++
                '{' -> if (depth == 0) return null else depth--
                ';' -> if (depth == 0) return null
                '(' -> if (depth > 0) depth-- else return i.takeIf { isCall(text, it) }
            }
            i--
        }
        return null
    }

    private fun isCall(text: CharSequence, open: Int): Boolean {
        var end = open
        while (end > 0 && text[end - 1] == ' ') end--
        if (end > 0 && (text[end - 1] == ')' || text[end - 1] == ']')) return true
        var start = end
        while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
        if (start == end || text.subSequence(start, end).toString() in NOT_CALLS) return false
        // `func name(` and `func (r T) name(`: the parameters of a declaration
        var lineStart = start
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        return !text.subSequence(lineStart, start).trimStart().startsWith("func")
    }

    /** `flag int` -> `int`, `a ...any` -> `...any`, `string` (a parameter without a name) -> `string`. */
    fun parameterType(label: String): String {
        val text = label.trim()
        val space = text.indexOf(' ')
        if (space <= 0) return text
        val name = text.substring(0, space)
        return if (name.all { it.isLetterOrDigit() || it == '_' } && name !in TYPE_WORDS) text.substring(space + 1).trim() else text
    }

    /**
     * Whether an item of the list is a value of the type [expected]: true, false, or null when the type takes what a name cannot tell
     * (an interface, a type parameter). [detail] is what gopls says of the item: the type of a variable, the signature of a function.
     */
    fun fits(expected: String, label: String, detail: String?, function: Boolean): Boolean? {
        val want = normalize(expected).removePrefix("...")
        if (want.isEmpty() || GoIdioms.isInterfaceLike(want) || (want.length == 1 && want[0].isUpperCase())) return null
        if (label == "nil") return isNilable(want)
        // the literals gopls offers: `Config{}`, `&Config{}`
        if (label.endsWith("{}")) return same(label.removeSuffix("{}").replace("&", "*"), want)
        val type = normalize(detail ?: return false)
        if (type.isEmpty()) return false
        if (function && !want.startsWith("func")) {
            // a call gives its value, when it has one
            val results = GoIdioms.splitSignature(type.removePrefix("func")).second
            return results.size == 1 && same(results[0].type, want)
        }
        return same(type, want)
    }

    /** The same type, or one that is a `&` or a `*` away: gopls writes the operator itself when such an item is chosen. */
    private fun same(type: String, want: String): Boolean = normalize(type).let { it == want || "*$it" == want || it == "*$want" }

    private fun isNilable(type: String): Boolean =
        type == "error" || type.startsWith("*") || type.startsWith("[]") || type.startsWith("map[") || type.startsWith("chan ") || type.startsWith("<-chan") || type.startsWith("func(")

    private fun normalize(type: String): String = type.trim().replace(SPACES, " ")

    private val SPACES = Regex("""\s+""")
    private val TYPE_WORDS = setOf("func", "chan", "map", "struct", "interface")
    private const val MAX_LOOK_BACK = 2000
}
