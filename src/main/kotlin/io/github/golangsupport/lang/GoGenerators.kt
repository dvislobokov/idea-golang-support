package io.github.golangsupport.lang

/**
 * What Alt+Insert and the intentions write: constructors, accessors, `String()`, struct tags, the methods of an interface, a test, a
 * missing `return`, a function that is called but not written. Text in, text out: the scanner gives the declarations and their fields,
 * the caller puts the result where it belongs. Nothing here knows types beyond their names; what needs a type checker is not offered.
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
        val wanted = kinds.filter { field.isExported || it !in setOf("json", "yaml", "xml") }
        if (wanted.isEmpty()) return line
        val existing = Regex("""`([^`]*)`\s*$""").find(line)
        val present = existing?.groupValues?.get(1)?.let { Regex("""(\w+):"[^"]*"""").findAll(it).map { m -> m.groupValues[1] }.toSet() }.orEmpty()
        val added = wanted.filter { it !in present }.joinToString(" ") { kind -> "$kind:\"${case.apply(field.name)}${if (omitEmpty && kind == "json") ",omitempty" else ""}\"" }
        if (added.isEmpty()) return line
        return if (existing != null) line.substring(0, existing.range.first) + "`" + (existing.groupValues[1] + " " + added).trim() + "`"
        else line.trimEnd() + " `$added`"
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

    /** A table-driven test for a function or a method; the arguments and the want are left for the writer, as GoLand does. */
    fun testFunction(function: GoDeclarationInfo, packageName: String?): String {
        val name = if (function.receiver != null) "${function.receiver}_${function.name}" else function.name
        val (parameters, results) = GoIdioms.splitSignature(function.signature.orEmpty())
        val fields = parameters.mapIndexed { i, p -> "\t\t${p.name ?: "arg$i"} ${p.type}" } + results.mapIndexed { i, r -> "\t\twant${if (results.size > 1) i.toString() else ""} ${r.type}" }
        val call = parameters.mapIndexed { i, p -> "tt.${p.name ?: "arg$i"}" }.joinToString(", ")
        val callee = if (function.receiver != null) "${receiverName(function.receiver)}.${function.name}" else function.name
        val gots = results.indices.joinToString(", ") { "got${if (results.size > 1) it.toString() else ""}" }
        val receiverSetup = if (function.receiver != null) "\t\t\tvar ${receiverName(function.receiver)} ${function.receiver}\n" else ""
        val body = if (results.isEmpty()) "$receiverSetup\t\t\t$callee($call)\n"
        else "$receiverSetup\t\t\t$gots := $callee($call)\n" + results.indices.joinToString("") { i ->
            val suffix = if (results.size > 1) i.toString() else ""
            "\t\t\tif got$suffix != tt.want$suffix {\n\t\t\t\tt.Errorf(\"${function.name}() = %v, want %v\", got$suffix, tt.want$suffix)\n\t\t\t}\n"
        }
        return "func Test$name(t *testing.T) {\n\ttests := []struct {\n\t\tname string\n${fields.joinToString("") { "$it\n" }}\t}{\n\t\t// TODO: add test cases\n\t}\n" +
            "\tfor _, tt := range tests {\n\t\tt.Run(tt.name, func(t *testing.T) {\n$body\t\t})\n\t}\n}\n"
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

    private val KEYWORDS = GoTextTokens.KEYWORDS
}
