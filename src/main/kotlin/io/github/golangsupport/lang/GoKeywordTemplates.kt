package io.github.golangsupport.lang

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.ConstantNode
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import io.github.golangsupport.settings.GoSettings
import com.intellij.psi.PsiElement
import io.github.golangsupport.lang.psi.GoConstDeclaration
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoInterfaceType
import io.github.golangsupport.lang.psi.GoStructType
import io.github.golangsupport.lang.psi.GoTypeDeclaration
import io.github.golangsupport.lang.psi.GoVarDeclaration
import io.github.golangsupport.lang.psi.GoTokenSets
import io.github.golangsupport.lang.psi.GoTypes

/**
 * What a keyword can begin here, as items of the completion list: `ty` at the top of a file offers `type Name struct {...}`, `fo` in a
 * body offers `for i, x := range xs` for the slices in sight, as GoLand does. Each item is a live template ([Item.template], `$STOP$`
 * for what is typed next, `$END$` for the caret), expanded by the platform, so Tab walks the stops. Works without gopls.
 *
 * The place is read from the PSI: the declarations of the file say whether the caret is at the top level or in a body. What there is
 * to range over or to select on comes from the PSI of go-psi too ([GoScopeInputs]); in dumb mode the names declared above the caret are
 * what a regular expression sees in a line.
 */
object GoKeywordTemplates {
    /** [keyword] is what is typed to get the item; [lookups] are other words that find it (`test` for `func TestX`). */
    class Item(val keyword: String, val label: String, val template: String, val typeText: String, val lookups: List<String> = emptyList(), val action: String? = null, vararg val stops: Pair<String, String>)

    /** The item that opens Type from JSON instead of expanding a template. */
    const val JSON = "json"

    /** An interface of the project, for the methods a type of the file lacks: name and signature of each method. */
    class InterfaceInfo(val name: String, val methods: List<Pair<String, String>>)

    /** Where the caret is: only the two places where a statement or a declaration begins. */
    enum class Place { TOP, BODY }

    class Context(
        val place: Place,
        val isTestFile: Boolean,
        /** `package main` without `func main`. */
        val wantsMain: Boolean,
        /** The types of the file, structs first, for `func (r *T) name()`. */
        val types: List<TypeInfo>,
        /** In a body: the function has `t *testing.T`. */
        val inTest: Boolean = false,
        /** In a body: names declared above the caret that a `for` can range over. */
        val slices: List<String> = emptyList(),
        val maps: List<String> = emptyList(),
        val channels: List<String> = emptyList(),
        val hasContext: Boolean = false,
        /** The methods every type of the file has, by the name of the type. */
        val methods: Map<String, Set<String>> = emptyMap(),
        /**
         * In a body, when the PSI told what is in scope ([GoScopeInputs]): the variables with their kinds, innermost first. The `select`
         * items and the channel items are built from it; null in dumb mode, which keeps the fixed `select` items.
         */
        val scope: List<GoScopeInputs.Variable>? = null,
    )

    class TypeInfo(val name: String, val isStruct: Boolean, val pointerReceiver: Boolean)

    private const val MAX_TYPES = 8
    private const val MAX_NAMES = 6
    private const val MAX_LINES_BACK = 60

    /** `xs := []T{`, `xs := make([]T`, `var xs []T`, `xs []T` in a parameter list. */
    private val SLICE = Regex("""\b([a-zA-Z_]\w*)\s*(?::?=\s*(?:make\()?|\s)\[]""")
    private val MAP = Regex("""\b([a-zA-Z_]\w*)\s*(?::?=\s*(?:make\()?|\s)map\[""")
    private val CHANNEL = Regex("""\b([a-zA-Z_]\w*)\s*(?::?=\s*(?:make\()?|\s)(?:<-)?chan\b""")
    private val CONTEXT = Regex("""\bctx\b""")

