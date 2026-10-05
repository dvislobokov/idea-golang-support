package io.github.golangsupport.ide.completion

import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionUtil
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.icons.AllIcons
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.GoIdeIcons
import io.github.golangsupport.lang.psi.*
import io.github.golangsupport.semantic.scope.GoUniverse
import io.github.golangsupport.semantic.types.GoArrayType
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoChanType
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoTupleType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypeRenderer
import io.github.golangsupport.semantic.types.GoUnknownType
import io.github.golangsupport.lang.psi.GoType as PsiType

/**
 * Names for things being declared, derived from a type or an expression (PLAN.md G3 "parameter names by type", level 3 "variable name hints").
 * Pure: works on type texts as [GoTypeRenderer] prints them with package qualifiers (`*os.File`, `[]Shape`, `context.Context`) and on
 * identifiers, so it is tested without the platform. [GoNameCompletion] wires it into completion.
 */
object GoNameSuggestions {
    val KEYWORDS: Set<String> = setOf(
        "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough", "for", "func", "go", "goto", "if",
        "import", "interface", "map", "package", "range", "return", "select", "struct", "switch", "type", "var",
    )

    /** Names a declaration should not take: keywords and predeclared identifiers (GoLand: `string2 string`). */
    val RESERVED: Set<String> = KEYWORDS + GoUniverse.ALL

    /** Idiomatic names of well-known types, by `pkg.Name` (pointer stripped). */
    private val WELL_KNOWN: Map<String, String> = mapOf(
        "error" to "err", "context.Context" to "ctx",
        "testing.T" to "t", "testing.B" to "b", "testing.M" to "m", "testing.F" to "f", "testing.TB" to "tb",
        "http.ResponseWriter" to "w", "http.Request" to "r", "io.Reader" to "r", "io.Writer" to "w", "io.ReadCloser" to "rc",
        "bytes.Buffer" to "buf", "strings.Builder" to "sb", "sync.Mutex" to "mu", "sync.RWMutex" to "mu", "sync.WaitGroup" to "wg",
        "time.Duration" to "d", "time.Time" to "t", "os.File" to "f", "sql.DB" to "db", "sql.Tx" to "tx",
        "json.Decoder" to "dec", "json.Encoder" to "enc", "regexp.Regexp" to "re", "reflect.Value" to "v",
    )

    /** Names of predeclared types in a variable position (the type name itself is reserved). */
    private val BASIC: Map<String, List<String>> = buildMap {
        put("string", listOf("s", "str"))
        put("bool", listOf("ok", "b"))
        put("byte", listOf("b"))
        put("rune", listOf("r"))
        put("uintptr", listOf("p"))
        put("error", listOf("err"))
        put("any", listOf("v"))
        for (t in listOf("int", "int8", "int16", "int32", "int64", "uint", "uint8", "uint16", "uint32", "uint64")) put(t, listOf("i", "n"))
        for (t in listOf("float32", "float64")) put(t, listOf("f", "x"))
        for (t in listOf("complex64", "complex128")) put(t, listOf("c"))
    }

    /** Call prefixes dropped from a callee's name: `GetName()` → `name`, `NewServer()` → `server`, `ParseConfig()` → `config`. */
    private val CALL_PREFIXES = listOf("Get", "New", "Create", "Make", "Build", "Load", "Parse", "Read", "Fetch", "Find", "Lookup", "Must", "Open", "To", "As")

    /** Callees whose own name says nothing about the result (`os.Open(…)` is named by its type: `file`, `f`). */
    private val VERBS = setOf(
        "open", "read", "write", "load", "parse", "do", "run", "get", "dial", "listen", "accept", "exec", "query", "marshal", "unmarshal",
        "compile", "join", "split", "trim", "sprintf", "sprint", "errorf", "atoi", "itoa", "call", "invoke", "apply", "eval", "make", "new", "append",
    )

    /** What an expression word names: the result of a call, a value, an element of a collection (`range names`, `names[0]`, `<-names`). */
    enum class Source { CALL, VALUE, ELEMENT }

