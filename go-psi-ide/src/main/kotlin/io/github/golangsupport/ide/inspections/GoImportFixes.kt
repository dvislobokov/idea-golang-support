package io.github.golangsupport.ide.inspections

import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.intention.HighPriorityAction
import com.intellij.codeInspection.HintAction
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.GoIdeOptions
import io.github.golangsupport.ide.completion.GoImportInserter
import io.github.golangsupport.ide.completion.GoImportPaths
import io.github.golangsupport.ide.formatter.GoCodeStyleSettings
import io.github.golangsupport.ide.formatter.GoImportGroups
import io.github.golangsupport.ide.formatter.GoImportSorter
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoImportDeclaration
import io.github.golangsupport.lang.psi.GoImportList
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeReferenceExpression
import io.github.golangsupport.semantic.scope.GoPackageModel
import io.github.golangsupport.semantic.scope.GoScopes

/** Document helpers shared by the import fixes and the import optimizer. */
internal object GoImportEdits {

    /** The committed document of [file], unblocked for text edits. */
    fun document(file: PsiFile): Document? {
        val manager = PsiDocumentManager.getInstance(file.project)
        val document = manager.getDocument(file) ?: return null
        manager.doPostponedOperationsAndUnblockDocument(document)
        manager.commitDocument(document)
        return document
    }

    fun commit(file: PsiFile, document: Document) = PsiDocumentManager.getInstance(file.project).commitDocument(document)

    /** `import "path"` into its goimports group ([GoImportInserter]); write action. */
    fun addImport(file: GoFile, path: String) {
        val document = document(file) ?: return
        GoImportInserter.addImport(file, document, path)
        commit(file, document)
    }

    /**
     * Removes [specs] from [document]: a spec alone on its line removes the line; a declaration
     * whose specs are all removed (or an unparenthesised one) is removed with its line, and a
     * blank line left between two blank lines is collapsed like gofmt does.
     */
    fun removeSpecs(document: Document, specs: Collection<GoImportSpec>) {
        val text = document.charsSequence
        val ranges = ArrayList<TextRange>()
        for ((decl, removed) in specs.groupBy { it.parent as? GoImportDeclaration }) {
            if (decl == null) continue
            if (decl.lparen == null || decl.importSpecList.all { it in removed }) {
                ranges += collapseBlankLines(text, wholeLines(text, decl.textRange))
            } else {
                for (spec in removed) {
                    val lines = wholeLines(text, spec.textRange)
                    ranges += if (aloneOnLines(text, spec.textRange)) lines else spec.textRange
                }
            }
        }
        for (r in ranges.sortedByDescending { it.startOffset }) document.deleteString(r.startOffset, r.endOffset)
    }

    /** [range] widened to whole lines, including the trailing line break. */
    private fun wholeLines(text: CharSequence, range: TextRange): TextRange {
        val start = text.lastIndexOf('\n', range.startOffset - 1) + 1
        var end = range.endOffset
        while (end < text.length && text[end] != '\n') end++
        if (end < text.length) end++
        return TextRange(start, end)
    }

    private fun aloneOnLines(text: CharSequence, range: TextRange): Boolean {
        val lines = wholeLines(text, range)
        val before = text.subSequence(lines.startOffset, range.startOffset)
        val after = text.subSequence(range.endOffset, lines.endOffset).toString().trim()
        return before.isBlank() && (after.isEmpty() || after.startsWith("//"))
    }

    private fun collapseBlankLines(text: CharSequence, lines: TextRange): TextRange {
        val blankBefore = lines.startOffset >= 2 && text[lines.startOffset - 1] == '\n' && text[lines.startOffset - 2] == '\n'
        val blankAfter = lines.endOffset < text.length && text[lines.endOffset] == '\n'
        return if ((blankBefore || lines.startOffset == 0) && blankAfter) TextRange(lines.startOffset, lines.endOffset + 1) else lines
    }

    private fun CharSequence.lastIndexOf(c: Char, from: Int): Int {
        var i = minOf(from, length - 1)
        while (i >= 0 && this[i] != c) i--
        return i
    }

