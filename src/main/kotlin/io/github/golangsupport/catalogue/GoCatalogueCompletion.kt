package io.github.golangsupport.catalogue

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
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
        if (typed.length < MIN_TYPED || !GoImports.isPackagePlace(text, parameters.offset - typed.length)) return
        val index = GoCatalogueService.getInstance(file.project).current()
        if (index.size == 0) return
        // the names of the package of the file are the business of the language server, and `internal` is not for everyone
        val own = file.virtualFile?.parent?.let { GoModulesService.getInstance(file.project).moduleOf(it)?.importPath(it) }
        val imports = GoImports.importsOf(text)
        val matcher = GoPrefixMatcher(typed)
        val names = result.withPrefixMatcher(matcher)
        // the list is a part of what there is: more letters may bring other names into it
        result.restartCompletionOnAnyPrefixChange()
        // with the Russian layout on the dot is a letter, and `аьеюЗкште` is one word: `fmt.Print`
        val qualifier = matcher.latin.substringBeforeLast('.', "").takeIf { it.isNotEmpty() }
        val wanted = matcher.latin.substringAfterLast('.')
        val native = GoFeatures.native(GoFeature.COMPLETION, file.project)
        val candidates = index.find(wanted, if (native) Int.MAX_VALUE else LIMIT, imports.mapTo(HashSet()) { it.path }, qualifier) { it.importPath != own && GoCatalogueScanner.isVisible(it.importPath, own) }
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
            val item = LookupElementBuilder.create(entry, entry.symbol.name)
                .withLookupString(entry.pack.name + "." + entry.symbol.name)
                .withPresentableText(insertion.text.removeSuffix("()"))
                .withTailText(tail(entry), true)
                .withTypeText(entry.pack.importPath, true)
                .withIcon(icon(entry.symbol.kind))
                .withInsertHandler(INSERT)
            // below what the file itself has under the same beginning, above what gopls has matched by letters in the middle
            names.addElement(PrioritizedLookupElement.withPriority(item, GoCompletionOrder.priority(wanted, entry.symbol.name) - BELOW - rank * STEP))
        }
    }

    private fun tail(entry: GoSymbolIndex.Entry): String? = when (entry.symbol.kind) {
        GoDeclarationKind.FUNCTION -> entry.symbol.signature
        GoDeclarationKind.STRUCT, GoDeclarationKind.INTERFACE -> " " + entry.symbol.kind.title
        else -> entry.symbol.signature?.let { " $it" }
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

        val INSERT = InsertHandler<LookupElement> { context, item ->
            val entry = item.`object` as? GoSymbolIndex.Entry ?: return@InsertHandler
            val document = context.document
            val text = document.immutableCharSequence
            // not where a type is expected (`var c http.Client`), and not when the braces are there
            val literal = GoSettings.getInstance().completionStructBraces && GoStructLiterals.isValuePlace(text, context.startOffset) &&
                text.getOrNull(context.tailOffset) != '{' && text.getOrNull(context.tailOffset) != '('
            val insertion = GoCatalogueInsertion.of(entry, GoImports.importsOf(text), literal) ?: return@InsertHandler
            document.replaceString(context.startOffset, context.tailOffset, insertion.text)
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
