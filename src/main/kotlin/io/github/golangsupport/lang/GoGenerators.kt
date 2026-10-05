package io.github.golangsupport.lang

/**
 * What Alt+Insert and the intentions write: constructors, accessors, `String()`, struct tags, the methods of an interface, a test, a
 * missing `return`, a function that is called but not written. Text in, text out: the PSI gives the structs and their fields
 * ([GoStructPsi], as [GoDeclarationInfo] through [GoStructPsi.infoOf]) and the method sets of interfaces, the caller puts the result where it
 * belongs. Nothing here looks into types: the callers decide from the PSI and its types what to ask for.
 */
object GoGenerators {
    private val ACRONYMS = setOf("ID", "URL", "URI", "HTTP", "HTTPS", "API", "JSON", "XML", "SQL", "DB", "UUID", "IP", "TCP", "UDP", "HTML", "CSS", "OS", "TLS", "SSH", "RPC", "GRPC", "CPU", "RAM", "TTL", "UID", "GID")

    /** The fields of a struct that are named (embedded ones have no type of their own here). */
    fun fields(struct: GoDeclarationInfo): List<GoDeclarationInfo> = struct.children.filter { it.kind == GoDeclarationKind.FIELD && it.signature != null }

    /** `s` for `Server`, `hc` for `HTTPClient`: the receiver name of a method, the way gofmt users write it. */
    fun receiverName(typeName: String): String {
        // the words of the name, an acronym being one: `HTTPClient` is HTTP + Client
        val words = Regex("""[A-Z]+(?=[A-Z][a-z])|[A-Z]?[a-z0-9]+|[A-Z]+""").findAll(typeName).map { it.value }.toList()
        val name = (if (words.size in 2..3) words.joinToString("") { it.take(1) } else typeName.take(1)).lowercase()
        return name.ifEmpty { "r" }.let { if (it in KEYWORDS) it + "_" else it }
    }

    /** `Name` -> `name`, `ID` -> `id`, `HTTPClient` -> `httpClient`: a parameter or a variable for a field. */
    fun parameterName(fieldName: String): String {
        val upper = fieldName.takeWhile { it.isUpperCase() }
        val name = when {
            upper.isEmpty() -> fieldName
            upper.length == fieldName.length -> fieldName.lowercase()
            upper.length > 1 -> upper.dropLast(1).lowercase() + fieldName.substring(upper.length - 1)
            else -> fieldName.replaceFirstChar { it.lowercaseChar() }
        }
        return if (name in KEYWORDS) name + "_" else name
    }

    /** `Name` -> `name`, `HTTPClient` -> `http_client`, `UserID` -> `user_id`: the name of a field in a JSON / YAML tag. */
    fun snakeCase(name: String): String {
        val result = StringBuilder()
        for ((i, c) in name.withIndex()) {
            val previous = name.getOrNull(i - 1)
            val next = name.getOrNull(i + 1)
            if (c.isUpperCase() && i > 0 && (previous?.isLowerCase() == true || previous?.isDigit() == true || (next?.isLowerCase() == true && previous?.isUpperCase() == true))) result.append('_')
            result.append(c.lowercaseChar())
        }
        return result.toString()
    }

    fun camelCase(name: String): String = parameterName(name)

    /** `func NewServer(port int, name string) *Server { return &Server{port: port, name: name} }`. */
    fun constructor(struct: GoDeclarationInfo, fields: List<GoDeclarationInfo> = fields(struct), pointer: Boolean = true): String {
        val parameters = fields.joinToString(", ") { "${parameterName(it.name)} ${it.signature}" }
        val assignments = fields.joinToString(", ") { "${it.name}: ${parameterName(it.name)}" }
        val type = struct.name
        val exported = struct.isExported
        val name = if (exported) "New$type" else "new" + type.replaceFirstChar { it.uppercaseChar() }
        val amp = if (pointer) "&" else ""
        val result = if (pointer) "*$type" else type
        return "func $name($parameters) $result {\n\treturn $amp$type{$assignments}\n}\n"
    }

    /** `func (s *Server) Port() int { return s.port }` for each field; the getter of an exported field is named after it all the same. */
    fun getters(struct: GoDeclarationInfo, fields: List<GoDeclarationInfo> = fields(struct)): String {
        val r = receiverName(struct.name)
        return fields.joinToString("\n") { field -> "func ($r *${struct.name}) ${accessorName(field.name)}() ${field.signature} {\n\treturn $r.${field.name}\n}\n" }
    }