    /**
     * Removes the unused imports reported by the checker, then lays the rest out as Code Style | Go | Imports says ([GoCodeStyleSettings]):
     * redundant aliases dropped, the declarations merged into one, and with goimports sorting each parenthesised declaration regrouped like
     * goimports ([GoImportGroups]; a declaration it cannot regroup, several specs on a line, is still sorted like gofmt), with gofmt
     * sorting only sorted inside its blank-line groups, with none left in its order.
     */
    fun optimize(file: GoFile, unused: List<TextRange>) {
        val document = document(file) ?: return
        val style = GoCodeStyleSettings.of(file)
        val specs = file.imports.filter { it.textRange in unused }
        if (specs.isNotEmpty()) {
            removeSpecs(document, specs)
            commit(file, document)
        }
        if (style.IMPORT_REMOVE_REDUNDANT_ALIASES) removeRedundantAliases(file, document)
        if (style.IMPORT_ONE_DECLARATION) merge(file, document)
        if (style.IMPORT_SORTING == GoCodeStyleSettings.SORT_GOIMPORTS) regroup(file, document, style)
        if (style.IMPORT_SORTING != GoCodeStyleSettings.SORT_NONE) GoImportSorter().process(file.node, file.textRange)
        PsiDocumentManager.getInstance(file.project).doPostponedOperationsAndUnblockDocument(document)
    }

    private fun declarations(file: GoFile): List<GoImportDeclaration> =
        PsiTreeUtil.getChildOfType(file, GoImportList::class.java)?.importDeclarationList.orEmpty()

    /** `fmt "fmt"` -> `"fmt"`: the alias is the name of the package (or, unresolved, the last element of the path). */
    private fun removeRedundantAliases(file: GoFile, document: Document) {
        val model = GoPackageModel.getInstance(file.project)
        val edits = file.imports.mapNotNull { spec ->
            val path = spec.path
            val name = model.resolveImport(path, file)?.name ?: GoScopes.defaultImportName(path)
            GoImportGroups.withoutRedundantAlias(spec.text, name)?.let { spec.textRange to it }
        }
        if (edits.isEmpty()) return
        for ((range, text) in edits.sortedByDescending { it.first.startOffset }) document.replaceString(range.startOffset, range.endOffset, text)
        commit(file, document)
    }

    /** Every declaration but `import "C"` (its doc comment is the cgo preamble) into the first one. */
    private fun merge(file: GoFile, document: Document) {
        val mergeable = declarations(file).filter { decl -> decl.importSpecList.none { it.path == "C" } }
        val merged = GoImportGroups.merge(mergeable.map { it.text }) ?: return
        val text = document.charsSequence
        for (decl in mergeable.drop(1).sortedByDescending { it.textRange.startOffset }) {
            val start = decl.textRange.startOffset.let { s -> var i = s; while (i > 0 && text[i - 1] != '\n') i--; i }
            var end = decl.textRange.endOffset
            while (end < text.length && text[end] != '\n') end++
            if (end < text.length) end++
            if (end < text.length && text[end] == '\n' && start >= 2 && text[start - 1] == '\n' && text[start - 2] == '\n') end++
            document.deleteString(start, end)
        }
        val first = mergeable.first().textRange
        document.replaceString(first.startOffset, first.endOffset, merged)
        commit(file, document)
    }

    private fun regroup(file: GoFile, document: Document, style: GoCodeStyleSettings) {
        val declarations = declarations(file).filter { it.lparen != null && it.rparen != null }
        if (declarations.isEmpty()) return
        val locals = GoCodeStyleSettings.localGroup(file)
        val edits = declarations.mapNotNull { decl -> GoImportGroups.regroup(decl.text, locals, style.IMPORT_GROUP_STDLIB)?.let { decl.textRange to it } }
        if (edits.isEmpty()) return
        for ((range, text) in edits.sortedByDescending { it.first.startOffset }) document.replaceString(range.startOffset, range.endOffset, text)
        commit(file, document)
    }

    fun unusedImportRanges(file: GoFile): List<TextRange> =
        GoDiagnosticsCache.diagnostics(file).filter { it.code in GoDiagnosticClasses.UNUSED_IMPORT }.map { it.range }
}

/**
 * `strings.ToUpper(s)` without `import "strings"`: adds the import of a standard-library or
 * module package whose name is the qualifier and which exports the selected name. One fix per
 * candidate path (`rand` -> `crypto/rand`, `math/rand`), standard library first.
 */