    /**
     * The context of [offset] in [file] (whose document is [text], committed: completion commits it), or null where a keyword cannot
     * begin: in the middle of a line, in a signature, in a type body. The place and the types of the file come from the PSI; what is in
     * scope from [GoScopeInputs] (variables by their types: `ctx int` is no context, the methods of the types by the semantic layer).
     * Where those cannot tell (dumb mode), the names declared above the caret are what a regular expression sees in a line. Read action.
     */
    fun contextAt(file: GoFile, text: CharSequence, offset: Int): Context? {
        val base = placeAt(file, text, offset) ?: return null
        if (base.place == Place.TOP) {
            val methods = GoScopeInputs.methodsByType(file) ?: return base
            return Context(Place.TOP, base.isTestFile, base.wantsMain, base.types, methods = methods)
        }
        val scope = GoScopeInputs.at(file, offset - typed(text, offset).length) ?: return base
        fun names(kind: GoScopeInputs.Kind, filter: (GoScopeInputs.Variable) -> Boolean = { true }) = scope.filter { it.kind == kind && filter(it) }.map { it.name }.take(MAX_NAMES)
        return Context(
            Place.BODY, base.isTestFile, base.wantsMain, base.types,
            // the templates write `t.Run`: a `*testing.X` that is called otherwise would get a call of a name that is not there
            inTest = scope.any { it.kind == GoScopeInputs.Kind.TESTING && it.name == "t" },
            slices = names(GoScopeInputs.Kind.SLICE), maps = names(GoScopeInputs.Kind.MAP), channels = names(GoScopeInputs.Kind.CHANNEL) { it.receives },
            hasContext = scope.any { it.kind == GoScopeInputs.Kind.CONTEXT }, scope = scope,
        )
    }

    /** The place of [offset] by the PSI, with what the declarations of the file tell; in a body, the names by the lines above. */
    private fun placeAt(file: GoFile, text: CharSequence, offset: Int): Context? {
        val typedStart = offset - typed(text, offset).length
        val lineStart = lineStart(text, typedStart)
        if (text.subSequence(lineStart, typedStart).isNotBlank()) return null
        val methodsOf = file.methods.groupBy { it.receiverTypeName }
        val types = file.types.filter { it.name != null }.sortedByDescending { it.type is GoStructType }.take(MAX_TYPES).map { type ->
            val methods = methodsOf[type.name].orEmpty()
            val struct = type.type is GoStructType
            TypeInfo(type.name!!, struct, methods.none() && struct || methods.any { it.isPointerReceiver })
        }
        val wantsMain = file.packageName == "main" && file.functions.none { it.name == "main" }
        // the package-level declaration that began before the word: none is the top of the file
        var enclosing: PsiElement? = file.findElementAt(typedStart)
        while (enclosing != null && enclosing.parent !is GoFile) enclosing = enclosing.parent
        val isDeclaration = enclosing is GoFunctionOrMethodDeclaration || enclosing is GoTypeDeclaration || enclosing is GoVarDeclaration || enclosing is GoConstDeclaration
        if (enclosing == null || !isDeclaration || enclosing.textRange.startOffset >= typedStart) {
            val methods = file.methods.mapNotNull { m -> m.receiverTypeName?.let { it to m.name } }.groupBy({ it.first }, { it.second }).mapValues { it.value.filterNotNull().toSet() }
            return Context(Place.TOP, file.isTestFile, wantsMain, types, methods = methods)
        }
        val function = enclosing as? GoFunctionOrMethodDeclaration ?: return null
        val body = function.block?.textRange ?: return null
        if (typedStart <= body.startOffset || typedStart >= body.endOffset) return null
        val above = text.subSequence(maxOf(body.startOffset, lineStart(text, lineStart, MAX_LINES_BACK)), lineStart)
        val signature = GoDeclarationInfo.of(function)?.signature.orEmpty()
        fun names(regex: Regex): List<String> = (regex.findAll(signature).map { it.groupValues[1] } + regex.findAll(above).map { it.groupValues[1] })
            .filter { it != "_" && it !in KEYWORDS }.distinct().toList().takeLast(MAX_NAMES).reversed()
        return Context(
            Place.BODY, file.isTestFile, wantsMain, types, inTest = signature.contains("*testing.T"),
            slices = names(SLICE), maps = names(MAP), channels = names(CHANNEL), hasContext = CONTEXT.containsMatchIn(signature) || CONTEXT.containsMatchIn(above),
        )
    }

