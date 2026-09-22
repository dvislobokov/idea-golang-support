package io.github.golangsupport.lang

/**
 * The next lines of code where Go leaves no choice about them: the check of an error that was just assigned, the `defer` that belongs to
 * what was just opened or locked. Shown as an inline suggestion (grey text, Tab to accept). By the text alone: the statement above the
 * caret and the signature of the function around it; no types, so only the shapes that mean one thing are recognized.
 */
object GoIdioms {
    /**
     * What to insert at [offset], or null. The caret has to be on a line of its own; what is typed there already must be the beginning
     * of the suggestion (then the rest of it is returned). [unit] is one level of indentation.
     */
    fun suggest(text: CharSequence, offset: Int, unit: String = "\t"): String? {
        if (offset < 0 || offset > text.length) return null
        var lineStart = offset
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        var lineEnd = offset
        while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
        if (text.subSequence(offset, lineEnd).isNotBlank()) return null
        val before = text.subSequence(lineStart, offset).toString()
        val typed = before.trimStart()
        val previous = linesBefore(text, lineStart)
        val above = previous.firstOrNull() ?: return null
        val indent = before.takeWhile { it == ' ' || it == '\t' }.ifEmpty { above.indent }

        val suggestion = errorCheck(text, offset, above, indent, unit) ?: deferAfter(above) ?: deferAfterErrorCheck(previous) ?: return null
        // not twice: the line below may be what would be suggested
        val below = text.subSequence(lineEnd, text.length).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        if (below != null && below == suggestion.lineSequence().first().trim()) return null
        if (!suggestion.startsWith(typed) || suggestion == typed) return null
        // a blank line may have no indent in the text: the editor keeps the caret of such a line in virtual space
        return (if (before.isEmpty()) indent else "") + suggestion.substring(typed.length)
    }

    private class Line(val indent: String, val code: String)

    /** The non-blank lines above, nearest first, comments at the end of a line cut off; enough of them to see an `if` block and what is above it. */
    private fun linesBefore(text: CharSequence, lineStart: Int): List<Line> {
        val result = ArrayList<Line>()
        var end = lineStart - 1
        while (end > 0 && result.size < MAX_LINES_BACK) {
            var start = end
            while (start > 0 && text[start - 1] != '\n') start--
            val raw = text.subSequence(start, end).toString().trimEnd('\r')
            val code = raw.trim().let { if (it.startsWith("//")) "" else it.substringBefore(" //").trim() }
            if (code.isNotEmpty()) result += Line(raw.takeWhile { it == ' ' || it == '\t' }, code)
            end = start - 1
        }
        return result
    }

    // --- if err != nil ---

    private val ERROR_ASSIGNMENT = Regex("""^(?:[\w.\[\]*]+\s*,\s*)*(\w*[eE]rr\w*)\s*:?=[^=].*[)\w\]}"`]$""")

    private fun errorCheck(text: CharSequence, offset: Int, above: Line, indent: String, unit: String): String? {
        if (above.code.startsWith("if ") || above.code.startsWith("for ") || above.code.startsWith("switch ") || above.code.startsWith("}")) return null
        val error = ERROR_ASSIGNMENT.matchEntire(above.code)?.groupValues?.get(1) ?: return null
        return "if $error != nil {\n$indent$unit${returnStatement(text, offset, error)}\n$indent}"
    }

    /** What leaves the function around [offset] with the error: `return nil, err`, `return total, err`, `t.Fatal(err)`, `log.Fatal(err)`. */
    fun returnStatement(text: CharSequence, offset: Int, error: String): String {
        val structure = GoDeclarations.scan(text)
        val function = structure.declarations.lastOrNull { it.body != null && offset > it.body.startOffset && offset <= it.body.endOffset && it.signature != null } ?: return "return $error"
        val (parameters, results) = splitSignature(function.signature.orEmpty())
        if (results.isEmpty()) {
            val testing = parameters.firstOrNull { it.type.contains("testing.") }
            return when {
                testing?.name != null -> "${testing.name}.Fatal($error)"
                function.name == "main" && structure.isMainPackage -> "log.Fatal($error)"
                else -> "return"
            }
        }
        val named = results.last().name != null
        val values = results.mapIndexed { i, result ->
            val isError = result.type == "error" && i == results.indexOfLast { it.type == "error" }
            when {
                isError -> error
                named -> result.name ?: result.type
                else -> zeroValue(result.type)
            }
        }
        return "return " + values.joinToString(", ")
    }

    class Parameter(val name: String?, val type: String)

    /** `(a, b int, f func() error) (total int, err error)` -> the parameters and the results; a name without a type takes the type that follows. */
    fun splitSignature(signature: String): Pair<List<Parameter>, List<Parameter>> {
        var text = signature.trim()
        if (text.startsWith("[")) text = text.substring(closing(text, 0) + 1).trim()
        if (!text.startsWith("(")) return emptyList<Parameter>() to emptyList()
        val close = closing(text, 0)
        val parameters = parameterList(text.substring(1, close))
        val rest = text.substring(close + 1).trim()
        val results = if (rest.startsWith("(") && closing(rest, 0) == rest.length - 1) parameterList(rest.substring(1, rest.length - 1))
        else if (rest.isEmpty()) emptyList() else listOf(Parameter(null, rest))
        return parameters to results
    }

