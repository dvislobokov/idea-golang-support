package io.github.golangsupport.ide.refactoring

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Pass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.IntroduceTargetChooser
import com.intellij.refactoring.RefactoringActionHandler
import com.intellij.refactoring.introduce.inplace.OccurrencesChooser
import com.intellij.refactoring.rename.inplace.MemberInplaceRenamer
import com.intellij.refactoring.rename.inplace.VariableInplaceRenamer
import com.intellij.refactoring.util.CommonRefactoringUtil
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.inspections.GoImportEdits
import io.github.golangsupport.ide.intentions.GoIntentionText
import io.github.golangsupport.ide.intentions.GoZeroValues
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoImportDeclaration
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.scope.GoScopes
import io.github.golangsupport.semantic.scope.GoUniverse
import io.github.golangsupport.semantic.types.GoTupleType
import io.github.golangsupport.semantic.types.GoType
import org.jetbrains.annotations.TestOnly

/**
 * What the dialogs and choosers would answer, for tests: the platform popups (target chooser, occurrences chooser, in-place name)
 * do not run headless, so in unit-test mode the innermost expression is taken, [replaceAll] decides the occurrences and [name]
 * (or the first suggestion) is the name.
 */
class GoIntroduceOptions @TestOnly constructor(val replaceAll: Boolean = false, val name: String? = null)

/** The selection or caret part shared by Introduce Variable and Introduce Constant. */
abstract class GoIntroduceHandlerBase(protected val options: GoIntroduceOptions?) : RefactoringActionHandler {
    protected abstract val title: String

    /** Why [expr] cannot be introduced here, or null. */
    protected abstract fun problem(expr: GoExpression): String?

    protected abstract fun introduce(project: Project, editor: Editor, file: GoFile, expr: GoExpression)

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?, dataContext: DataContext?) {
        if (editor == null || file !is GoFile || !GoIdeFeatureGate.enabled(GoIdeFeature.RENAME, project)) return
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val selection = editor.selectionModel
        if (selection.hasSelection()) {
            val expr = GoExtraction.selectedExpression(file, selection.selectionStart, selection.selectionEnd)
                ?: return error(project, editor, "Selected block should represent an expression")
            problem(expr)?.let { return error(project, editor, it) }
            return introduce(project, editor, file, expr)
        }
        val all = GoExtraction.expressionsAt(file, editor.caretModel.offset)
        val candidates = all.filter { problem(it) == null }
        if (candidates.isEmpty()) return error(project, editor, all.firstOrNull()?.let(::problem) ?: "The caret should be on an expression")
        if (candidates.size == 1 || ApplicationManager.getApplication().isUnitTestMode) return introduce(project, editor, file, candidates.first())
        IntroduceTargetChooser.showChooser(editor, candidates, object : Pass<GoExpression>() {
            override fun pass(expr: GoExpression) = introduce(project, editor, file, expr)
        }, { e: GoExpression -> e.text.replace(Regex("\\s+"), " ").let { if (it.length > 60) it.take(57) + "..." else it } }, "Expressions")
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) = Unit

    protected fun error(project: Project, editor: Editor, message: String) =
        CommonRefactoringUtil.showErrorHint(project, editor, "Cannot perform refactoring.\n$message", title, null)

    /** Asks whether to replace [expr] only or all [occurrences] (more than one), then runs [then] with the chosen list. */
    protected fun chooseOccurrences(editor: Editor, expr: GoExpression, occurrences: List<GoExpression>, then: (List<GoExpression>) -> Unit) {
        if (occurrences.size <= 1) return then(listOf(expr))
        if (ApplicationManager.getApplication().isUnitTestMode) return then(if (options?.replaceAll == true) occurrences else listOf(expr))
        OccurrencesChooser.simpleChooser<GoExpression>(editor).showChooser(expr, occurrences, object : Pass<OccurrencesChooser.ReplaceChoice>() {
            override fun pass(choice: OccurrencesChooser.ReplaceChoice) = then(if (choice == OccurrencesChooser.ReplaceChoice.ALL) occurrences else listOf(expr))
        })
    }

    /** Replaces [occurrences] by [replacement] and inserts [declaration] at [offset] (before them all) in one command. */
    protected fun write(project: Project, file: GoFile, occurrences: List<GoExpression>, replacement: String, offset: Int, declaration: String) {
        val ranges = occurrences.map { it.textRange }.sortedByDescending { it.startOffset }
        WriteCommandAction.writeCommandAction(project, file).withName(title).run<RuntimeException> {
            val document = GoImportEdits.document(file) ?: return@run
            for (r in ranges) document.replaceString(r.startOffset, r.endOffset, replacement)
            document.insertString(offset, declaration)
            GoImportEdits.commit(file, document)
        }
    }

    /** Lets the user type another name over the new declaration and its uses (not in tests: the template would wait for input). */
    protected fun renameInPlace(editor: Editor, element: PsiNamedElement?, names: Collection<String>, member: Boolean) {
        if (element == null || ApplicationManager.getApplication().isUnitTestMode || !editor.settings.isVariableInplaceRenameEnabled) return
        editor.caretModel.moveToOffset(element.textOffset)
        val renamer = if (member) MemberInplaceRenamer(element, null, editor) else VariableInplaceRenamer(element, editor)
        renamer.performInplaceRename(LinkedHashSet(names))
    }
}