    fun items(context: Context): List<Item> = if (context.place == Place.TOP) top(context) else body(context)

    private fun top(context: Context): List<Item> = buildList {
        add(Item("type", "type Name struct {...}", "type \$NAME\$ struct {\n\t\$END\$\n}", "struct"))
        add(Item("type", "type Name interface {...}", "type \$NAME\$ interface {\n\t\$END\$\n}", "interface"))
        add(Item("type", "type Name func(...)", "type \$NAME\$ func(\$PARAMS\$) \$END\$", "function type"))
        add(Item("type", "type Name = T", "type \$NAME\$ = \$TYPE\$\$END\$", "alias"))
        add(Item("type", "type (...)", "type (\n\t\$END\$\n)", "group"))
        add(Item("type", "type from JSON...", "", "the clipboard or a dialog", listOf("json"), action = JSON))
        add(Item("func", "func name() {...}", "func \$NAME\$(\$PARAMS\$) \$END\${\n\t\n}", "function"))
        for (type in context.types) {
            val receiver = type.name.first().lowercaseChar()
            add(Item("func", "func ($receiver ${if (type.pointerReceiver) "*" else ""}${type.name}) name() {...}", "func ($receiver ${if (type.pointerReceiver) "*" else ""}${type.name}) \$NAME\$(\$PARAMS\$) \$END\${\n\t\n}", "method"))
        }
        if (context.wantsMain) add(Item("func", "func main() {...}", "func main() {\n\t\$END\$\n}", "entry point", listOf("main")))
        add(Item("func", "func init() {...}", "func init() {\n\t\$END\$\n}", "initializer", listOf("init")))
        if (context.isTestFile) {
            add(Item("func", "func TestName(t *testing.T) {...}", "func Test\$NAME\$(t *testing.T) {\n\t\$END\$\n}", "test", listOf("test", "Test")))
            add(Item("func", "func TestName(t *testing.T) { table }", TABLE_TEST, "table test", listOf("test", "Test", "table")))
            add(Item("func", "func BenchmarkName(b *testing.B) {...}", "func Benchmark\$NAME\$(b *testing.B) {\n\tfor b.Loop() {\n\t\t\$END\$\n\t}\n}", "benchmark", listOf("bench", "Benchmark")))
            add(Item("func", "func FuzzName(f *testing.F) {...}", "func Fuzz\$NAME\$(f *testing.F) {\n\tf.Add(\$SEED\$)\n\tf.Fuzz(func(t *testing.T, \$PARAMS\$) {\n\t\t\$END\$\n\t})\n}", "fuzz test", listOf("fuzz", "Fuzz")))
        }
        add(Item("var", "var name T", "var \$NAME\$ \$TYPE\$\$END\$", "variable"))
        add(Item("var", "var (...)", "var (\n\t\$END\$\n)", "group"))
        add(Item("const", "const Name = value", "const \$NAME\$ = \$VALUE\$\$END\$", "constant"))
        add(Item("const", "const (... = iota)", "const (\n\t\$NAME\$ \$TYPE\$ = iota\n\t\$END\$\n)", "enumeration", listOf("iota")))
        add(Item("const", "const (...)", "const (\n\t\$END\$\n)", "group"))
        add(Item("import", "import (...)", "import (\n\t\"\$END\$\"\n)", "imports"))
    }

