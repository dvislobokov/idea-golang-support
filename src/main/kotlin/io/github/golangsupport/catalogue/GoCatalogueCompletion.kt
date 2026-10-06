package io.github.golangsupport.catalogue

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import io.github.golangsupport.ide.GoImportExclusions
import io.github.golangsupport.ide.completion.GoCompletionContext
import io.github.golangsupport.ide.completion.GoImportPaths
import io.github.golangsupport.lang.GoCompletionOrder
import io.github.golangsupport.lang.GoDeclarationKind
import io.github.golangsupport.lang.GoFeature
import io.github.golangsupport.lang.GoFeatures
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.GoIdioms
import io.github.golangsupport.lang.GoImport
import io.github.golangsupport.lang.GoImports
import io.github.golangsupport.lang.GoNames
import io.github.golangsupport.lang.GoPrefixMatcher
import io.github.golangsupport.lang.GoStructLiterals
import io.github.golangsupport.mod.GoModulesService
import io.github.golangsupport.settings.GoSettings
import javax.swing.Icon
import io.github.golangsupport.lang.psi.GoTypes

/** How a symbol of the catalogue is written into a file, given what the file imports; pure, for the tests. */
object GoCatalogueInsertion {
    /** What is written and how far from its end the caret is left (inside the brackets of a call that takes arguments). */
    class Text(val text: String, val caretFromEnd: Int, val importPath: String?)

    /**
     * Null when the symbol cannot be written: the name of its package is taken in the file by another import. A package imported under
     * another name is called by that name, a package imported with a dot is not called at all.
     */
    fun of(entry: GoSymbolIndex.Entry, imports: List<GoImport>, literal: Boolean = false): Text? {
        val imported = imports.firstOrNull { it.path == entry.pack.importPath && it.alias != "_" }
        val qualifier = when {
            imported == null -> entry.pack.name
            imported.alias == "." -> ""
            else -> imported.alias ?: entry.pack.name
        }
        if (imported == null && imports.any { GoImports.nameOf(it) == qualifier }) return null
        val name = (if (qualifier.isEmpty()) "" else "$qualifier.") + entry.symbol.name
        val importPath = entry.pack.importPath.takeIf { imported == null }
        // a type where a value is expected is the beginning of a literal: `c := http.Client{}`, caret between the braces (asked by the user)
        if (literal && hasLiteral(entry.symbol)) return Text("$name{}", 1, importPath)
        if (entry.symbol.kind != GoDeclarationKind.FUNCTION) return Text(name, 0, importPath)
        val takesArguments = entry.symbol.signature?.let { GoIdioms.splitSignature(it).first.isNotEmpty() } ?: true
        return Text("$name()", if (takesArguments) 1 else 0, importPath)
    }

    /**
     * What the catalogue offers of [found]: the packages of the project belong to the built-in completion when it serves completion
     * ([nativeCompletion]: it types them and adds the import itself), so they are left out then; the standard library and the modules stay.
     */
    fun offered(found: List<GoSymbolIndex.Entry>, nativeCompletion: Boolean, limit: Int): List<GoSymbolIndex.Entry> =
        found.asSequence().filter { !(nativeCompletion && it.project) }.take(limit).toList()

    /**
     * `json.` typed before `json` is imported: the identifier before the dot at [start] when it is a bare name (not `a.json.`); the
     * catalogue then lists the packages of that name the file does not import (`encoding/json` and `encoding/json/v2`).
     */
    fun packageQualifier(text: CharSequence, start: Int): String? {
        if (start <= 0 || text.getOrNull(start - 1) != '.') return null
        var begin = start - 1
        while (begin > 0 && (text[begin - 1].isLetterOrDigit() || text[begin - 1] == '_')) begin--
        if (begin == start - 1 || text[begin].isDigit() || text.getOrNull(begin - 1) == '.') return null
        return text.subSequence(begin, start - 1).toString()
    }

    /**
     * Where a row of [packageQualifier] begins: the qualifier the user typed is written again by the insertion, so the text to replace starts at
     * it. [start] itself when the text before it is not `name.`.
     */
    fun replacedFrom(text: CharSequence, start: Int, packageName: String): Int {
        val from = start - packageName.length - 1
        if (from < 0 || text.subSequence(from, start).toString() != "$packageName.") return start
        val before = text.getOrNull(from - 1)
        return if (before != null && (before.isLetterOrDigit() || before == '_' || before == '.')) start else from
    }

