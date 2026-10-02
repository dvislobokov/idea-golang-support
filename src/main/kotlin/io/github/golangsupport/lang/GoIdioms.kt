package io.github.golangsupport.lang

/**
 * The next lines of code where Go leaves no choice about them: the check of an error that was just assigned, the `defer` that belongs to
 * what was just opened or locked. Shown as an inline suggestion (grey text, Tab to accept). By the text alone: the statement above the
 * caret and the signature of the function around it; no types, so only the shapes that mean one thing are recognized. On a go-psi file
 * [Types] answers what the text can only guess (see [GoIdiomTypes]).
 */
object GoIdioms {
    /** The function around the caret as the PSI knows it ([GoReturnValues.function]). */
    class Function(val name: String?, val parameters: List<Parameter>, val results: List<Parameter>, val isMain: Boolean)

    /**
     * What only the types can tell, over the PSI ([GoIdiomTypes]). Offsets are those of [suggest]'s text; an answer is null when the
     * types cannot tell (no PSI there yet, an unresolved call), and then the text decides as it does without types.
     */
    interface Types {
        /** The function around the caret. */
        val function: Function?

        /** Whether the last result of the call assigned by the statement on the line at [lineStart] is `error`. */
        fun assignsError(lineStart: Int): Boolean?

        /** Whether [receiver] (an expression or a variable of the statement on the line at [lineStart]) has `method()` (`method() error`). */
        fun hasMethod(lineStart: Int, receiver: String, method: String, returnsError: Boolean): Boolean?

        /** The cases of `select {` on the line at [lineStart], a level a tab: ctx, then timers, then channels in scope; null for none. */
        fun selectCases(lineStart: Int): List<String>?

        /** The cases of `switch x {` (constants of the named type of `x`) or `switch v := x.(type) {` (implementations of the interface). */
        fun switchCases(lineStart: Int): List<String>?
    }

    /**
     * What to insert at [offset], or null. The caret has to be on a line of its own; what is typed there already must be the beginning
     * of the suggestion (then the rest of it is returned). [unit] is one level of indentation. [types], when given, makes the guesses by
     * name exact and adds what only the types can tell (the cases of `select` and `switch`).
     */
    fun suggest(text: CharSequence, offset: Int, unit: String = "\t", types: Types? = null): String? {
        if (offset < 0 || offset > text.length) return null
        var lineStart = offset
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        var lineEnd = offset
        while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
        val before = text.subSequence(lineStart, offset).toString()
        val rest = text.subSequence(offset, lineEnd).toString().trim()
        // the bracket the editor has closed by itself is not something written after the caret
        receiver(text, lineStart, before, rest)?.let { return it }
        if (rest.isNotEmpty()) return null
        val typed = before.trimStart()
        val previous = linesBefore(text, lineStart)
        fieldTag(before, previous)?.let { return it }
        val above = previous.firstOrNull() ?: return null
        // a line without an indent of its own is as deep as the line above, and a level deeper below what opens a block
        val indent = before.takeWhile { it == ' ' || it == '\t' }.ifEmpty { above.indent + if (above.code.endsWith("{")) unit else "" }

        val below = text.subSequence(lineEnd, text.length).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        val function = types?.function
        val suggestion = typedCases(text, offset, above, below, indent, unit, types)
            ?: errorCheck(text, offset, above, indent, unit, types) ?: returnInErrorCheck(text, offset, previous, below, indent, function)
            ?: okCheck(text, offset, above, indent, unit, function)
            ?: deferAfter(above, types) ?: deferInGoroutine(previous) ?: deferAfterErrorCheck(previous, types) ?: errorAfterLoop(text, offset, previous, indent, unit, function)
            ?: scannerLoop(above, indent, unit) ?: rowsLoop(previous, indent, unit) ?: return null
        // not twice: the line below may be what would be suggested
        if (below != null && below == suggestion.lineSequence().first().trim()) return null
        if (!suggestion.startsWith(typed) || suggestion == typed) return null
        // a blank line may have no indent in the text: the editor keeps the caret of such a line in virtual space
        return (if (before.isEmpty()) indent else "") + suggestion.substring(typed.length)
    }

    /** A line of code; [start] is the offset of its first character (the indent). */
    private class Line(val indent: String, val code: String, val start: Int)