    fun setters(struct: GoDeclarationInfo, fields: List<GoDeclarationInfo> = fields(struct)): String {
        val r = receiverName(struct.name)
        return fields.joinToString("\n") { field ->
            val value = parameterName(field.name).let { if (it == r) it + "_" else it }
            "func ($r *${struct.name}) Set${accessorName(field.name)}($value ${field.signature}) {\n\t$r.${field.name} = $value\n}\n"
        }
    }

    /** `port` -> `Port`, `id` -> `ID`, `Name` -> `Name`. */
    fun accessorName(fieldName: String): String = if (fieldName.uppercase() in ACRONYMS) fieldName.uppercase() else fieldName.replaceFirstChar { it.uppercaseChar() }

    /** `func (s Server) String() string { return fmt.Sprintf("Server{Port: %v, Name: %v}", s.Port, s.Name) }`. */
    fun stringMethod(struct: GoDeclarationInfo, fields: List<GoDeclarationInfo> = fields(struct)): String {
        val r = receiverName(struct.name)
        val format = fields.joinToString(", ") { "${it.name}: %v" }
        val arguments = fields.joinToString(", ") { "$r.${it.name}" }
        return "func ($r ${struct.name}) String() string {\n\treturn fmt.Sprintf(\"${struct.name}{$format}\"${if (arguments.isEmpty()) "" else ", $arguments"})\n}\n"
    }

    /**
     * `String()` of an enum the way `stringer` writes it: a case per member (one name per value, the caller decides which), the number
     * in the type's name for any other value. The number is converted to the [underlying] type: `%d` on the value itself would call
     * this `String()` again.
     */
    fun enumStringMethod(typeName: String, receiver: String, members: List<String>, underlying: String): String {
        val cases = members.joinToString("") { "\tcase $it:\n\t\treturn \"$it\"\n" }
        return "func ($receiver $typeName) String() string {\n\tswitch $receiver {\n$cases\t}\n\treturn fmt.Sprintf(\"$typeName(%d)\", $underlying($receiver))\n}\n"
    }

    /** How Equal compares a field; [importPath] is the package the comparison calls. */
    enum class EqualKind(val importPath: String?) {
        /** `==`: every comparable type, pointers by address. */
        OPERATOR(null),
        BYTES("bytes"),
        /** `slices.Equal` for a slice of comparable elements. */
        SLICES("slices"),
        /** `maps.Equal` for a map with comparable values. */
        MAPS("maps"),
        /** `time.Time` has its own `Equal`: `==` compares the location too. */
        TIME(null),
        /** Not comparable otherwise (a func, a slice of slices): `reflect.DeepEqual`, only when the field is ticked by hand. */
        DEEP("reflect");

        /** Whether the field is ticked in the dialog at the start. */
        val byDefault: Boolean get() = this != DEEP
    }

    fun equalComparison(field: String, kind: EqualKind, a: String, b: String): String = when (kind) {
        EqualKind.OPERATOR -> "$a.$field == $b.$field"
        EqualKind.BYTES -> "bytes.Equal($a.$field, $b.$field)"
        EqualKind.SLICES -> "slices.Equal($a.$field, $b.$field)"
        EqualKind.MAPS -> "maps.Equal($a.$field, $b.$field)"
        EqualKind.TIME -> "$a.$field.Equal($b.$field)"
        EqualKind.DEEP -> "reflect.DeepEqual($a.$field, $b.$field)"
    }

    /**
     * `func (p Point) Equal(other Point) bool { return p.X == other.X && … }`; the parameter has the receiver's type ([pointer]: `*T`).
     * More than three comparisons go one per line.
     */
    fun equalMethod(typeName: String, receiver: String, pointer: Boolean, fields: List<Pair<String, EqualKind>>): String {
        val other = if (receiver == "other") "o" else "other"
        val type = if (pointer) "*$typeName" else typeName
        val comparisons = fields.map { (name, kind) -> equalComparison(name, kind, receiver, other) }
        val body = if (comparisons.isEmpty()) "true" else comparisons.joinToString(if (comparisons.size > 3) " &&\n\t\t" else " && ")
        return "func ($receiver $type) Equal($other $type) bool {\n\treturn $body\n}\n"
    }