    /** What can stand where a type is expected. */
    fun isType(symbol: GoSymbol): Boolean = symbol.kind == GoDeclarationKind.STRUCT || symbol.kind == GoDeclarationKind.TYPE || symbol.kind == GoDeclarationKind.INTERFACE

    /** A struct, and a type that is a map, a slice or an array: `http.Header{}`. An interface and a number have no literal. */
    fun hasLiteral(symbol: GoSymbol): Boolean = symbol.kind == GoDeclarationKind.STRUCT ||
        symbol.kind == GoDeclarationKind.TYPE && symbol.signature?.trim()?.let { it.startsWith("map[") || it.startsWith("[") } == true
}

/**
 * The functions and the types of packages by their own names, imported or not: `Printl` gives `fmt.Println`, `NewReq` gives
 * `http.NewRequest`, and choosing one writes the package before it and the import. gopls completes after the dot of a package
 * (`http.Cl`), not a bare name (checked with tools/gopls/completion.py). Works without gopls: the names are of [GoCatalogueService].
 */
class GoCatalogueCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile as? GoFile ?: return
        if (!GoSettings.getInstance().completionCatalogue || parameters.position.node?.elementType != GoTypes.IDENTIFIER) return
        val text = parameters.editor.document.immutableCharSequence
        val typed = GoCompletionOrder.typed(text, parameters.offset)
        val start = parameters.offset - typed.length
        if (parameters.completionType == CompletionType.SMART && smart(parameters, file, text, typed, result)) return
        if (parameters.completionType == CompletionType.BASIC && unimportedPackage(parameters, file, text, typed, result)) return
        if (typed.length < MIN_TYPED || !GoImports.isPackagePlace(text, start)) return
        val index = GoCatalogueService.getInstance(file.project).current()
        if (index.size == 0) return
        // the names of the package of the file are the business of the language server, and `internal` is not for everyone
        val own = ownPackage(file)
        val imports = GoImports.importsOf(text)
        val matcher = GoPrefixMatcher(typed)
        val names = result.withPrefixMatcher(matcher)
        // the list is a part of what there is: more letters may bring other names into it
        result.restartCompletionOnAnyPrefixChange()
        // with the Russian layout on the dot is a letter, and `аьеюЗкште` is one word: `fmt.Print`
        val qualifier = matcher.latin.substringBeforeLast('.', "").takeIf { it.isNotEmpty() }
        val wanted = matcher.latin.substringAfterLast('.')
        val native = GoFeatures.native(GoFeature.COMPLETION, file.project)
        val imported = imports.mapTo(HashSet()) { it.path }
        // Exclude from import and completion (Settings | Editor | General | Auto Import): an imported package stays, it is the user's already
        val excluded = GoSettings.getInstance().importExcluded
        val candidates = index.find(wanted, if (native) Int.MAX_VALUE else LIMIT, imported, qualifier) { visible(it, own, imported, excluded) }
        val typePlace = GoStructLiterals.isTypePlace(text, parameters.offset - typed.length)
        val found = GoCatalogueInsertion.offered(if (typePlace) candidates.filter { GoCatalogueInsertion.isType(it.symbol) } else candidates, native, LIMIT)
        // while the IDE indexes the built-in completion is off and gopls may not answer yet: the predeclared types are not lost then
        if (typePlace && !native && GoFeatures.configuredNative(GoFeature.COMPLETION) && qualifier == null) {
            for (name in GoNames.BUILTIN_TYPES + TYPE_KEYWORDS) {
                if (!matcher.prefixMatches(name)) continue
                val item = LookupElementBuilder.create(name).withIcon(if (name in TYPE_KEYWORDS) null else AllIcons.Nodes.Type).withBoldness(name in TYPE_KEYWORDS)
                names.addElement(PrioritizedLookupElement.withPriority(item, GoCompletionOrder.priority(wanted, name)))
            }
        }
        found.forEachIndexed { rank, entry ->
            val insertion = GoCatalogueInsertion.of(entry, imports) ?: return@forEachIndexed
            val item = row(entry, insertion)
            // below what the file itself has under the same beginning, above what gopls has matched by letters in the middle
            names.addElement(PrioritizedLookupElement.withPriority(item, GoCompletionOrder.priority(wanted, entry.symbol.name) - BELOW - rank * STEP))
        }
    }

    private fun ownPackage(file: GoFile): String? = file.virtualFile?.parent?.let { GoModulesService.getInstance(file.project).moduleOf(it)?.importPath(it) }

    private fun visible(pack: GoPackageSymbols, own: String?, imported: Set<String>, excluded: List<String>): Boolean =
        pack.importPath != own && GoCatalogueScanner.isVisible(pack.importPath, own) && (pack.importPath in imported || !GoImportExclusions.excluded(pack.importPath, excluded))

    private fun row(entry: GoSymbolIndex.Entry, insertion: GoCatalogueInsertion.Text): LookupElementBuilder {
        val (tail, type) = texts(entry.symbol.kind, entry.symbol.signature, entry.pack.importPath)
        return LookupElementBuilder.create(entry, entry.symbol.name)
            .withLookupString(entry.pack.name + "." + entry.symbol.name)
            .withPresentableText(insertion.text.removeSuffix("()").removeSuffix("{}"))
            .withTailText(tail, true)
            .withTypeText(type, true)
            .withIcon(icon(entry.symbol.kind))
            .withInsertHandler(INSERT)
    }

    /**
     * Smart completion where a type is expected (`total = `, `var i int = `, `ch <- `, `return `, an argument): the functions, variables and
     * constants of the standard library and of the modules required directly whose value has that type ([GoCatalogueSmart]), as
     * `strings.Count(…)` rows that add the import; below what the file and its package have. False when no type is expected (the basic list then).
     */
    private fun smart(parameters: CompletionParameters, file: GoFile, text: CharSequence, typed: String, result: CompletionResultSet): Boolean {
        if (!GoImports.isPackagePlace(text, parameters.offset - typed.length)) return false
        val context = GoCompletionContext.of(parameters) ?: return false
        if (!context.isExpression) return false
        val expected = context.semantics.expectedType ?: return false
        val keys = GoCatalogueSmart.expectedKeys(expected)
        if (keys.isEmpty()) return true
        val index = GoCatalogueService.getInstance(file.project).current()
        if (index.size == 0) return true
        val own = ownPackage(file)
        val imports = GoImports.importsOf(text)
        val imported = imports.mapTo(HashSet()) { it.path }
        val excluded = GoSettings.getInstance().importExcluded
        val matcher = GoPrefixMatcher(typed)
        val found = index.fitting(keys, SMART_LIMIT, SMART_PER_PACKAGE, imported) {
            !it.project && !it.indirect && visible(it.pack, own, imported, excluded) &&
                (typed.isEmpty() || matcher.prefixMatches(it.symbol.name) || matcher.prefixMatches(it.pack.name + "." + it.symbol.name))
        }
        val names = result.withPrefixMatcher(matcher)
        found.forEachIndexed { rank, entry ->
            val insertion = GoCatalogueInsertion.of(entry, imports) ?: return@forEachIndexed
            val base = if (entry.pack.importPath in imported) SMART_IMPORTED else SMART_OTHER
            names.addElement(PrioritizedLookupElement.withPriority(row(entry, insertion), GoCompletionOrder.priority(typed, entry.symbol.name) + base - rank * STEP))
        }
        return true
    }

    /**
     * `json.` / `json.Mar` with no `json` imported: the members of every package of that name the catalogue has, each row showing its path
     * (`encoding/json`, `encoding/json/v2`), and choosing one imports that one. The package the built-in completion lists itself for that
     * name ([GoImportPaths], its first) is left to it. False when the place is not that.
     */
    private fun unimportedPackage(parameters: CompletionParameters, file: GoFile, text: CharSequence, typed: String, result: CompletionResultSet): Boolean {
        val start = parameters.offset - typed.length
        val qualifier = GoCatalogueInsertion.packageQualifier(text, start) ?: return false
        val imports = GoImports.importsOf(text)
        if (imports.any { GoImports.nameOf(it) == qualifier }) return false
        val index = GoCatalogueService.getInstance(file.project).current()
        if (index.size == 0) return false
        val own = ownPackage(file)
        val excluded = GoSettings.getInstance().importExcluded
        val native = GoFeatures.native(GoFeature.COMPLETION, file.project)
        val nativePick = if (native) GoImportPaths.all(file.project, file.virtualFile).firstOrNull { it.name == qualifier }?.path else null
        val found = index.find(typed, Int.MAX_VALUE, emptySet(), qualifier) { it.name == qualifier && it.importPath != nativePick && visible(it, own, emptySet(), excluded) }
            .filter { !(native && it.project) }.sortedWith(compareBy<GoSymbolIndex.Entry> { it.origin }.thenBy { it.pack.importPath.length }.thenBy { it.pack.importPath })
            .take(LIMIT * 4)
        if (found.isEmpty()) return false
        result.restartCompletionOnAnyPrefixChange()
        found.forEachIndexed { rank, entry ->
            val insertion = GoCatalogueInsertion.of(entry, imports) ?: return@forEachIndexed
            result.addElement(PrioritizedLookupElement.withPriority(row(entry, insertion), GoCompletionOrder.priority(typed, entry.symbol.name) - BELOW - rank * STEP))
        }
        return false
    }

    /**
     * Tail and type of a catalogue row as GoLand writes a member of another package (dump probes 8, 11) and as the built-in rows do
     * (`GoLookupElementFactory.foreignTail`): `(v any) encoding/json` + `([]byte, error)` for a function, ` encoding/json` for a type,
     * ` strings` + `int` for a variable or constant (its type). The signature text is the catalogue's (`[T any](x T) (int, error)`).
     */
    fun texts(kind: GoDeclarationKind, signature: String?, importPath: String): Pair<String, String?> = when (kind) {
        GoDeclarationKind.FUNCTION, GoDeclarationKind.METHOD -> {
            val text = signature?.trim().orEmpty()
            var end = 0
            if (text.startsWith("[")) end = closing(text, 0) + 1
            if (end < text.length && text[end] == '(') end = closing(text, end) + 1
            text.substring(0, end.coerceIn(0, text.length)) + " $importPath" to text.substring(end.coerceIn(0, text.length)).trim().ifEmpty { null }
        }
        GoDeclarationKind.CONST, GoDeclarationKind.VAR -> " $importPath" to signature?.trim()?.ifEmpty { null }
        else -> " $importPath" to null
    }

    /** The index of the bracket closing the one at [open] (the end of [text] when unbalanced). */
    private fun closing(text: String, open: Int): Int {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (--depth == 0) return i
            }
        }
        return text.length - 1
    }

    private fun icon(kind: GoDeclarationKind): Icon = when (kind) {
        GoDeclarationKind.FUNCTION -> AllIcons.Nodes.Function
        GoDeclarationKind.STRUCT, GoDeclarationKind.TYPE -> AllIcons.Nodes.Class
        GoDeclarationKind.INTERFACE -> AllIcons.Nodes.Interface
        GoDeclarationKind.CONST -> AllIcons.Nodes.Constant
        else -> AllIcons.Nodes.Variable
    }

    private companion object {
        const val MIN_TYPED = 2
        val TYPE_KEYWORDS = listOf("struct", "interface", "map", "chan", "func")
        const val LIMIT = 40
        const val BELOW = 0.4
        const val STEP = 0.001

        /** Smart rows: no more than this many of one package and in all (GoLand's list for `total = ` holds a dozen of `strings`). */
        const val SMART_PER_PACKAGE = 10
        const val SMART_LIMIT = 30
        /** Below a value of the file that fits only by assignability (0.5 in go-psi's scheme), a package the file imports first. */
        const val SMART_IMPORTED = 0.3
        const val SMART_OTHER = 0.2

        val INSERT = InsertHandler<LookupElement> { context, item ->
            val entry = item.`object` as? GoSymbolIndex.Entry ?: return@InsertHandler
            val document = context.document
            val text = document.immutableCharSequence
            // not where a type is expected (`var c http.Client`), and not when the braces are there
            val literal = GoSettings.getInstance().completionStructBraces && GoStructLiterals.isValuePlace(text, context.startOffset) &&
                text.getOrNull(context.tailOffset) != '{' && text.getOrNull(context.tailOffset) != '('
            val insertion = GoCatalogueInsertion.of(entry, GoImports.importsOf(text), literal) ?: return@InsertHandler
            // `json.Mar` of a package not imported: the qualifier typed is a part of what is written
            val from = if (insertion.importPath != null) GoCatalogueInsertion.replacedFrom(text, context.startOffset, entry.pack.name) else context.startOffset
            document.replaceString(from, context.tailOffset, insertion.text)
            // the caret first: the import is written above it and moves it along
            context.editor.caretModel.moveToOffset(context.tailOffset - insertion.caretFromEnd)
            insertion.importPath?.let { path -> GoImports.add(document.immutableCharSequence, path)?.let { document.insertString(it.offset, it.text) } }
            context.commitDocument()
            if (insertion.caretFromEnd > 0 && insertion.text.endsWith(")") && GoSettings.getInstance().completionArguments) {
                val editor = context.editor
                val project = context.project
                context.setLaterRunnable {
                    if (project.isDisposed || editor.isDisposed) return@setLaterRunnable
                    AutoPopupController.getInstance(project).autoPopupParameterInfo(editor, null)
                    AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
                }
            }
        }
    }
}