    private fun body(context: Context): List<Item> = buildList {
        add(Item("for", "for i := range n {...}", "for \$I\$ := range \$N\$ {\n\t\$END\$\n}", "count", stops = arrayOf("I" to "i", "N" to "n")))
        for (name in context.slices) add(Item("for", "for i, ${element(name)} := range $name {...}", "for \$I\$, \$X\$ := range $name {\n\t\$END\$\n}", "slice", stops = arrayOf("I" to "_", "X" to element(name))))
        for (name in context.maps) add(Item("for", "for k, v := range $name {...}", "for \$K\$, \$V\$ := range $name {\n\t\$END\$\n}", "map", stops = arrayOf("K" to "k", "V" to "v")))
        for (name in context.channels) add(Item("for", "for v := range $name {...}", "for \$V\$ := range $name {\n\t\$END\$\n}", "channel", stops = arrayOf("V" to "v")))
        add(Item("for", "for _, v := range xs {...}", "for \$I\$, \$V\$ := range \$XS\$ {\n\t\$END\$\n}", "range", stops = arrayOf("I" to "_", "V" to "v")))
        add(Item("for", "for i := 0; i < n; i++ {...}", "for \$I\$ := 0; \$I\$ < \$N\$; \$I\$++ {\n\t\$END\$\n}", "counter", stops = arrayOf("I" to "i", "N" to "n")))
        val select = context.scope?.let(::selectCases)
        if (context.scope == null && context.hasContext) add(Item("for", "for { select { case <-ctx.Done(): ... } }", "for {\n\tselect {\n\tcase <-ctx.Done():\n\t\treturn\n\t\$END\$\n\t}\n}", "loop with context", listOf("select")))
        if (select != null) add(Item("for", "for { ${select.label} }", "for {\n\t${select.template.replace("\n", "\n\t")}\n}", "select loop", listOf("select"), stops = select.stops))
        add(Item("for", "for {...}", "for {\n\t\$END\$\n}", "endless"))
        add(Item("if", "if err != nil {...}", "if err != nil {\n\t\$END\$\n}", "error check", listOf("err")))
        add(Item("if", "if _, err := f(); err != nil {...}", "if \$RESULT\$, err := \$CALL\$; err != nil {\n\t\$END\$\n}", "call with error", stops = arrayOf("RESULT" to "_")))
        add(Item("if", "if v, ok := m[k]; ok {...}", "if \$V\$, ok := \$M\$[\$K\$]; ok {\n\t\$END\$\n}", "comma ok", stops = arrayOf("V" to "v")))
        add(Item("if", "if v, ok := x.(T); ok {...}", "if \$V\$, ok := \$X\$.(\$T\$); ok {\n\t\$END\$\n}", "type assertion", stops = arrayOf("V" to "v")))
        add(Item("switch", "switch x {...}", "switch \$X\$ {\ncase \$CASE\$:\n\t\$END\$\n}", "switch"))
        add(Item("switch", "switch v := x.(type) {...}", "switch \$V\$ := \$X\$.(type) {\ncase \$CASE\$:\n\t\$END\$\n}", "type switch", stops = arrayOf("V" to "v")))
        if (context.scope == null || select == null) add(Item("select", "select {...}", "select {\ncase \$CASE\$:\n\t\$END\$\n}", "select"))
        if (context.scope == null && context.hasContext) add(Item("select", "select { case <-ctx.Done(): ... }", "select {\ncase <-ctx.Done():\n\treturn \$RESULT\$\n\$END\$\n}", "wait with context", stops = arrayOf("RESULT" to "ctx.Err()")))
        if (select != null) add(Item("select", select.label, select.template, "cases in scope", stops = select.stops))
        add(Item("go", "go func() {...}()", "go func() {\n\t\$END\$\n}()", "goroutine"))
        add(Item("defer", "defer func() {...}()", "defer func() {\n\t\$END\$\n}()", "deferred call"))
        add(Item("chan", "ch := make(chan T)", "\$CH\$ := make(chan \$T\$\$END\$)", "channel", listOf("make"), stops = arrayOf("CH" to "ch")))
        add(Item("map", "m := make(map[K]V)", "\$M\$ := make(map[\$K\$]\$V\$\$END\$)", "map", listOf("make"), stops = arrayOf("M" to "m")))
        add(Item("chan", "ch := make(chan T, n)", "\$CH\$ := make(chan \$T\$, \$N\$)\$END\$", "buffered channel", listOf("make"), stops = arrayOf("CH" to "ch")))
        add(Item("make", "s := make([]T, 0, n)", "\$S\$ := make([]\$T\$, 0, \$N\$)\$END\$", "slice", listOf("slice"), stops = arrayOf("S" to "s")))
        if (context.inTest) {
            add(Item("t.Run", "t.Run(\"name\", func(t *testing.T) {...})", "t.Run(\"\$NAME\$\", func(t *testing.T) {\n\t\$END\$\n})", "subtest", listOf("Run")))
            add(Item("t.Parallel", "t.Parallel()", "t.Parallel()\$END\$", "parallel", listOf("Parallel")))
            add(Item("t.Cleanup", "t.Cleanup(func() {...})", "t.Cleanup(func() {\n\t\$END\$\n})", "cleanup", listOf("Cleanup")))
            add(Item("t.Helper", "t.Helper()", "t.Helper()\$END\$", "helper", listOf("Helper")))
        }
        // what the PSI knows of the channels in scope: a receive with its ok, and a close for a channel this function made and has not closed
        for (channel in context.scope.orEmpty().filter { it.kind == GoScopeInputs.Kind.CHANNEL }.take(MAX_NAMES)) {
            val name = channel.name
            if (channel.receives) add(Item("ok", "v, ok := <-$name", "\$V\$, ok := <-$name\$END\$", "receive", listOf("recv"), stops = arrayOf("V" to "v")))
            if (channel.madeHere && channel.sends && !channel.closed) {
                add(Item("close", "close($name)", "close($name)\$END\$", "close the channel"))
                add(Item("defer", "defer close($name)", "defer close($name)\$END\$", "close on return", listOf("close")))
            }
        }
    }