    /** The packages the comparisons of [kinds] call, sorted. */
    fun equalImports(kinds: Collection<EqualKind>): List<String> = kinds.mapNotNull { it.importPath }.distinct().sorted()

    enum class TagCase(val title: String, val apply: (String) -> String) {
        SNAKE("snake_case", ::snakeCase), CAMEL("camelCase", ::camelCase), AS_IS("as the field", { it }), LOWER("lowercase", { it.lowercase() });

        override fun toString(): String = title
    }

    /**
     * The text of a struct body with `json:"name"` (and the other [kinds]) on every named field; a tag that is there keeps its other
     * keys and gets the missing ones. Unexported fields are skipped for `json` and `yaml`, which cannot see them anyway.
     */
    fun withTags(structText: String, fields: List<GoDeclarationInfo>, bodyStart: Int, kinds: List<String>, case: TagCase, omitEmpty: Boolean): String {
        val result = StringBuilder(structText)
        // from the last field up: the offsets of the fields above stay right
        for (field in fields.sortedByDescending { it.range.startOffset }) {
            val start = field.range.startOffset - bodyStart
            val end = field.range.endOffset - bodyStart
            if (start < 0 || end > structText.length) continue
            val line = structText.substring(start, end)
            result.replace(start, end, withTag(line, field, kinds, case, omitEmpty))
        }
        return result.toString()
    }

    /** One field line with its tag: `Name string` -> `Name string \`json:"name"\``. */
    fun withTag(line: String, field: GoDeclarationInfo, kinds: List<String>, case: TagCase, omitEmpty: Boolean): String {
        val existing = Regex("""`([^`]*)`\s*$""").find(line)
        val tag = tagFor(field.name, field.isExported, existing?.groupValues?.get(1), kinds, case, omitEmpty) ?: return line
        return if (existing != null) line.substring(0, existing.range.first) + tag else line.trimEnd() + " " + tag
    }

    /**
     * The raw tag literal of a field named [name] with the keys of [kinds] it lacks added to [existing] (the value of its tag, without
     * the quotes, or null); null when nothing is added. `json`, `yaml` and `xml` skip an unexported field, which they cannot see.
     */
    fun tagFor(name: String, exported: Boolean, existing: String?, kinds: List<String>, case: TagCase, omitEmpty: Boolean): String? =
        tagFor(name, exported, existing, kinds, case.apply, omitEmpty)