    // --- parameters ---

    /** GoLand's parameter name for [typeText]: the idiomatic name or the lowercased type name, with a digit when reserved or [taken]. */
    fun parameterName(typeText: String, taken: (String) -> Boolean = { false }): String {
        val bare = typeText.removePrefix("*").substringBefore('[')
        val base = WELL_KNOWN[bare] ?: decapitalize(bare.substringAfterLast('.'))
        return unique(base, taken)
    }

    // --- variables ---

    /** Candidate names for a value of type [typeText], best first, not yet made unique. */
    fun namesForType(typeText: String): List<String> {
        val t = typeText.trim().removePrefix("*")
        val out = LinkedHashSet<String>()
        when {
            t.isEmpty() || t == "?" -> {}
            t.startsWith("[") -> {
                val elem = t.substringAfter(']')
                if (elem == "byte" || elem == "uint8") out += listOf("data", "buf", "b")
                else namesForType(elem).filter { it.length > 1 }.mapTo(out, ::plural)
                if (out.isEmpty()) out += "items"
            }
            t.startsWith("map[") -> {
                out += "m"
                namesForType(valueOfMap(t)).filter { it.length > 1 }.take(1).mapTo(out, ::plural)
            }
            t.startsWith("chan") || t.startsWith("<-chan") -> out += "ch"
            t.startsWith("func") -> out += listOf("fn", "f")
            t.startsWith("struct{") -> {}
            t.startsWith("interface{") -> out += "v"
            else -> {
                val bare = t.substringBefore('[').let { if (it.startsWith("builtin.")) it.removePrefix("builtin.") else it }
                BASIC[bare]?.let { return it }
                WELL_KNOWN[bare]?.let { out += it }
                val name = bare.substringAfterLast('.')
                val full = decapitalize(name)
                if (full !in RESERVED) out += full
                val last = decapitalize(words(name).lastOrNull() ?: name)
                if (last !in RESERVED) out += last
                name.firstOrNull()?.lowercaseChar()?.toString()?.let { out += it }
            }
        }
        return out.filter { it.isNotEmpty() }
    }

    /** Names from the word of an expression: the callee of a call, a value's own name, the singular for an element of a collection. */
    fun namesForExpression(word: String, source: Source): List<String> {
        if (word.isEmpty() || word == "_") return emptyList()
        val name = when (source) {
            Source.CALL -> when {
                word == "len" || word == "cap" -> "n"
                word.lowercase() in VERBS -> null
                else -> decapitalize(stripCallPrefix(word))
            }
            Source.VALUE -> decapitalize(word)
            Source.ELEMENT -> singular(decapitalize(word))
        } ?: return emptyList()
        return listOf(if (name == "type") "typ" else name).filter { it !in RESERVED && it.length > 0 }
    }

    /** Names of the index (first) variable of `for … range` over a slice, array, string or integer. */
    val INDEX_NAMES: List<String> = listOf("i")

    /**
     * [names] in order, each made unique against [taken] (and each other): a reserved or taken name gets the next free digit
     * (`string2`), `i` moves on to `j`, `k` first.
     */
    fun uniqueAll(names: List<String>, taken: (String) -> Boolean): List<String> {
        val used = HashSet<String>()
        val out = ArrayList<String>()
        for (n in names) {
            val u = unique(n) { taken(it) || it in used }
            if (used.add(u)) out += u
        }
        return out
    }

    fun unique(name: String, taken: (String) -> Boolean): String {
        val free = { n: String -> n !in RESERVED && !taken(n) }
        if (free(name)) return name
        if (name == "i") listOf("j", "k").firstOrNull(free)?.let { return it }
        var i = 2
        while (!free("$name$i")) i++
        return "$name$i"
    }

    // --- words ---