class GoAddImportFix(element: PsiElement, private val path: String, private val all: List<String> = listOf(path)) :
    LocalQuickFixAndIntentionActionOnPsiElement(element), HighPriorityAction, HintAction {

    override fun getFamilyName(): String = "Add import"

    override fun getText(): String = "Import \"$path\""

    override fun invoke(project: Project, file: PsiFile, editor: Editor?, startElement: PsiElement, endElement: PsiElement) {
        val go = file as? GoFile ?: return
        GoImportEdits.addImport(go, path)
    }

    /**
     * "Show import popup": the question hint of the platform (`ShowAutoImportPass` asks the first fix of an unresolved name), with every
     * package that fits; one is imported on Alt+Enter, several are offered in a list.
     */
    override fun showHint(editor: Editor): Boolean {
        val element = startElement ?: return false
        if (!GoIdeOptions.getInstance().importShowPopup || HintManager.getInstance().hasShownHintsThatWillHideByOtherHint(true)) return false
        val file = element.containingFile as? GoFile ?: return false
        val shortcut = KeymapUtil.getFirstKeyboardShortcutText(ActionManager.getInstance().getAction(IdeActions.ACTION_SHOW_INTENTION_ACTIONS))
        val message = if (all.size == 1) "Import \"${all[0]}\"? $shortcut" else "Import one of ${all.size} packages? $shortcut"
        val range = element.textRange
        HintManager.getInstance().showQuestionHint(editor, message, range.startOffset, range.endOffset) {
            if (all.size == 1) importLater(file, all[0])
            else JBPopupFactory.getInstance().createPopupChooserBuilder(all).setTitle("Import Package").setItemChosenCallback { importLater(file, it) }
                .createPopup().showInBestPositionFor(editor)
            true
        }
        return true
    }

    private fun importLater(file: GoFile, path: String) {
        if (!file.isValid) return
        WriteCommandAction.runWriteCommandAction(file.project, "Add Import", null, { GoImportEdits.addImport(file, path) }, file)
    }

    companion object {
        private const val MAX_FIXES = 5

        fun forUndefined(file: GoFile, range: TextRange): List<LocalQuickFix> {
            val (ref, paths) = candidates(file, range, MAX_FIXES) ?: return emptyList()
            return paths.map { GoAddImportFix(ref, it, paths) }
        }

        /**
         * The unresolved package qualifier at [range] (`strings` of `strings.ToUpper`) and the import paths of the packages with that name
         * that export the selected name, at most [limit], standard library first; null when [range] is not such a qualifier.
         */
        fun candidates(file: GoFile, range: TextRange, limit: Int): Pair<GoReferenceExpression, List<String>>? {
            val ref = PsiTreeUtil.findElementOfClassAtRange(file, range.startOffset, range.endOffset, GoReferenceExpression::class.java) ?: return null
            if (ref.expression != null) return null
            val name = ref.identifier.text
            val parent = ref.parent
            val selected = when {
                parent is GoReferenceExpression && parent.expression == ref -> parent.identifier.text
                parent is GoTypeReferenceExpression && parent.referenceExpression == ref -> parent.identifier.text
                else -> return null
            }
            val imported = file.imports.map { it.path }.toSet()
            val model = GoPackageModel.getInstance(file.project)
            val paths = GoImportPaths.all(file.project, file.originalFile.virtualFile).asSequence()
                .filter { it.name == name && it.path !in imported }
                .filter { entry ->
                    val pkg = model.resolveImport(entry.path, file) ?: return@filter false
                    (pkg.name == null || pkg.name == name) && model.scopeOf(pkg).lookup(selected).any { it.isPublic() }
                }
                .take(limit)
                .map { it.path }
                .toList()
            return ref to paths
        }
    }
}

/** Removes the unused import spec (its line; the whole declaration when it becomes empty). */
class GoRemoveImportFix : LocalQuickFix {
    override fun getFamilyName(): String = "Remove unused import"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val spec = PsiTreeUtil.getParentOfType(descriptor.psiElement, GoImportSpec::class.java, false) ?: return
        val file = spec.containingFile as? GoFile ?: return
        val document = GoImportEdits.document(file) ?: return
        GoImportEdits.removeSpecs(document, listOf(spec))
        GoImportEdits.commit(file, document)
    }
}

/** Optimize imports from the unused-import problem: the same as Code | Optimize Imports ([GoImportOptimizer]). */
class GoOptimizeImportsFix : LocalQuickFix {
    override fun getFamilyName(): String = "Optimize imports"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val file = descriptor.psiElement?.containingFile as? GoFile ?: return
        GoImportEdits.optimize(file, GoImportEdits.unusedImportRanges(file))
    }
}

/**
 * Code | Optimize Imports: removes imports the checker reports as unused (`_`, `.` and `"C"`
 * imports are never reported) and regroups the specs like `goimports -local <main module>`
 * ([GoImportGroups]): `"C"`, the standard library, third-party modules, the main module, one blank
 * line between the groups, each sorted. Blank-line groups written by hand are not kept.
 */
class GoImportOptimizer : com.intellij.lang.ImportOptimizer {
    /** Not ours while the diagnostics come from another source: Optimize Imports then falls back to whatever the host has. */
    override fun supports(file: PsiFile): Boolean = file is GoFile && GoIdeFeatureGate.enabled(GoIdeFeature.DIAGNOSTICS, file.project)

    override fun processFile(file: PsiFile): Runnable {
        val go = file as? GoFile ?: return Runnable {}
        val unused = GoImportEdits.unusedImportRanges(go)
        return Runnable { GoImportEdits.optimize(go, unused) }
    }
}