    /** [tagFor] with the name of the field written by [naming]. */
    fun tagFor(name: String, exported: Boolean, existing: String?, kinds: List<String>, naming: (String) -> String, omitEmpty: Boolean): String? {
        val wanted = kinds.filter { exported || it !in setOf("json", "yaml", "xml") }
        if (wanted.isEmpty()) return null
        val present = existing?.let { Regex("""(\w+):"[^"]*"""").findAll(it).map { m -> m.groupValues[1] }.toSet() }.orEmpty()
        val added = wanted.filter { it !in present }.joinToString(" ") { kind -> "$kind:\"${naming(name)}${if (omitEmpty && kind == "json") ",omitempty" else ""}\"" }
        if (added.isEmpty()) return null
        return "`" + ((existing?.let { "$it " } ?: "") + added).trim() + "`"
    }

    /** The methods of [iface] for [typeName], each with a body that panics: what Implement Interface writes. */
    fun interfaceStubs(typeName: String, iface: GoDeclarationInfo, pointer: Boolean = true): String =
        methodStubs(typeName, iface.children.filter { it.kind == GoDeclarationKind.INTERFACE_METHOD }.map { it.name to it.signature.orEmpty() }, pointer)

    /** A stub per (name, signature) of [methods]. */
    fun methodStubs(typeName: String, methods: List<Pair<String, String>>, pointer: Boolean = true): String {
        val r = receiverName(typeName)
        val receiver = if (pointer) "*$typeName" else typeName
        return methods.joinToString("\n") { (name, signature) -> "func ($r $receiver) $name$signature {\n\tpanic(\"not implemented\")\n}\n" }
    }

    /** The methods a type has already, so that Implement Interface adds only what is missing. */
    fun missingMethods(iface: GoDeclarationInfo, existing: Set<String>): GoDeclarationInfo =
        GoDeclarationInfo(iface.kind, iface.name, iface.nameRange, iface.range, body = iface.body, children = iface.children.filter { it.name !in existing })

    /**
     * A table-driven test for a function or a method; the arguments and the want are left for the writer, as GoLand does. A variadic
     * parameter is a slice field passed with `...`, an unnamed or `_` one is named `arg1`, `arg2`… by position, and results `==` cannot
     * compare (slices, maps, funcs, structs…) are compared with `reflect.DeepEqual` ([testImports] then has `reflect`).
     */
    fun testFunction(function: GoDeclarationInfo, packageName: String?): String {
        val name = if (function.receiver != null) "${function.receiver}_${function.name}" else function.name
        val (parameters, results) = GoIdioms.splitSignature(function.signature.orEmpty())
        val names = parameters.mapIndexed { i, p -> p.name?.takeIf { it != "_" } ?: "arg${i + 1}" }
        val fields = parameters.mapIndexed { i, p -> "\t\t${names[i]} ${p.type.trim().let { t -> if (t.startsWith("...")) "[]" + t.removePrefix("...").trim() else t }}" } +
            results.mapIndexed { i, r -> "\t\twant${if (results.size > 1) i.toString() else ""} ${r.type}" }
        val call = parameters.mapIndexed { i, p -> "tt.${names[i]}" + if (p.type.trimStart().startsWith("...")) "..." else "" }.joinToString(", ")
        val callee = if (function.receiver != null) "${receiverName(function.receiver)}.${function.name}" else function.name
        val gots = results.indices.joinToString(", ") { "got${if (results.size > 1) it.toString() else ""}" }
        val receiverSetup = if (function.receiver != null) "\t\t\tvar ${receiverName(function.receiver)} ${function.receiver}\n" else ""
        val body = if (results.isEmpty()) "$receiverSetup\t\t\t$callee($call)\n"
        else "$receiverSetup\t\t\t$gots := $callee($call)\n" + results.indices.joinToString("") { i ->
            val suffix = if (results.size > 1) i.toString() else ""
            val differs = if (isComparable(results[i].type)) "got$suffix != tt.want$suffix" else "!reflect.DeepEqual(got$suffix, tt.want$suffix)"
            "\t\t\tif $differs {\n\t\t\t\tt.Errorf(\"${function.name}() = %v, want %v\", got$suffix, tt.want$suffix)\n\t\t\t}\n"
        }
        return "func Test$name(t *testing.T) {\n\ttests := []struct {\n\t\tname string\n${fields.joinToString("") { "$it\n" }}\t}{\n\t\t// TODO: add test cases\n\t}\n" +
            "\tfor _, tt := range tests {\n\t\tt.Run(tt.name, func(t *testing.T) {\n$body\t\t})\n\t}\n}\n"
    }

    /** The imports the [testFunction] of [function] needs: `testing`, and `reflect` when a result is compared with `reflect.DeepEqual`. */
    fun testImports(function: GoDeclarationInfo): List<String> =
        listOf("testing") + if (GoIdioms.splitSignature(function.signature.orEmpty()).second.any { !isComparable(it.type) }) listOf("reflect") else emptyList()

    private val COMPARABLE_TYPES = setOf(
        "bool", "string", "error", "byte", "rune", "uintptr", "int", "int8", "int16", "int32", "int64", "uint", "uint8", "uint16", "uint32", "uint64",
        "float32", "float64", "complex64", "complex128",
    )

    /** Whether `!=` surely compiles and means equality for a value of [type] as written: basic types, pointers and channels; else DeepEqual. */
    private fun isComparable(type: String): Boolean {
        val t = type.trim()
        return t in COMPARABLE_TYPES || t.startsWith("*") || t.startsWith("chan ") || t.startsWith("<-chan") || t.startsWith("chan<-")
    }

    /**
     * Override Methods: `func (s *Server) Close() error { return s.Conn.Close() }` for the method [name] promoted through the embedded
     * [field]; [signature] is `(params) results` as the struct's file writes it. Unnamed and `_` parameters get names (`arg0`…) to be
     * passed on, a variadic one is passed with `...`, a receiver named like a parameter becomes `recv`.
     */
    fun delegatingMethod(typeName: String, receiver: String, pointer: Boolean, field: String, name: String, signature: String): String {
        val text = signature.trim()
        val close = closingParen(text, 0)
        if (!text.startsWith("(") || close < 0) return "func ($receiver ${if (pointer) "*" else ""}$typeName) $name$text {\n\tpanic(\"not implemented\")\n}\n"
        val parameters = GoIdioms.splitSignature(text).first
        val names = parameters.mapIndexed { i, p -> p.name?.takeIf { it != "_" } ?: "arg$i" }
        val parameterText = if (parameters.all { it.name != null && it.name != "_" }) text.substring(1, close).trim()
        else parameters.mapIndexed { i, p -> "${names[i]} ${p.type}" }.joinToString(", ")
        val results = text.substring(close + 1).trim()
        var recv = receiver
        if (recv in names) {
            recv = "recv"
            while (recv in names) recv += "_"
        }
        val arguments = parameters.mapIndexed { i, p -> names[i] + if (p.type.trimStart().startsWith("...")) "..." else "" }.joinToString(", ")
        val call = "$recv.$field.$name($arguments)"
        return "func ($recv ${if (pointer) "*" else ""}$typeName) $name($parameterText)${if (results.isEmpty()) "" else " $results"} {\n\t${if (results.isEmpty()) "" else "return "}$call\n}\n"
    }

    /**
     * Generate | Method: `func (t *T) Name(params) results { panic("not implemented") }`. [results] typed as one line: several or named
     * ones (`int, error`, `n int`) are put in parentheses.
     */
    fun method(typeName: String, receiver: String, pointer: Boolean, name: String, parameters: String, results: String): String {
        val r = results.trim().removeSuffix(",")
        val needsParens = r.isNotEmpty() && !(r.startsWith("(") && closingParen(r, 0) == r.length - 1) &&
            (hasTopLevelComma(r) || Regex("""^[A-Za-z_]\w*\s+\S""").find(r)?.let { r.substringBefore(' ') !in TYPE_KEYWORDS } == true)
        val resultText = when {
            r.isEmpty() -> ""
            needsParens -> " ($r)"
            else -> " $r"
        }
        return "func ($receiver ${if (pointer) "*" else ""}$typeName) ${name.trim()}(${parameters.trim()})$resultText {\n\tpanic(\"not implemented\")\n}\n"
    }

    private val TYPE_KEYWORDS = setOf("func", "map", "chan", "struct", "interface")

    /** The name `TestXxx` the test of [function] has (`TestServer_Start` for a method), as [testFunction] writes it. */
    fun testName(function: GoDeclarationInfo): String = "Test" + if (function.receiver != null) "${function.receiver}_${function.name}" else function.name

    /**
     * Tests for package: the exported functions and methods of [functions] (no `init`, `main` or test functions) whose test is in none of
     * [testTexts] (the `_test.go` files of the package).
     */
    fun untested(functions: List<GoDeclarationInfo>, testTexts: Collection<String>): List<GoDeclarationInfo> {
        // the test names of the files, read once: not a regex per function and file
        val existing = testTexts.flatMapTo(HashSet()) { text -> TEST_DECLARATION.findAll(text).map { it.groupValues[1] } }
        return functions.filter { f ->
            (f.kind == GoDeclarationKind.FUNCTION || f.kind == GoDeclarationKind.METHOD) && f.isExported && !TEST_LIKE.containsMatchIn(f.name) && testName(f) !in existing
        }
    }

    private val TEST_DECLARATION = Regex("""func (Test\w*)\(""")
    private val TEST_LIKE = Regex("""^(Test|Benchmark|Fuzz|Example)""")

    private fun hasTopLevelComma(text: String): Boolean {
        var depth = 0
        for (c in text) {
            when (c) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) return true
            }
        }
        return false
    }