/**
 * Introduce Variable (Ctrl+Alt+V): `name := expr` before the statement that evaluates the expression, in the same block or case
 * clause; with "replace all" every equivalent expression of the same function goes, and the declaration moves before the statement
 * of the innermost block holding them all. A call of several results becomes `a, b := f()`. Not offered where evaluating the
 * expression earlier would change the program ([GoExtraction.anchorOf]). The platform has no language-agnostic introduce-variable
 * base (Java's and Kotlin's are their own), so this is a small handler over the platform's choosers and in-place renamer.
 */
class GoIntroduceVariableHandler @JvmOverloads constructor(options: GoIntroduceOptions? = null) : GoIntroduceHandlerBase(options) {
    override val title: String = "Introduce Variable"

    override fun problem(expr: GoExpression): String? {
        GoExtraction.rejectReason(expr)?.let { return it }
        val type = GoSemanticService.getInstance(expr.project).typeOf(expr)
        if (GoExtraction.isValueless(type)) return "The expression has no value"
        if (GoExtraction.anchorOf(expr) == null) return "A statement cannot be inserted where the expression is evaluated"
        return null
    }

    override fun introduce(project: Project, editor: Editor, file: GoFile, expr: GoExpression) {
        val anchor = GoExtraction.anchorOf(expr) ?: return
        val type = GoSemanticService.getInstance(project).typeOf(expr)
        val owner = GoPsiUtil.functionOwner(expr)
        if (type is GoTupleType) return introduceTuple(project, editor, file, expr, type, anchor, owner)
        val all = GoExtraction.occurrences(expr, owner ?: file) { o -> GoPsiUtil.functionOwner(o) == owner && problem(o) == null }
        val allAnchor = if (all.size > 1) GoExtraction.commonAnchor(all) else null
        chooseOccurrences(editor, expr, if (allAnchor != null) all else listOf(expr)) { chosen ->
            val at = if (chosen.size > 1) allAnchor!! else anchor
            val taken = GoExtraction.localTaken(at, owner)
            val names = GoExtraction.suggestNames(expr, type).map { GoExtraction.unique(it, taken) }.distinct()
            val name = options?.name ?: names.first()
            perform(project, editor, file, chosen, at, name, expr.text, names)
        }
    }

    private fun perform(project: Project, editor: Editor, file: GoFile, chosen: List<GoExpression>, at: GoStatement, name: String, value: String, names: List<String>) {
        val offset = at.textRange.startOffset
        val indent = GoIntentionText.indentAt(file.viewProvider.contents, offset)
        write(project, file, chosen, name, offset, "$name := $value\n$indent")
        val definition = PsiTreeUtil.findElementOfClassAtOffset(file, offset, GoVarDefinition::class.java, false)
        renameInPlace(editor, definition, names, member = false)
    }