    /** The non-blank lines above, nearest first, comments at the end of a line cut off; enough of them to see an `if` block and what is above it. */
    private fun linesBefore(text: CharSequence, lineStart: Int): List<Line> {
        val result = ArrayList<Line>()
        var end = lineStart - 1
        while (end > 0 && result.size < MAX_LINES_BACK) {
            var start = end
            while (start > 0 && text[start - 1] != '\n') start--
            val raw = text.subSequence(start, end).toString().trimEnd('\r')
            val code = raw.trim().let { if (it.startsWith("//")) "" else it.substringBefore(" //").trim() }
            if (code.isNotEmpty()) result += Line(raw.takeWhile { it == ' ' || it == '\t' }, code, start)
            end = start - 1
        }
        return result
    }

    // --- select, switch and for: what the types in scope suggest ---

    private val SWITCH_OPENED = Regex("""^switch\b.*\{$""")

    /**
     * The empty body of `select {`, `switch x {`, `switch v := x.(type) {` or `for {` just opened: the cases the types give (see [Types]).
     * Only with types, and only while there is nothing in the body yet.
     */
    private fun typedCases(text: CharSequence, offset: Int, above: Line, below: String?, indent: String, unit: String, types: Types?): String? {
        if (types == null || below == null || !below.startsWith("}")) return null
        val lines = when {
            above.code == "select {" -> types.selectCases(above.start)
            SWITCH_OPENED.matches(above.code) -> types.switchCases(above.start)
            above.code == "for {" -> types.selectCases(above.start)?.let { cases -> listOf("select {") + cases + "}" }
            else -> null
        } ?: return null
        if (lines.isEmpty()) return null
        val values = lines.map { line ->
            val code = line.trimStart('\t')
            // `return ctx.Err()` is written as the function returns its errors
            val written = if (code.startsWith(CONTEXT_EXIT)) contextExit(text, offset, code.removePrefix(CONTEXT_EXIT), types.function) else code
            unit.repeat(line.length - code.length) + written
        }
        return values.joinToString("\n$indent")
    }

    /** The line of [Types.selectCases] that leaves on a cancelled context, followed by the name of the context. */
    const val CONTEXT_EXIT = "\u0000exit:"

    /** `return ctx.Err()`, with the zero values before it; a bare `return` from a function without results. */
    private fun contextExit(text: CharSequence, offset: Int, context: String, function: Function?): String =
        if (function != null && function.results.isEmpty()) "return" else returnStatement("$context.Err()", function)

    // --- if err != nil ---

    private val ERROR_ASSIGNMENT = Regex("""^(?:[\w.\[\]*]+\s*,\s*)*(\w*[eE]rr\w*)\s*:?=[^=].*[)\w\]}"`]$""")

    private fun errorCheck(text: CharSequence, offset: Int, above: Line, indent: String, unit: String, types: Types?): String? {
        if (above.code.startsWith("if ") || above.code.startsWith("for ") || above.code.startsWith("switch ") || above.code.startsWith("}")) return null
        val error = ERROR_ASSIGNMENT.matchEntire(above.code)?.groupValues?.get(1) ?: return null
        // a call that does not return an error last is not checked for one
        if (types?.assignsError(above.start) == false) return null
        return "if $error != nil {\n$indent$unit${errorExit(text, offset, error, above.code, types?.function).joinToString("\n$indent$unit")}\n$indent}"
    }