    /** `Base` → `base`, `URL` → `url`, `HTTPClient` → `httpClient`, `ID` → `id`. */
    fun decapitalize(name: String): String {
        if (name.isEmpty() || !name[0].isUpperCase()) return name
        var upper = 0
        while (upper < name.length && name[upper].isUpperCase()) upper++
        return when {
            upper == 1 -> name[0].lowercaseChar() + name.substring(1)
            upper == name.length -> name.lowercase()
            // `HTTPClient`: the last capital starts the next word.
            name[upper].isLetter() -> name.substring(0, upper - 1).lowercase() + name.substring(upper - 1)
            else -> name.substring(0, upper).lowercase() + name.substring(upper)
        }
    }

    /** Camel-case words: `HTTPClient` → `HTTP`, `Client`; `responseWriter` → `response`, `Writer`. */
    fun words(name: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        for (i in 1 until name.length) {
            val c = name[i]
            val boundary = c.isUpperCase() && (!name[i - 1].isUpperCase() || (i + 1 < name.length && name[i + 1].isLowerCase())) ||
                c.isDigit() != name[i - 1].isDigit() || c == '_'
            if (boundary) {
                if (i > start) out += name.substring(start, i).trim('_')
                start = i
            }
        }
        if (start < name.length) out += name.substring(start).trim('_')
        return out.filter { it.isNotEmpty() }
    }

    /** `names` → `name`, `entries` → `entry`, `boxes` → `box`, `nameList` → `name`; null when [word] does not look plural. */
    fun singular(word: String): String? = when {
        word.endsWith("List") && word.length > 4 -> word.dropLast(4)
        word.endsWith("Slice") && word.length > 5 -> word.dropLast(5)
        word == "children" -> "child"
        word == "people" -> "person"
        word.endsWith("ies") && word.length > 3 -> word.dropLast(3) + "y"
        listOf("sses", "xes", "zes", "ches", "shes").any { word.endsWith(it) } -> word.dropLast(2)
        word.endsWith("ss") || word.endsWith("us") || word.endsWith("is") -> null
        word.endsWith("s") && word.length > 1 -> word.dropLast(1)
        else -> null
    }

    /** `shape` → `shapes`, `entry` → `entries`, `box` → `boxes`. */
    fun plural(word: String): String = when {
        word.endsWith("y") && word.length > 1 && word[word.length - 2] !in "aeiou" -> word.dropLast(1) + "ies"
        listOf("s", "x", "z", "ch", "sh").any { word.endsWith(it) } -> word + "es"
        else -> word + "s"
    }

    private fun stripCallPrefix(word: String): String {
        for (p in CALL_PREFIXES) {
            if (word.length > p.length && word[p.length].isUpperCase() && (word.startsWith(p) || word.startsWith(p.lowercase()))) return word.substring(p.length)
        }
        return word
    }

    /** The value type of `map[K]V` (brackets inside K balanced). */
    private fun valueOfMap(t: String): String {
        var depth = 0
        for (i in 3 until t.length) {
            when (t[i]) {
                '[' -> depth++
                ']' -> if (--depth == 0) return t.substring(i + 1)
            }
        }
        return ""
    }
}

/**
 * The completion side of [GoNameSuggestions]: `name Type` items at a parameter name position of a function, method or function literal
 * (`func g(<caret>`: `err error`, `base Base`, `string2 string`, `ctx context.Context`), and names at a variable or parameter name being declared
 * (`var <caret> Base`, `<caret> := c.Area()`, `for i, <caret> := range names`, `func g(<caret> Base)`).
 */
object GoNameCompletion {

    /**
     * `func g(<caret>`, `func g(a int, <caret>`: a parameter declaration that is a bare type name of a function, method or literal signature
     * (results, receivers, function types and `...T` keep the ordinary type completion; a qualified `pkg.<caret>` too).
     */
    fun isParameterNamePosition(context: GoCompletionContext): Boolean {
        if (context.kind != GoCompletionContext.Kind.TYPE) return false
        val ref = context.typeReference ?: return false
        if (ref.referenceExpression != null) return false
        val decl = (ref.parent as? PsiType)?.parent as? GoParameterDeclaration ?: return false
        if (decl.paramDefinitionList.isNotEmpty() || decl.node.findChildByType(GoTypes.ELLIPSIS) != null) return false
        val signature = (decl.parent as? GoParameters)?.parent as? GoSignature ?: return false
        return signature.parent is GoFunctionOrMethodDeclaration || signature.parent is GoFunctionLit
    }