    /** The cases a `select` can wait on in [scope], as one template: the context first, then receives, timers, sends. */
    class Select(val label: String, val template: String, val stops: Array<Pair<String, String>>)

    /** Null when nothing in scope can be waited on: the plain `select {...}` is offered then. */
    fun selectCases(scope: List<GoScopeInputs.Variable>): Select? {
        val heads = ArrayList<String>()
        val bodies = ArrayList<String?>()
        val stops = ArrayList<Pair<String, String>>()
        scope.firstOrNull { it.kind == GoScopeInputs.Kind.CONTEXT }?.let { context ->
            heads += "<-${context.name}.Done()"
            bodies += "return \$RESULT\$"
            stops += "RESULT" to "${context.name}.Err()"
        }
        val channels = scope.filter { it.kind == GoScopeInputs.Kind.CHANNEL }.take(MAX_NAMES)
        for (channel in channels.filter { it.receives }) { heads += "v := <-${channel.name}"; bodies += null }
        for (timer in scope.filter { it.kind == GoScopeInputs.Kind.TIMER }.take(MAX_NAMES)) { heads += "<-${timer.name}.C"; bodies += null }
        channels.filter { !it.receives && it.sends }.forEachIndexed { index, channel ->
            // the names of stops are letters ([STOP])
            val value = if (index == 0) "VALUE" else "VALUE_${'A' + index}"
            heads += "${channel.name} <- \$$value\$"
            bodies += null
            stops += value to ""
        }
        if (heads.isEmpty()) return null
        // the caret goes into the first case that has no body of its own, or below the last one
        val caret = bodies.indexOfFirst { it == null }
        val text = StringBuilder("select {\n")
        for (i in heads.indices) {
            text.append("case ").append(heads[i]).append(":\n")
            bodies[i]?.let { text.append('\t').append(it).append('\n') }
            if (i == caret) text.append("\t\$END\$\n")
        }
        if (caret < 0) text.append("\$END\$\n")
        text.append("}")
        val label = "select { " + heads.joinToString("; ") { "case ${it.replace(STOP, "v")}: ..." } + " }"
        return Select(label, text.toString(), stops.toTypedArray())
    }

    /**
     * The methods a type of the file lacks to implement an interface it has begun to implement: at least one method of the interface
     * is there by name, at least one is not. Every type of the file with every interface, so the list stays short by that rule.
     */
    fun interfaceItems(context: Context, interfaces: List<InterfaceInfo>): List<Item> = buildList {
        if (context.place != Place.TOP) return@buildList
        for (type in context.types) {
            val has = context.methods[type.name] ?: continue
            val receiver = "${GoGenerators.receiverName(type.name)} ${if (type.pointerReceiver) "*" else ""}${type.name}"
            for (iface in interfaces) {
                val missing = iface.methods.filter { (name, _) -> name !in has }
                if (missing.size == iface.methods.size || missing.isEmpty()) continue
                for ((name, signature) in missing) {
                    add(Item("func", "func ($receiver) $name$signature {...}", "func ($receiver) $name$signature {\n\t\$END\$\n}", "missing method of ${iface.name}", listOf(name)))
                }
            }
        }
    }