    private fun parameterList(inner: String): List<Parameter> {
        val parts = splitTopLevel(inner, ',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        fun named(part: String): Pair<String, String>? {
            val space = part.indexOf(' ')
            if (space <= 0) return null
            val name = part.substring(0, space)
            return if (name.all { it.isLetterOrDigit() || it == '_' } && name !in TYPE_WORDS) name to part.substring(space + 1).trim() else null
        }
        // Go has it all or nothing: either every entry has a name, or none has
        if (named(parts.last()) == null) return parts.map { Parameter(null, it) }
        val result = ArrayList<Parameter>()
        var type = ""
        for (part in parts.asReversed()) {
            val pair = named(part)
            if (pair != null) type = pair.second
            result += Parameter(pair?.first ?: part, type)
        }
        return result.asReversed()
    }

    fun zeroValue(type: String): String {
        val t = type.trim()
        return when {
            t == "string" -> "\"\""
            t == "bool" -> "false"
            t in NUMERIC -> "0"
            t == "error" || t == "any" || t.startsWith("*") || t.startsWith("[]") || t.startsWith("map[") || t.startsWith("chan ") || t.startsWith("<-chan") ||
                t.startsWith("func(") || t.startsWith("interface") || t in KNOWN_INTERFACES || INTERFACE_LIKE.containsMatchIn(t.substringAfterLast('.')) -> "nil"
            // a type parameter: the only zero value that compiles for any T
            t.length == 1 && t[0].isUpperCase() -> "*new($t)"
            else -> "$t{}"
        }
    }

    // --- defer ---

    private val CONTEXT = Regex("""^\w+,\s*(\w+)\s*:?=\s*context\.With(?:Cancel|CancelCause|Timeout|TimeoutCause|Deadline|DeadlineCause)\(.*\)$""")
    private val LOCK = Regex("""^([\w.]+)\.(R?)Lock\(\)$""")
    private val STOPPABLE = Regex("""^(\w+)\s*:?=\s*time\.New(?:Ticker|Timer)\(.*\)$""")

    private fun deferAfter(above: Line): String? {
        CONTEXT.matchEntire(above.code)?.let { match -> return match.groupValues[1].takeIf { it != "_" }?.let { "defer $it()" } }
        LOCK.matchEntire(above.code)?.let { return "defer ${it.groupValues[1]}.${it.groupValues[2]}Unlock()" }
        STOPPABLE.matchEntire(above.code)?.let { return "defer ${it.groupValues[1]}.Stop()" }
        return null
    }

    private val OPENED = Regex("""^(\w+),\s*\w*[eE]rr\w*\s*:?=\s*([\w.]+)\(.*\)$""")

    /** What is to be closed once the error of opening it has been dealt with; by the name of the function that opens. */
    private val CLOSERS: List<Pair<Regex, String>> = listOf(
        Regex("""^os\.(Open|Create|OpenFile|CreateTemp)$""") to "Close()",
        Regex("""^(net\.(Dial|DialTimeout|Listen)|sql\.Open|grpc\.(Dial|NewClient)|tls\.Dial|zip\.OpenReader|gzip\.NewReader)$""") to "Close()",
        Regex("""^(http\.(Get|Post|Head|PostForm)|[\w.]+\.(Do|Get|Post))$""") to "Body.Close()",
        Regex("""^[\w.]+\.(Query|QueryContext|Prepare|PrepareContext)$""") to "Close()",
        Regex("""^[\w.]+\.(Begin|BeginTx)$""") to "Rollback()",
    )

    /** The caret is right after `if err != nil { ... }`, and the statement above that `if` has opened something. */
    private fun deferAfterErrorCheck(previous: List<Line>): String? {
        if (previous.firstOrNull()?.code != "}") return null
        val indent = previous.first().indent
        val check = previous.indexOfFirst { it.indent == indent && it.code.startsWith("if ") && it.code.endsWith("!= nil {") }
        if (check < 0 || check + 1 >= previous.size) return null
        // everything between the `if` and its brace is its body
        if (previous.subList(1, check).any { it.indent.length <= indent.length }) return null
        val match = OPENED.matchEntire(previous[check + 1].code) ?: return null
        val (name, opener) = match.destructured
        if (name == "_") return null
        return CLOSERS.firstOrNull { it.first.matches(opener) }?.let { "defer $name.${it.second}" }
    }

    // --- text ---

    private fun closing(text: String, open: Int): Int {
        var depth = 0
        for (i in open until text.length) when (text[i]) {
            '(', '[', '{' -> depth++
            ')', ']', '}' -> if (--depth == 0) return i
        }
        return text.length - 1
    }

    private fun splitTopLevel(text: String, separator: Char): List<String> {
        val result = ArrayList<String>()
        var depth = 0
        var start = 0
        for (i in text.indices) when (text[i]) {
            '(', '[', '{' -> depth++
            ')', ']', '}' -> depth--
            separator -> if (depth == 0) {
                result += text.substring(start, i)
                start = i + 1
            }
        }
        result += text.substring(start)
        return result
    }

    private const val MAX_LINES_BACK = 12
    private val TYPE_WORDS = setOf("func", "chan", "map", "struct", "interface")
    private val NUMERIC = setOf(
        "int", "int8", "int16", "int32", "int64", "uint", "uint8", "uint16", "uint32", "uint64", "uintptr", "byte", "rune", "float32", "float64", "complex64", "complex128",
        "time.Duration",
    )
    private val KNOWN_INTERFACES = setOf("context.Context", "net.Conn", "net.Listener", "net.Addr", "fmt.Stringer", "sort.Interface", "http.RoundTripper", "driver.Value")

    /** `io.Reader`, `http.Handler`, `ResponseWriter`: names interfaces are given. `Order`, `Buffer`, `Server` are not among them. */
    private val INTERFACE_LIKE = Regex("""(Reader|Writer|Closer|Seeker|Handler|Marshaler|Unmarshaler|Scanner|Formatter|Interface)$""")
}