    /**
     * Turns the type candidates of a parameter name position into `name Type` items (packages stay, so `pkg.` still leads to a qualified type);
     * with a typed prefix the exported types of the imported packages follow at the lowest rank (GoLand offers none; a `ctx` prefix finds
     * `ctx context.Context` this way).
     */
    fun parameterItems(context: GoCompletionContext, types: List<GoCandidate>, prefix: String, out: MutableList<GoCandidate>) {
        val taken = takenNames(context)
        for (c in types) {
            when (c.kind) {
                GoCandidateKind.PACKAGE -> out += c
                GoCandidateKind.TYPE, GoCandidateKind.BUILTIN_TYPE, GoCandidateKind.TYPE_PARAMETER -> {
                    // Dot-imported types are written unqualified like the package's own.
                    val generic = (c.element as? GoTypeSpec)?.typeParameters != null
                    out += parameterItem(c.name, c.name, c.level, c.element, generic, taken)
                }
                else -> {}
            }
        }
        if (prefix.isEmpty()) return
        val semantics = context.semantics
        for (spec in semantics.imports) {
            if (spec.isBlank || spec.isDot) continue
            val pkg = semantics.importedPackage(spec) ?: continue
            val qualifier = semantics.importName(spec)
            for (e in semantics.scopeOf(pkg).allDeclarations()) {
                if (e !is GoTypeSpec || !e.isPublic()) continue
                val name = e.name ?: continue
                out += parameterItem(name, "$qualifier.$name", GoScopeLevel.UNIMPORTED, e, e.typeParameters != null, taken)
            }
        }
    }

    private fun parameterItem(typeName: String, typeText: String, level: Int, element: com.intellij.psi.PsiElement?, generic: Boolean, taken: (String) -> Boolean): GoCandidate {
        val name = GoNameSuggestions.parameterName(typeText, taken)
        return GoCandidate(
            name, GoCandidateKind.PARAMETER, level, element,
            icon = AllIcons.Nodes.Parameter, lookupString = "$name $typeText",
            lookupStrings = listOf(name, typeName, typeText),
            insertHandler = if (generic) TYPE_ARGUMENTS else null,
        )
    }

    /** `name Set[<caret>]` for a generic type. */
    private val TYPE_ARGUMENTS = InsertHandler<LookupElement> { ctx, _ ->
        val tail = ctx.tailOffset
        if (ctx.document.charsSequence.getOrNull(tail) != '[') ctx.document.insertString(tail, "[]")
        ctx.editor.caretModel.moveToOffset(tail + 1)
    }

    /** Names at a variable or parameter name being declared; true when the leaf is one (other providers then have nothing to add). */
    fun variableNames(context: GoCompletionContext, result: CompletionResultSet): Boolean {
        val def = context.leaf.parent
        if (def !is GoVarDefinition && def !is GoParamDefinition) return false
        val names = suggestions(context, def as GoNamedElement)
        val unique = GoNameSuggestions.uniqueAll(names, takenNames(context)).take(MAX_NAMES)
        val out = unique.mapIndexed { i, n -> GoCandidate(n, GoCandidateKind.VARIABLE, GoScopeLevel.LOCAL + i, icon = GoIdeIcons.VARIABLE) }
        GoCompletionContributor.emit(out, context, result)
        return true
    }

    private const val MAX_NAMES = 6

