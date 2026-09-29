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

        val below = text.subSequence(lineEnd, text.length).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        val suggestion = errorCheck(text, offset, above, indent, unit) ?: returnInErrorCheck(text, offset, previous, below) ?: okCheck(text, offset, above, indent, unit)
            ?: deferAfter(above) ?: deferInGoroutine(previous) ?: deferAfterErrorCheck(previous) ?: errorAfterLoop(text, offset, previous, indent, unit)
            ?: scannerLoop(above, indent, unit) ?: rowsLoop(previous, indent, unit) ?: return null
        // not twice: the line below may be what would be suggested
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
        return "if $error != nil {\n$indent$unit${errorReturn(text, offset, error, above.code)}\n$indent}"
    }

    /**
     * The body of an `if err != nil {` that was typed by hand and is still empty: the statement that leaves the function. Not above
     * something that is in the block already: a line added to a body is not the whole of it.
     */
    private fun returnInErrorCheck(text: CharSequence, offset: Int, previous: List<Line>, below: String?): String? {
        val check = previous.firstOrNull() ?: return null
        val error = ERROR_CHECK.matchEntire(check.code)?.groupValues?.get(1) ?: return null
        if (below != null && !below.startsWith("}")) return null
        // what has failed is in the `if` itself (`if err := f(); err != nil {`), or in the statement above it
        val failed = if (';' in check.code) check.code.substringAfter("if ").substringBefore(';') else previous.getOrNull(1)?.code?.takeIf { ERROR_ASSIGNMENT.matches(it) }
        return errorReturn(text, offset, error, failed)
    }

    // --- the way the file returns its errors ---

    private val WRAPPED = Regex("""fmt\.Errorf\("[^"\n]*%w""")
    private val RETURNED_AS_IS = Regex("""^[ \t]*return[ \t]+(?:[^\n]*,[ \t]*)?\w*[eE]rr\w*[ \t]*$""", RegexOption.MULTILINE)
    private val CALLED = Regex("""(?:=|^)\s*(?:<-\s*)?([\w.]+)\(""")
    private val HUMPS = Regex("""(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])""")

    /**
     * Whether the errors of the file go up wrapped, `fmt.Errorf("open config: %w", err)`, more often than as they are. What is
     * suggested is written the way the file is: a team that wraps its errors does not want `return nil, err`, and the other way round.
     */
    fun wrapsErrors(text: CharSequence): Boolean {
        val wrapped = WRAPPED.findAll(text).count()
        return wrapped >= MIN_WRAPPED && wrapped > RETURNED_AS_IS.findAll(text).count()
    }

    /** `os.ReadFile(name)` has failed: `read file`. The name of what was called, in words; null when nothing was. */
    fun failure(statement: String?): String? {
        val called = CALLED.find(statement ?: return null)?.groupValues?.get(1)?.substringAfterLast('.') ?: return null
        return called.split(HUMPS).filter { it.isNotEmpty() }.joinToString(" ") { it.lowercase() }.takeIf { it.isNotEmpty() }
    }

    /** [returnStatement] with the error wrapped where the file wraps its errors; [failed] is the statement the error has come from. */
    private fun errorReturn(text: CharSequence, offset: Int, error: String, failed: String?): String {
        val plain = returnStatement(text, offset, error)
        // `t.Fatal(err)` and `log.Fatal(err)` say where they are by themselves
        if (!plain.startsWith("return ") || !wrapsErrors(text)) return plain
        val message = failure(failed) ?: return plain
        return returnStatement(text, offset, "fmt.Errorf(\"$message: %w\", $error)")
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

    // --- return ---

    // any letter: what is typed may be of the other layout
    private val RETURN_TYPED = Regex("""^return[ \t]+[\w\p{L}]*$""")
    private val ERROR_CHECK = Regex("""^(?:\}\s*else\s+)?if\s+(?:.*;\s*)?(\w*[eE]rr\w*)\s*!=\s*nil\s*\{$""")

    /**
     * The values of the `return` that is being typed at [offset], for the completion list: the zero values of the results with the error
     * of the `if err != nil` around (`nil, err`), with `nil` for the error elsewhere. Null where the function returns less than two
     * values: one name is what the language server completes.
     */
    fun returnValues(text: CharSequence, offset: Int): String? {
        if (offset < 0 || offset > text.length) return null
        var lineStart = offset
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        var lineEnd = offset
        while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
        if (text.subSequence(offset, lineEnd).isNotBlank()) return null
        val before = text.subSequence(lineStart, offset).toString()
        if (!RETURN_TYPED.matches(before.trimStart())) return null
        val indent = before.takeWhile { it == ' ' || it == '\t' }
        // the line that opens the block of the caret: the nearest one above with a smaller indent
        val opener = linesBefore(text, lineStart).firstOrNull { it.indent.length < indent.length }
        val error = opener?.let { ERROR_CHECK.matchEntire(it.code) }?.groupValues?.get(1) ?: "nil"
        val statement = returnStatement(text, offset, error)
        return statement.removePrefix("return ").takeIf { statement.startsWith("return ") && ", " in it }
    }

    // --- if !ok ---

    /** `v, ok := m[key]`, `s, ok := x.(string)`, `v, ok := <-ch`: the comma-ok forms, and what stands on the right. */
    private val COMMA_OK = Regex("""^[\w.\[\]*]+\s*,\s*(ok\w*)\s*:?=\s*(.+)$""")
    private val MAP_LOOKUP = Regex("""^([\w.]+)\[(.+)\]$""")
    private val TYPE_ASSERTION = Regex("""^([\w.()]+)\.\((.+)\)$""")

    private fun okCheck(text: CharSequence, offset: Int, above: Line, indent: String, unit: String): String? {
        if (above.code.startsWith("if ") || above.code.startsWith("for ")) return null
        val match = COMMA_OK.matchEntire(above.code) ?: return null
        val (ok, right) = match.destructured
        val lookup = MAP_LOOKUP.matchEntire(right)
        val assertion = TYPE_ASSERTION.matchEntire(right)
        if (lookup == null && assertion == null && !right.startsWith("<-")) return null
        // what to say when it is not there: the key of a map, the type that was expected; a closed channel is not an error
        val error = when {
            lookup != null -> "fmt.Errorf(\"unknown %v\", ${lookup.groupValues[2].trim()})"
            assertion != null -> "fmt.Errorf(\"unexpected type %T\", ${assertion.groupValues[1]})"
            else -> "nil"
        }
        val statement = returnStatement(text, offset, error).let { if (error == "nil" && it.endsWith("Fatal(nil)")) "return" else it }
        return "if !$ok {\n$indent$unit$statement\n$indent}"
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

    /** A type any value may turn out to fit, as far as a name tells: `any`, an interface literal, the interfaces known by their names. */
    fun isInterfaceLike(type: String): Boolean {
        val t = type.trim()
        return t == "any" || t.startsWith("interface") || t in KNOWN_INTERFACES || INTERFACE_LIKE.containsMatchIn(t.substringAfterLast('.'))
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

    private val CONTEXT = Regex("""^\w+,\s*(\w+)\s*:?=\s*(?:context\.With(?:Cancel|CancelCause|Timeout|TimeoutCause|Deadline|DeadlineCause)|signal\.NotifyContext)\(.*\)$""")
    private val LOCK = Regex("""^([\w.]+)\.(R?)Lock\(\)$""")
    private val STOPPABLE = Regex("""^(\w+)\s*:?=\s*time\.New(?:Ticker|Timer)\(.*\)$""")

    /** `ctx, span := tracer.Start(ctx, "name")` of OpenTelemetry: by the name of the variable, a `Start` alone means many things. */
    private val SPAN = Regex("""^\w+,\s*(\w*[sS]pan\w*)\s*:?=\s*[\w.]+\.Start\(.*\)$""")
    private val SIGNALS = Regex("""^signal\.Notify\((\w+),.*\)$""")

    private fun deferAfter(above: Line): String? {
        CONTEXT.matchEntire(above.code)?.let { match -> return match.groupValues[1].takeIf { it != "_" }?.let { "defer $it()" } }
        LOCK.matchEntire(above.code)?.let { return "defer ${it.groupValues[1]}.${it.groupValues[2]}Unlock()" }
        STOPPABLE.matchEntire(above.code)?.let { return "defer ${it.groupValues[1]}.Stop()" }
        SPAN.matchEntire(above.code)?.let { return "defer ${it.groupValues[1]}.End()" }
        SIGNALS.matchEntire(above.code)?.let { return "defer signal.Stop(${it.groupValues[1]})" }
        return null
    }

    private val GO_FUNC = Regex("""^go func\(.*\)\s*\{$""")
    private val WAIT_GROUP_ADD = Regex("""^([\w.]+)\.Add\(""")
    private val CHANNEL_MADE = Regex("""^(\w+)\s*:?=\s*make\(chan\b.*\)$""")

    /**
     * The first line of `go func() {`, before anything can return early: `defer wg.Done()` below a `wg.Add(1)`; `defer close(ch)`
     * below a channel that was just made, which the goroutine that writes to it is the one to close.
     */
    private fun deferInGoroutine(previous: List<Line>): String? {
        if (!GO_FUNC.matches(previous.firstOrNull()?.code.orEmpty())) return null
        val above = previous.drop(1)
        above.firstNotNullOfOrNull { WAIT_GROUP_ADD.find(it.code)?.groupValues?.get(1) }?.let { return "defer $it.Done()" }
        return above.firstNotNullOfOrNull { CHANNEL_MADE.matchEntire(it.code)?.groupValues?.get(1) }?.let { "defer close($it)" }
    }

    private val SCANNER = Regex("""^(\w+)\s*:?=\s*bufio\.NewScanner\(.*\)$""")
    private val LOOP = Regex("""^for (\w+)\.(Next|Scan)\(\) \{$""")

    /** After `scanner := bufio.NewScanner(r)`: the loop over its lines. */
    private fun scannerLoop(above: Line, indent: String, unit: String): String? =
        SCANNER.matchEntire(above.code)?.groupValues?.get(1)?.let { "for $it.Scan() {\n$indent$unit\n$indent}" }

    /**
     * After the loop over rows or over the lines of a scanner: the error the loop has stopped at, which `Next` and `Scan` do not
     * return and which is forgotten more often than anything else here.
     */
    private fun errorAfterLoop(text: CharSequence, offset: Int, previous: List<Line>, indent: String, unit: String): String? {
        val brace = previous.firstOrNull()?.takeIf { it.code == "}" } ?: return null
        val open = previous.indexOfFirst { it !== brace && it.indent == brace.indent }
        if (open < 1 || previous.subList(1, open).any { it.indent.length <= brace.indent.length }) return null
        val (name, method) = LOOP.matchEntire(previous[open].code)?.destructured ?: return null
        // what a wrapped error says has failed: `scan`, `read rows`
        val failed = if (method == "Scan") "scan()" else "readRows()"
        return "if err := $name.Err(); err != nil {\n$indent$unit${errorReturn(text, offset, "err", failed)}\n$indent}"
    }

    private val CLOSED_ROWS = Regex("""^defer (\w+)\.Close\(\)$""")
    private val QUERIED = Regex("""^(\w+),\s*\w*[eE]rr\w*\s*:?=\s*[\w.]+\.(Query|QueryContext)\(.*\)$""")

    /** After `defer rows.Close()` of what `Query` returned: the loop over the rows (the caret lands after the block; its body is the next thing to write). */
    private fun rowsLoop(previous: List<Line>, indent: String, unit: String): String? {
        val rows = CLOSED_ROWS.matchEntire(previous.firstOrNull()?.code.orEmpty())?.groupValues?.get(1) ?: return null
        if (previous.drop(1).none { QUERIED.matchEntire(it.code)?.groupValues?.get(1) == rows }) return null
        return "for $rows.Next() {\n$indent$unit\n$indent}"
    }

    private val OPENED = Regex("""^(\w+),\s*\w*[eE]rr\w*\s*:?=\s*([\w.]+)\(.*\)$""")

    /** What is to be closed once the error of opening it has been dealt with; by the name of the function that opens. */
    private val CLOSERS: List<Pair<Regex, (String) -> String>> = listOf(
        Regex("""^os\.(Open|Create|OpenFile|CreateTemp)$""") to { name -> "$name.Close()" },
        Regex("""^os\.MkdirTemp$""") to { name -> "os.RemoveAll($name)" },
        Regex("""^(net\.(Dial|DialTimeout|Listen)|sql\.Open|grpc\.(Dial|NewClient)|tls\.Dial|zip\.OpenReader|gzip\.NewReader)$""") to { name -> "$name.Close()" },
        Regex("""^(http\.(Get|Post|Head|PostForm)|[\w.]+\.(Do|Get|Post))$""") to { name -> "$name.Body.Close()" },
        Regex("""^[\w.]+\.(Query|QueryContext|Prepare|PrepareContext)$""") to { name -> "$name.Close()" },
        Regex("""^[\w.]+\.(Begin|BeginTx)$""") to { name -> "$name.Rollback()" },
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
        return CLOSERS.firstOrNull { it.first.matches(opener) }?.let { "defer ${it.second(name)}" }
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

    // enough to see from the end of a loop to its beginning
    private const val MAX_LINES_BACK = 40
    private const val MIN_WRAPPED = 2
    private val TYPE_WORDS = setOf("func", "chan", "map", "struct", "interface")
    private val NUMERIC = setOf(
        "int", "int8", "int16", "int32", "int64", "uint", "uint8", "uint16", "uint32", "uint64", "uintptr", "byte", "rune", "float32", "float64", "complex64", "complex128",
        "time.Duration",
    )
    private val KNOWN_INTERFACES = setOf("context.Context", "net.Conn", "net.Listener", "net.Addr", "fmt.Stringer", "sort.Interface", "http.RoundTripper", "driver.Value")

    /** `io.Reader`, `http.Handler`, `ResponseWriter`: names interfaces are given. `Order`, `Buffer`, `Server` are not among them. */
    private val INTERFACE_LIKE = Regex("""(Reader|Writer|Closer|Seeker|Handler|Marshaler|Unmarshaler|Scanner|Formatter|Interface)$""")
}