    /** What is typed before the caret: an identifier, or `t.Run` when it is a member of `t`. */
    fun typed(text: CharSequence, offset: Int): String {
        val name = GoCompletionOrder.typed(text, offset)
        val start = offset - name.length
        return if (start >= 2 && text[start - 1] == '.' && text[start - 2] == 't' && (start == 2 || !(text[start - 3].isLetterOrDigit() || text[start - 3] == '_'))) "t.$name" else name
    }

    /** `items` → `item`, `entries` → `entry`, `data` → `v`. */
    fun element(slice: String): String = when {
        slice.endsWith("ies") && slice.length > 4 -> slice.dropLast(3) + "y"
        slice.endsWith("ses") || slice.endsWith("xes") || slice.endsWith("shes") || slice.endsWith("ches") -> slice.dropLast(2)
        slice.endsWith("s") && !slice.endsWith("ss") && slice.length > 1 -> slice.dropLast(1)
        else -> "v"
    }

    /**
     * Whether an item of another contributor with [lookupString] is hidden behind the templates whose lookups are [own]: a bare keyword
     * or a snippet of the native contributor (`if`, `for`, `switch`: nothing declared behind it) says the same as the template of that
     * word; a declaration that happens to share the word (a method named `Run`) stays.
     */
    fun hides(lookupString: String, hasDeclaration: Boolean, own: Set<String>): Boolean = !hasDeclaration && lookupString in own

    /** The names of the stops of a template, in the order of their first appearance; `$END$` is the caret, not a stop. */
    fun stops(template: String): List<String> = STOP.findAll(template).map { it.groupValues[1] }.filter { it != "END" }.distinct().toList()

    private val STOP = Regex("""\$([A-Z_]+)\$""")

    private const val TABLE_TEST = "func Test\$NAME\$(t *testing.T) {\n\ttests := []struct {\n\t\tname string\n\t\t\$FIELDS\$\n\t}{\n\t\t{name: \"\$CASE\$\"},\n\t}\n\tfor _, tt := range tests {\n\t\tt.Run(tt.name, func(t *testing.T) {\n\t\t\t\$END\$\n\t\t})\n\t}\n}"

    private val KEYWORDS = setOf("var", "const", "func", "return", "range", "case", "make", "map", "chan", "type", "struct", "interface", "if", "for", "switch", "select", "go", "defer")

    private fun lineStart(text: CharSequence, offset: Int, linesBack: Int = 0): Int {
        var i = offset.coerceIn(0, text.length)
        var lines = linesBack
        while (i > 0 && (text[i - 1] != '\n' || lines-- > 0)) i--
        return i
    }
}