    private fun closingParen(text: String, open: Int): Int {
        var depth = 0
        for (i in open until text.length) {
            if (text[i] == '(') depth++ else if (text[i] == ')' && --depth == 0) return i
        }
        return -1
    }

    /** `return 0, nil` for `(int, error)`; empty for a function without results. */
    fun returnStatement(signature: String?): String? {
        val results = GoIdioms.splitSignature(signature.orEmpty()).second
        if (results.isEmpty()) return null
        return "return " + results.joinToString(", ") { GoIdioms.zeroValue(it.type) }
    }

    /** `func name(a int, s string) {…}` for a call `name(a, s)`: names from the arguments when they are names, types are for the writer. */
    /** [results]: the names the call site assigns to (`a, err := f()`); each result is `any`, `error` when its name starts with `err`. */
    fun functionFromCall(name: String, arguments: List<String>, receiverType: String? = null, results: List<String> = emptyList()): String {
        val parameters = arguments.mapIndexed { i, argument ->
            val trimmed = argument.trim()
            val parameter = if (trimmed.all { it.isLetterOrDigit() || it == '_' } && trimmed.isNotEmpty() && !trimmed[0].isDigit() && trimmed !in KEYWORDS) trimmed else "arg${i + 1}"
            "$parameter ${guessType(trimmed)}"
        }
        val receiver = if (receiverType != null) "(${receiverName(receiverType)} *$receiverType) " else ""
        val types = results.map { if (it.startsWith("err")) "error" else "any" }
        val result = when (types.size) {
            0 -> ""
            1 -> " ${types[0]}"
            else -> " (${types.joinToString(", ")})"
        }
        return "func $receiver$name(${parameters.joinToString(", ")})$result {\n\tpanic(\"not implemented\")\n}\n"
    }

