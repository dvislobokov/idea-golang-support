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
import io.github.golangsupport.settings.GoSettings

/**
 * What a keyword can begin here, as items of the completion list: `ty` at the top of a file offers `type Name struct {...}`, `fo` in a
 * body offers `for i, x := range xs` for the slices in sight, as GoLand does. Each item is a live template ([Item.template], `$STOP$`
 * for what is typed next, `$END$` for the caret), expanded by the platform, so Tab walks the stops. Works without gopls.
 *
 * The place is read from the text: the declarations of the file say whether the caret is at the top level or in a body, the names
 * declared above the caret in the body say what there is to range over. Statements and expressions are not parsed: only what a
 * regular expression sees in a line.
 */
object GoKeywordTemplates {
    /** [keyword] is what is typed to get the item; [lookups] are other words that find it (`test` for `func TestX`). */
    class Item(val keyword: String, val label: String, val template: String, val typeText: String, val lookups: List<String> = emptyList(), vararg val stops: Pair<String, String>)

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

    /** The context of [offset], or null where a keyword cannot begin: in the middle of a line, in a signature, in a type body. */
    fun contextAt(text: CharSequence, offset: Int, isTestFile: Boolean, structure: GoFileStructure = GoDeclarations.scan(text)): Context? {
        val typedStart = offset - typed(text, offset).length
        val lineStart = lineStart(text, typedStart)
        if (text.subSequence(lineStart, typedStart).isNotBlank()) return null
        val types = structure.declarations.filter { it.kind.isType }.sortedByDescending { it.kind == GoDeclarationKind.STRUCT }.take(MAX_TYPES).map { type ->
            val methods = structure.declarations.filter { it.kind == GoDeclarationKind.METHOD && it.receiver == type.name }
            val pointer = methods.none() && type.kind == GoDeclarationKind.STRUCT || methods.any { text.subSequence(it.range.startOffset, it.nameRange.startOffset).contains('*') }
            TypeInfo(type.name, type.kind == GoDeclarationKind.STRUCT, pointer)
        }
        val wantsMain = structure.isMainPackage && structure.mainFunction == null
        val enclosing = structure.all().firstOrNull { it.range.contains(typedStart) && it.range.startOffset < typedStart }
        if (enclosing == null) return Context(Place.TOP, isTestFile, wantsMain, types)
        val body = enclosing.body ?: return null
        if ((enclosing.kind != GoDeclarationKind.FUNCTION && enclosing.kind != GoDeclarationKind.METHOD) || typedStart <= body.startOffset || typedStart >= body.endOffset) return null
        val above = text.subSequence(maxOf(body.startOffset, lineStart(text, lineStart, MAX_LINES_BACK)), lineStart)
        val signature = enclosing.signature ?: ""
        fun names(regex: Regex): List<String> = (regex.findAll(signature).map { it.groupValues[1] } + regex.findAll(above).map { it.groupValues[1] })
            .filter { it != "_" && it !in KEYWORDS }.distinct().toList().takeLast(MAX_NAMES).reversed()
        return Context(
            Place.BODY, isTestFile, wantsMain, types, inTest = signature.contains("*testing.T"),
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
        if (context.hasContext) add(Item("for", "for { select { case <-ctx.Done(): ... } }", "for {\n\tselect {\n\tcase <-ctx.Done():\n\t\treturn\n\t\$END\$\n\t}\n}", "loop with context", listOf("select")))
        add(Item("for", "for {...}", "for {\n\t\$END\$\n}", "endless"))
        add(Item("if", "if err != nil {...}", "if err != nil {\n\t\$END\$\n}", "error check", listOf("err")))
        add(Item("if", "if _, err := f(); err != nil {...}", "if \$RESULT\$, err := \$CALL\$; err != nil {\n\t\$END\$\n}", "call with error", stops = arrayOf("RESULT" to "_")))
        add(Item("if", "if v, ok := m[k]; ok {...}", "if \$V\$, ok := \$M\$[\$K\$]; ok {\n\t\$END\$\n}", "comma ok", stops = arrayOf("V" to "v")))
        add(Item("if", "if v, ok := x.(T); ok {...}", "if \$V\$, ok := \$X\$.(\$T\$); ok {\n\t\$END\$\n}", "type assertion", stops = arrayOf("V" to "v")))
        add(Item("switch", "switch x {...}", "switch \$X\$ {\ncase \$CASE\$:\n\t\$END\$\n}", "switch"))
        add(Item("switch", "switch v := x.(type) {...}", "switch \$V\$ := \$X\$.(type) {\ncase \$CASE\$:\n\t\$END\$\n}", "type switch", stops = arrayOf("V" to "v")))
        add(Item("select", "select {...}", "select {\ncase \$CASE\$:\n\t\$END\$\n}", "select"))
        if (context.hasContext) add(Item("select", "select { case <-ctx.Done(): ... }", "select {\ncase <-ctx.Done():\n\treturn \$RESULT\$\n\$END\$\n}", "wait with context", stops = arrayOf("RESULT" to "ctx.Err()")))
        add(Item("go", "go func() {...}()", "go func() {\n\t\$END\$\n}()", "goroutine"))
        add(Item("defer", "defer func() {...}()", "defer func() {\n\t\$END\$\n}()", "deferred call"))
        add(Item("chan", "ch := make(chan T)", "\$CH\$ := make(chan \$T\$\$END\$)", "channel", listOf("make"), "CH" to "ch"))
        add(Item("map", "m := make(map[K]V)", "\$M\$ := make(map[\$K\$]\$V\$\$END\$)", "map", listOf("make"), "M" to "m"))
        if (context.inTest) {
            add(Item("t.Run", "t.Run(\"name\", func(t *testing.T) {...})", "t.Run(\"\$NAME\$\", func(t *testing.T) {\n\t\$END\$\n})", "subtest", listOf("Run")))
            add(Item("t.Parallel", "t.Parallel()", "t.Parallel()\$END\$", "parallel", listOf("Parallel")))
            add(Item("t.Cleanup", "t.Cleanup(func() {...})", "t.Cleanup(func() {\n\t\$END\$\n})", "cleanup", listOf("Cleanup")))
            add(Item("t.Helper", "t.Helper()", "t.Helper()\$END\$", "helper", listOf("Helper")))
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
        if (elementType in GoTokenTypes.COMMENTS || elementType in GoTokenTypes.STRINGS) return
        val text = parameters.editor.document.immutableCharSequence
        val context = GoKeywordTemplates.contextAt(text, parameters.offset, file.isTestFile, GoStructure.of(file)) ?: return
        val typed = GoKeywordTemplates.typed(text, parameters.offset)
        if (typed.isEmpty() && !parameters.isExtendedCompletion) return
        val items = result.withPrefixMatcher(GoPrefixMatcher(typed))
        for (item in GoKeywordTemplates.items(context)) {
            val element = LookupElementBuilder.create(item, item.keyword).withLookupStrings(item.lookups).withPresentableText(item.label).withTypeText(item.typeText, true)
                .withIcon(AllIcons.Nodes.Template).withInsertHandler { context, _ -> expand(context, item) }
            items.addElement(PrioritizedLookupElement.withPriority(element, PRIORITY))
        }
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