/** The items of [GoKeywordTemplates] in the completion list, expanded as live templates when chosen. */
class GoKeywordCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile as? GoFile ?: return
        if (!GoSettings.getInstance().completionKeywordTemplates) return
        val elementType = parameters.position.node.elementType
        if (elementType in GoTokenSets.COMMENTS || elementType in GoTokenSets.STRING_LITERALS || elementType == GoTypes.CHAR) return
        val text = parameters.editor.document.immutableCharSequence
        val typed = GoKeywordTemplates.typed(text, parameters.offset)
        if (typed.isEmpty() && !parameters.isExtendedCompletion) return
        // the original file: completion has committed its document, and its PSI is what the scope is read from
        val context = GoKeywordTemplates.contextAt(file, text, parameters.offset) ?: return
        val items = result.withPrefixMatcher(GoPrefixMatcher(typed))
        val missingMethods = if (context.place == GoKeywordTemplates.Place.TOP && context.methods.isNotEmpty())
            GoKeywordTemplates.interfaceItems(context, GoScopeInputs.interfaces(file, context.methods) ?: interfacesOf(file)) else emptyList()
        val own = HashSet<String>()
        for (item in missingMethods + GoKeywordTemplates.items(context)) {
            val element = LookupElementBuilder.create(item, item.keyword).withLookupStrings(item.lookups).withPresentableText(item.label).withTypeText(item.typeText, true)
                .withIcon(if (item.action != null) AllIcons.Actions.Edit else if (item in missingMethods) AllIcons.Gutter.ImplementingMethod else AllIcons.Nodes.Template)
                .withInsertHandler { context, _ -> if (item.action == GoKeywordTemplates.JSON) fromJson(context, file) else expand(context, item) }
            items.addElement(PrioritizedLookupElement.withPriority(element, if (item in missingMethods) PRIORITY + 1 else PRIORITY))
            own += item.keyword
            own += item.lookups
        }
        // the native contributor comes next (plugin.xml: this one is "before goPsiCompletion"): its bare keywords and snippets of the
        // same word would double a template; gopls has no bare keywords, so with it the list is left as it is
        if (own.isNotEmpty() && GoFeatures.native(GoFeature.COMPLETION, file.project)) {
            result.runRemainingContributors(parameters) { r -> if (!GoKeywordTemplates.hides(r.lookupElement.lookupString, r.lookupElement.psiElement != null, own)) result.passResult(r) }
        }
    }

    /** The interfaces of the project with their methods, read again when any file changes: one walk of the files per change, not per list. */
    private fun interfacesOf(file: GoFile): List<GoKeywordTemplates.InterfaceInfo> = CachedValuesManager.getManager(file.project).getCachedValue(file.project) {
        val project = file.project
        val interfaces = ArrayList<GoKeywordTemplates.InterfaceInfo>()
        // the index knows names, not kinds: every Go file of the project, its types from the stubs; the text of a signature only for an interface
        for (virtualFile in FileTypeIndex.getFiles(GoFileType, GlobalSearchScope.projectScope(project))) {
            val psi = PsiManager.getInstance(project).findFile(virtualFile) as? GoFile ?: continue
            for (spec in psi.types) {
                val type = spec.type as? GoInterfaceType ?: continue
                val methods = type.methodSpecList.mapNotNull { method -> method.name?.let { it to GoDeclarationInfo.oneLine(method.signature?.text.orEmpty()) } }
                if (methods.isNotEmpty()) interfaces += GoKeywordTemplates.InterfaceInfo(spec.name ?: continue, methods)
            }
        }
        CachedValueProvider.Result.create(interfaces.sortedBy { it.name }, PsiModificationTracker.MODIFICATION_COUNT)
    }

    /** The keyword goes away; the dialog opens after the lookup has closed, outside its write command. */
    private fun fromJson(context: InsertionContext, file: GoFile) {
        context.document.deleteString(context.startOffset, context.tailOffset)
        val at = context.startOffset
        ApplicationManager.getApplication().invokeLater({ if (!context.editor.isDisposed) GoTypeFromJsonAction.generate(context.project, context.editor, file, at) }, ModalityState.defaultModalityState())
    }

    private fun expand(context: InsertionContext, item: GoKeywordTemplates.Item) {
        // the platform has written the keyword; the template is written in its place
        context.document.deleteString(context.startOffset, context.tailOffset)
        context.editor.caretModel.moveToOffset(context.startOffset)
        val manager = TemplateManager.getInstance(context.project)
        val template = manager.createTemplate("go.keyword." + item.keyword, "go", item.template)
        template.isToReformat = false
        // every stop is declared, in the order of the text: that is the order Tab walks them
        val defaults = item.stops.toMap()
        for (name in GoKeywordTemplates.stops(item.template)) template.addVariable(name, ConstantNode(defaults[name] ?: ""), ConstantNode(defaults[name] ?: ""), true)
        manager.startTemplate(context.editor, template)
    }

    private companion object {
        /** Under the values of `return`, above the fuzzy matches of gopls. */
        const val PRIORITY = 900.0
    }
}