    private fun suggestions(context: GoCompletionContext, def: GoNamedElement): List<String> {
        val semantics = context.semantics
        val type = runCatching { semantics.declarationType(def) }.getOrNull()?.takeIf { it !is GoUnknownType }
        val out = ArrayList<String>()
        var byType = true
        when (val parent = def.parent) {
            is GoRangeClause -> {
                val index = parent.varDefinitionList.indexOf(def)
                val expr = parent.expression
                val over = expr?.let { runCatching { semantics.typeOf(it) }.getOrNull() }?.let { t -> (t as? GoPointerType)?.elem?.underlying() ?: t.underlying() }
                // An index is `i` whatever its int type says (`n` would read as a count).
                if (index == 0 && (over is GoSliceType || over is GoArrayType || over is GoBasicType)) { out += GoNameSuggestions.INDEX_NAMES; byType = false }
                else if (index == 1 || over is GoChanType) wordOf(expr)?.let { (w, _) -> out += GoNameSuggestions.namesForExpression(w, GoNameSuggestions.Source.ELEMENT) }
                // A map key is named by its type only (`k` / `key` come from the key's type).
                if (index == 0 && over is GoMapType) out += GoNameSuggestions.namesForType(render(over.key))
            }
            is GoShortVarDeclaration -> out += expressionNames(parent.varDefinitionList.indexOf(def), parent.varDefinitionList.size, parent.expressionList)
            is GoVarSpec -> out += expressionNames(parent.varDefinitionList.indexOf(def), parent.varDefinitionList.size, parent.expressionList)
            else -> {}
        }
        if (byType && type != null && type !is GoTupleType) out += GoNameSuggestions.namesForType(render(type))
        return out.distinct()
    }

    /** Names from the right-hand side: the expression at the same index, or the single call of a multi-value assignment (only for its first value). */
    private fun expressionNames(index: Int, count: Int, values: List<GoExpression>): List<String> {
        val expr = when {
            values.size == count -> values.getOrNull(index)
            values.size == 1 && index == 0 -> values[0]
            else -> null
        }
        val (word, source) = wordOf(expr) ?: return emptyList()
        return GoNameSuggestions.namesForExpression(word, source)
    }

    /** The word an expression is named by: the callee of a call, the last identifier of a reference, the collection of an index or receive. */
    private fun wordOf(expr: GoExpression?): Pair<String, GoNameSuggestions.Source>? = when (expr) {
        is GoCallExpr -> (expr.expression as? GoReferenceExpression)?.identifier?.text?.let { it to GoNameSuggestions.Source.CALL }
        is GoReferenceExpression -> expr.identifier.text to GoNameSuggestions.Source.VALUE
        is GoParenthesesExpr -> wordOf(PsiTreeUtil.getChildOfType(expr, GoExpression::class.java))
        is GoUnaryExpr -> {
            val inner = wordOf(expr.expression)
            if (expr.arrow != null) inner?.let { it.first to GoNameSuggestions.Source.ELEMENT } else inner
        }
        is GoIndexOrSliceExpr -> {
            val inner = wordOf(expr.expression)
            val slice = expr.node.findChildByType(GoTypes.COLON) != null
            if (slice) inner else inner?.let { it.first to GoNameSuggestions.Source.ELEMENT }
        }
        else -> null
    }?.takeIf { !it.first.contains(CompletionUtil.DUMMY_IDENTIFIER_TRIMMED) }

    private fun render(type: GoType): String = GoTypeRenderer.render(type, qualified = true)

    /** Names a new declaration at the caret should not reuse: locals and parameters in scope, package-level names, imported package names. */
    private fun takenNames(context: GoCompletionContext): (String) -> Boolean {
        val names = HashSet<String>()
        GoScopeCandidates.walkLocals(context.leaf) { e, _ -> e.name?.let(names::add) }
        val file = context.file
        (file.types + file.functions + file.vars + file.consts).forEach { e -> e.name?.let(names::add) }
        context.semantics.imports.forEach { spec -> if (!spec.isBlank && !spec.isDot) names += context.semantics.importName(spec) }
        val packageNames by lazy { context.semantics.packageScope.allDeclarations().mapNotNullTo(HashSet()) { it.name } }
        return { n -> n in names || n in packageNames }
    }
}