    /** `a, b := f()` for a call of several results: the names of the results where they have them, else by their types. */
    private fun introduceTuple(project: Project, editor: Editor, file: GoFile, expr: GoExpression, type: GoTupleType, anchor: GoStatement, owner: PsiElement?) {
        val results = (expr as? GoCallExpr)?.let { GoSemanticService.getInstance(project).calleeSignature(it)?.results }
        val taken = GoExtraction.localTaken(anchor, owner)
        val chosen = ArrayList<String>()
        type.types.forEachIndexed { i, t ->
            val declared = results?.getOrNull(i)?.name?.takeIf { it.isNotEmpty() && it != "_" && !GoUniverse.isBuiltin(it) }
            val base = declared ?: if (GoZeroValues.isError(t)) "err" else GoExtraction.suggestNames(null, t).first()
            chosen += GoExtraction.unique(base) { taken(it) || it in chosen }
        }
        val names = chosen.joinToString(", ")
        val offset = anchor.textRange.startOffset
        val indent = GoIntentionText.indentAt(file.viewProvider.contents, offset)
        write(project, file, listOf(expr), names, offset, "$names := ${expr.text}\n$indent")
    }
}

/**
 * Introduce Constant (Ctrl+Alt+C): a constant expression (the semantic checker gives it a value; its names are package-level or
 * imported constants, not local ones nor `iota`) becomes `const name = expr` after the imports, replacing this or every equivalent
 * expression of the file. The name avoids the package's names and the locals around each replaced occurrence.
 */
class GoIntroduceConstantHandler @JvmOverloads constructor(options: GoIntroduceOptions? = null) : GoIntroduceHandlerBase(options) {
    override val title: String = "Introduce Constant"

    override fun problem(expr: GoExpression): String? {
        GoExtraction.rejectReason(expr)?.let { return it }
        val service = GoSemanticService.getInstance(expr.project)
        if (service.constantValue(expr) == null) return "The expression is not constant"
        val refs = PsiTreeUtil.findChildrenOfType(expr, GoReferenceExpression::class.java) + listOfNotNull(expr as? GoReferenceExpression)
        for (ref in refs) {
            if (ref.expression == null && ref.identifier.text == "iota") return "The expression uses iota"
            if (service.resolve(ref).any { GoPsiUtil.functionOwner(it) != null }) return "The expression uses a local constant"
        }
        return null
    }

    override fun introduce(project: Project, editor: Editor, file: GoFile, expr: GoExpression) {
        val type = GoSemanticService.getInstance(project).typeOf(expr)
        val all = GoExtraction.occurrences(expr, file) { problem(it) == null }
        chooseOccurrences(editor, expr, all) { chosen ->
            val packageScope = file.packageClause ?: file
            val taken = { n: String -> GoUniverse.isBuiltin(n) || GoScopes.resolveName(packageScope, n).isNotEmpty() || chosen.any { GoScopes.resolveName(it, n).isNotEmpty() } }
            val names = constantNames(expr, type).map { GoExtraction.unique(it, taken) }.distinct()
            val name = options?.name ?: names.first()
            val anchor = PsiTreeUtil.findChildrenOfType(file, GoImportDeclaration::class.java).lastOrNull() ?: file.packageClause
            val offset = anchor?.textRange?.endOffset ?: 0
            write(project, file, chosen, name, offset, "\n\nconst $name = ${expr.text}")
            val definition = PsiTreeUtil.findElementOfClassAtOffset(file, offset + "\n\nconst ".length, GoConstDefinition::class.java, false)
            renameInPlace(editor, definition, names, member = true)
        }
    }

    /** A string's words (`"max retries"` -> `maxRetries`), then the variable names with `c` in place of `v`. */
    private fun constantNames(expr: GoExpression, type: GoType): List<String> {
        val names = ArrayList<String>()
        if (expr is GoStringLiteral) {
            val words = Regex("[A-Za-z][A-Za-z0-9]*").findAll(expr.text).map { it.value.lowercase() }.take(3).toList()
            if (words.isNotEmpty()) names += words.first() + words.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        }
        names += GoExtraction.suggestNames(expr, type).map { if (it == "v") "c" else it }
        return names.distinct()
    }
}
