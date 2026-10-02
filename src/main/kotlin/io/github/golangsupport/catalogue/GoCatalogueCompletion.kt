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
import io.github.golangsupport.lang.GoDeclarations
import io.github.golangsupport.lang.GoFile
import io.github.golangsupport.lang.GoIdioms
import io.github.golangsupport.lang.GoImport
import io.github.golangsupport.lang.GoImports
import io.github.golangsupport.lang.GoPrefixMatcher
import io.github.golangsupport.lang.GoStructLiterals
import io.github.golangsupport.lang.GoTextTokens
import io.github.golangsupport.mod.GoModulesService
import io.github.golangsupport.settings.GoSettings
import javax.swing.Icon

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
        if (!GoSettings.getInstance().completionCatalogue || parameters.position.node?.elementType != GoTextTokens.IDENTIFIER) return
        val text = parameters.editor.document.immutableCharSequence
        val typed = GoCompletionOrder.typed(text, parameters.offset)
        if (typed.length < MIN_TYPED || !GoImports.isPackagePlace(text, parameters.offset - typed.length)) return
        val index = GoCatalogueService.getInstance(file.project).current()
        if (index.size == 0) return
        // the names of the package of the file are the business of the language server, and `internal` is not for everyone
        val own = file.virtualFile?.parent?.let { GoModulesService.getInstance(file.project).moduleOf(it)?.importPath(it) }
        val imports = GoDeclarations.scan(text).imports
        val matcher = GoPrefixMatcher(typed)
        val names = result.withPrefixMatcher(matcher)
        // the list is a part of what there is: more letters may bring other names into it
        result.restartCompletionOnAnyPrefixChange()
        // with the Russian layout on the dot is a letter, and `аьеюЗкште` is one word: `fmt.Print`
        val qualifier = matcher.latin.substringBeforeLast('.', "").takeIf { it.isNotEmpty() }
        val wanted = matcher.latin.substringAfterLast('.')
        val found = index.find(wanted, LIMIT, imports.mapTo(HashSet()) { it.path }, qualifier) { it.importPath != own && GoCatalogueScanner.isVisible(it.importPath, own) }
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
            val insertion = GoCatalogueInsertion.of(entry, GoDeclarations.scan(text).imports, literal) ?: return@InsertHandler
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