    /** The names a line assigns to before the call: `a, err := ` -> [a, err]; empty when the call is not assigned. */
    fun assignedNames(lineBeforeCall: String): List<String> {
        val match = Regex("""^\s*(?:var\s+)?([\w\s,]+?)\s*:?=\s*$""").find(lineBeforeCall) ?: return emptyList()
        return match.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** The type of a literal argument, `any` for everything else. */
    fun guessType(argument: String): String = when {
        argument.startsWith("\"") || argument.startsWith("`") -> "string"
        argument == "true" || argument == "false" -> "bool"
        argument.matches(Regex("""-?\d+""")) -> "int"
        argument.matches(Regex("""-?\d*\.\d+""")) -> "float64"
        argument == "nil" -> "any"
        argument.startsWith("&") -> "any"
        else -> "any"
    }

    /** `f(a, g(b, c), "x,y")` -> the arguments of the outer call, split at the commas of the top level. */
    fun callArguments(call: String): List<String> {
        val open = call.indexOf('(')
        if (open < 0 || !call.trimEnd().endsWith(")")) return emptyList()
        val inner = call.substring(open + 1, call.trimEnd().length - 1)
        val result = ArrayList<String>()
        var depth = 0
        var quote: Char? = null
        var start = 0
        for ((i, c) in inner.withIndex()) {
            when {
                quote != null -> if (c == quote && inner.getOrNull(i - 1) != '\\') quote = null
                c == '"' || c == '`' || c == '\'' -> quote = c
                c == '(' || c == '[' || c == '{' -> depth++
                c == ')' || c == ']' || c == '}' -> depth--
                c == ',' && depth == 0 -> { result += inner.substring(start, i).trim(); start = i + 1 }
            }
        }
        inner.substring(start).trim().takeIf { it.isNotEmpty() }?.let { result += it }
        return result
    }

    /** The name that is called in `pkg.Name(...)` or `Name(...)` at the caret: `Name` and, when qualified, the qualifier. */
    fun calledName(text: CharSequence, offset: Int): Pair<String, String?>? {
        var start = offset
        while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
        var end = offset
        while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) end++
        if (start == end || end >= text.length || text[end] != '(') return null
        val name = text.substring(start, end)
        if (name[0].isDigit()) return null
        var qualifierEnd = start
        var qualifier: String? = null
        if (qualifierEnd > 0 && text[qualifierEnd - 1] == '.') {
            var qualifierStart = qualifierEnd - 1
            while (qualifierStart > 0 && (text[qualifierStart - 1].isLetterOrDigit() || text[qualifierStart - 1] == '_')) qualifierStart--
            qualifier = text.substring(qualifierStart, qualifierEnd - 1).takeIf { it.isNotEmpty() }
        }
        return name to qualifier
    }

    /** The text of the call that starts at [nameStart]: the name and the balanced parentheses after it. */
    fun callText(text: CharSequence, nameStart: Int): String? {
        val open = text.indexOf('(', nameStart)
        if (open < 0) return null
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return text.substring(nameStart, i + 1)
            }
        }
        return null
    }

    private val KEYWORDS = GoNames.KEYWORDS
}