    /**
     * The body of an `if err != nil {` that was typed by hand and is still empty: the statement that leaves the function. Not above
     * something that is in the block already: a line added to a body is not the whole of it.
     */
    private fun returnInErrorCheck(text: CharSequence, offset: Int, previous: List<Line>, below: String?, indent: String, function: Function?): String? {
        val check = previous.firstOrNull() ?: return null
        val error = ERROR_CHECK.matchEntire(check.code)?.groupValues?.get(1) ?: return null
        if (below != null && !below.startsWith("}")) return null
        // what has failed is in the `if` itself (`if err := f(); err != nil {`), or in the statement above it
        val failed = if (';' in check.code) check.code.substringAfter("if ").substringBefore(';') else previous.getOrNull(1)?.code?.takeIf { ERROR_ASSIGNMENT.matches(it) }
        return errorExit(text, offset, error, failed, function).joinToString("\n$indent")
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

    private val STATUS = Regex("""status\.Errorf?\(codes\.""")

    /** The caller has sent what cannot be read: the request is bad, not the server. */
    private val BAD_INPUT = Regex("""(decode|unmarshal|parse|atoi|bind|validate)""", RegexOption.IGNORE_CASE)

    /**
     * Whether the file answers with the errors of gRPC, `status.Errorf(codes.Internal, ...)`: more often than it returns errors as
     * they are, and not less often than it wraps them. The methods of a gRPC service are written so.
     */
    fun answersWithStatus(text: CharSequence): Boolean {
        val statuses = STATUS.findAll(text).count()
        return statuses >= MIN_WRAPPED && statuses > RETURNED_AS_IS.findAll(text).count() && statuses >= WRAPPED.findAll(text).count()
    }

    /**
     * How the function around [offset] is left with the error, a line each. The way of the place it is written in:
     * - a handler of `net/http` (or of gin) answers and returns: it has no result to return the error in;
     * - a test fails, `main` dies ([returnStatement]);
     * - a file of a gRPC service returns a status;
     * - a file that wraps its errors returns the error wrapped, with what has failed in words ([failed] is the statement);
     * - the rest return the error as it is.
     */
    private fun errorExit(text: CharSequence, offset: Int, error: String, failed: String?, function: Function? = null): List<String> {
        val badInput = BAD_INPUT.containsMatchIn(failed.orEmpty())
        val parameters = enclosing(function)?.takeIf { (_, results) -> results.isEmpty() }?.first.orEmpty()
        parameters.firstOrNull { it.type == "http.ResponseWriter" }?.name?.let { writer ->
            return listOf("http.Error($writer, $error.Error(), http.${if (badInput) "StatusBadRequest" else "StatusInternalServerError"})", "return")
        }
        parameters.firstOrNull { it.type == "*gin.Context" }?.name?.let { context ->
            return listOf("$context.AbortWithStatusJSON(http.${if (badInput) "StatusBadRequest" else "StatusInternalServerError"}, gin.H{\"error\": $error.Error()})", "return")
        }
        val plain = returnStatement(error, function)
        // `t.Fatal(err)` and `log.Fatal(err)` say where they are by themselves
        val message = failure(failed)?.takeIf { plain.startsWith("return ") } ?: return listOf(plain)
        return listOf(when {
            answersWithStatus(text) -> returnStatement("status.Errorf(codes.${if (badInput) "InvalidArgument" else "Internal"}, \"$message: %v\", $error)", function)
            wrapsErrors(text) -> returnStatement("fmt.Errorf(\"$message: %w\", $error)", function)
            else -> plain
        })
    }

    /** The parameters and the results of the function around the caret, as the PSI knows it. */
    private fun enclosing(function: Function?): Pair<List<Parameter>, List<Parameter>>? = function?.let { it.parameters to it.results }

    // --- the receiver of a method ---

    private val RECEIVER_TYPED = Regex("""^func \(([\w *.]*)$""")
    private val METHOD = Regex("""^func \((\w+) (\*?)(\w+)(?:\[[^\]]*])?\) """, RegexOption.MULTILINE)
    private val TYPE = Regex("""^type (\w+)(?:\[[^\]]*])? (\w+)""", RegexOption.MULTILINE)

    /**
     * `func (` at the top of a file: the receiver, `s *Server) `. Of the type that is nearest above, by a method of it or by its
     * declaration; named and taken by pointer or by value as the methods of the type in the file are.
     */
    private fun receiver(text: CharSequence, lineStart: Int, before: String, rest: String): String? {
        val typed = RECEIVER_TYPED.matchEntire(before)?.groupValues?.get(1) ?: return null
        if (rest.isNotEmpty() && rest != ")") return null
        val above = text.subSequence(0, lineStart)
        val method = METHOD.findAll(above).lastOrNull()
        val declared = TYPE.findAll(above).lastOrNull()
        // an interface has no methods to write: the type of the methods above it is the one that goes on
        val isInterface = declared?.groupValues?.get(2) == "interface"
        val type = when {
            method != null && (declared == null || isInterface || method.range.first > declared.range.first) -> method.groupValues[3]
            declared != null && !isInterface -> declared.groupValues[1]
            else -> return null
        }
        val habit = METHOD.findAll(text).firstOrNull { it.groupValues[3] == type }
        val inside = (habit?.groupValues?.get(1) ?: type.take(1).lowercase()) + " " + (habit?.groupValues?.get(2) ?: "*") + type
        if (!inside.startsWith(typed) || inside == typed && rest == ")") return null
        return inside.substring(typed.length) + if (rest == ")") "" else ") "
    }

    // --- the tag of a field ---

    private val FIELD = Regex("""^(\w+)[ \t]+([\w.\[\]*]+)([ \t]*)$""")
    private val TAGGED = Regex("""^(\w+)\s+\S.*?\s+`([^`]+)`$""")
    private val TAG_PAIR = Regex("""(\w+):"([^"]*)"""")

    /** The ways the name of a field is written in a tag; the first that fits every field of the struct is its way. */
    private val NAMINGS: List<(String) -> String> = listOf(
        { GoGenerators.snakeCase(it) }, { GoGenerators.camelCase(it) }, { it.lowercase() }, { it }, { GoGenerators.snakeCase(it).replace('_', '-') }, { GoGenerators.snakeCase(it).uppercase() },
    )

    /** Types that are whole as they are typed: `int` may become `int64`, and it is a type before it does. */
    private val WHOLE_TYPES = setOf("string", "bool", "error", "any", "byte", "rune", "int", "int32", "int64", "uint", "uint32", "uint64", "float32", "float64", "time.Time", "time.Duration")

    /**
     * A field typed in a struct whose fields have tags: the tag, with the keys of the field above, the name written as the names of
     * the struct are (`user_name` or `userName`) and the options of the field above (`omitempty`). A key whose value is not a name of
     * the field, `validate:"required"`, is not repeated: what it says is about one field.
     */
    private fun fieldTag(before: String, previous: List<Line>): String? {
        val indent = before.takeWhile { it == ' ' || it == '\t' }
        val (name, type, space) = FIELD.matchEntire(before.trimStart())?.destructured ?: return null
        if (!name[0].isUpperCase() || (space.isEmpty() && type !in WHOLE_TYPES)) return null
        val opener = previous.firstOrNull { it.indent.length < indent.length }?.takeIf { it.code.endsWith("struct {") } ?: return null
        val fields = previous.takeWhile { it !== opener }.filter { it.indent == indent }.mapNotNull { TAGGED.matchEntire(it.code)?.destructured }
            .map { (field, tag) -> field to TAG_PAIR.findAll(tag).associate { it.groupValues[1] to it.groupValues[2] } }
        val nearest = fields.firstOrNull() ?: return null
        val pairs = nearest.second.mapNotNull { (key, value) ->
            val written = fields.mapNotNull { (field, tags) -> tags[key]?.substringBefore(',')?.takeIf { it.isNotEmpty() && it != "-" }?.let { field to it } }
            val naming = NAMINGS.firstOrNull { naming -> written.isNotEmpty() && written.all { (field, value) -> naming(field) == value } } ?: return@mapNotNull null
            val options = value.substringAfter(',', "")
            "$key:\"${naming(name)}${if (options.isEmpty()) "" else ",$options"}\""
        }
        if (pairs.isEmpty()) return null
        return (if (space.isEmpty()) " " else "") + "`" + pairs.joinToString(" ") + "`"
    }

    /**
     * What leaves [function] with the error: `return nil, err`, `return total, err`, `t.Fatal(err)`, `log.Fatal(err)`; `return err` when
     * the function is not known (no PSI there yet, dumb mode).
     */
    fun returnStatement(error: String, function: Function?): String {
        val around = function ?: return "return $error"
        val (parameters, results) = around.parameters to around.results
        if (results.isEmpty()) {
            val testing = parameters.firstOrNull { it.type.contains("testing.") }
            return when {
                testing?.name != null -> "${testing.name}.Fatal($error)"
                around.isMain -> "log.Fatal($error)"
                else -> "return"
            }
        }
        val named = results.last().name != null
        val values = results.mapIndexed { i, result ->
            val isError = result.type == "error" && i == results.indexOfLast { it.type == "error" }
            when {
                isError -> error
                named -> result.name ?: result.type
                else -> result.zero ?: zeroValue(result.type)
            }
        }
        return "return " + values.joinToString(", ")
    }

    // --- return ---

    // any letter: what is typed may be of the other layout
    private val RETURN_TYPED = Regex("""^return[ \t]+[\w\p{L}]*$""")
    private val ERROR_CHECK = Regex("""^(?:\}\s*else\s+)?if\s+(?:.*;\s*)?(\w*[eE]rr\w*)\s*!=\s*nil\s*\{$""")

    /** Whether the line of [offset] is `return` and a name being typed, with nothing after the caret: where the values are offered. */
    fun typingReturn(text: CharSequence, offset: Int): Boolean {
        if (offset < 0 || offset > text.length) return false
        var lineStart = offset
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        var lineEnd = offset
        while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
        return text.subSequence(offset, lineEnd).isBlank() && RETURN_TYPED.matches(text.subSequence(lineStart, offset).toString().trimStart())
    }

    // --- if !ok ---

    /** `v, ok := m[key]`, `s, ok := x.(string)`, `v, ok := <-ch`: the comma-ok forms, and what stands on the right. */
    private val COMMA_OK = Regex("""^[\w.\[\]*]+\s*,\s*(ok\w*)\s*:?=\s*(.+)$""")
    private val MAP_LOOKUP = Regex("""^([\w.]+)\[(.+)\]$""")
    private val TYPE_ASSERTION = Regex("""^([\w.()]+)\.\((.+)\)$""")

    private fun okCheck(text: CharSequence, offset: Int, above: Line, indent: String, unit: String, function: Function?): String? {
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
        val statement = returnStatement(error, function).let { if (error == "nil" && it.endsWith("Fatal(nil)")) "return" else it }
        return "if !$ok {\n$indent$unit$statement\n$indent}"
    }

    /** A parameter or a result; [zero] is the zero value of the type when the PSI knows it (the text guesses it by the name of the type). */
    class Parameter(val name: String?, val type: String, val zero: String? = null)

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
    private val SPAN = Regex("""^\w+,\s*(\w*[sS]pan\w*)\s*:?=\s*.+\.Start\(.*\)$""")
    private val SIGNALS = Regex("""^signal\.Notify\((\w+),.*\)$""")

    private fun deferAfter(above: Line, types: Types?): String? {
        CONTEXT.matchEntire(above.code)?.let { match -> return match.groupValues[1].takeIf { it != "_" }?.let { "defer $it()" } }
        LOCK.matchEntire(above.code)?.let {
            val (receiver, read) = it.destructured
            // a `Lock` without an `Unlock` (a file lock, a custom type) is not paired by a deferred call
            if (types?.hasMethod(above.start, receiver, "${read}Unlock", returnsError = false) == false) return null
            return "defer $receiver.${read}Unlock()"
        }
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
    private fun errorAfterLoop(text: CharSequence, offset: Int, previous: List<Line>, indent: String, unit: String, function: Function?): String? {
        val brace = previous.firstOrNull()?.takeIf { it.code == "}" } ?: return null
        val open = previous.indexOfFirst { it !== brace && it.indent == brace.indent }
        if (open < 1 || previous.subList(1, open).any { it.indent.length <= brace.indent.length }) return null
        val (name, method) = LOOP.matchEntire(previous[open].code)?.destructured ?: return null
        // what a wrapped error says has failed: `scan`, `read rows`
        val failed = if (method == "Scan") "scan()" else "readRows()"
        return "if err := $name.Err(); err != nil {\n$indent$unit${errorExit(text, offset, "err", failed, function).joinToString("\n$indent$unit")}\n$indent}"
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

    /**
     * The caret is right after `if err != nil { ... }`, and the statement above that `if` has opened something. With [types] the
     * `Close` is exact: whatever has `Close() error` is closed, whatever has not is not, whatever the name of the function that opened it.
     */
    private fun deferAfterErrorCheck(previous: List<Line>, types: Types?): String? {
        if (previous.firstOrNull()?.code != "}") return null
        val indent = previous.first().indent
        val check = previous.indexOfFirst { it.indent == indent && it.code.startsWith("if ") && it.code.endsWith("!= nil {") }
        if (check < 0 || check + 1 >= previous.size) return null
        // everything between the `if` and its brace is its body
        if (previous.subList(1, check).any { it.indent.length <= indent.length }) return null
        val opened = previous[check + 1]
        val match = OPENED.matchEntire(opened.code) ?: return null
        val (name, opener) = match.destructured
        if (name == "_") return null
        val byName = CLOSERS.firstOrNull { it.first.matches(opener) }?.second?.invoke(name)
        if (byName != null && byName != "$name.Close()") return "defer $byName"
        return when (types?.hasMethod(opened.start, name, "Close", returnsError = true)) {
            true -> "defer $name.Close()"
            false -> null
            null -> byName?.let { "defer $it" }
        }
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
