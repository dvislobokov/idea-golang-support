package io.github.golangsupport.ide.refactoring

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.RefactoringActionHandler
import com.intellij.refactoring.rename.inplace.MemberInplaceRenamer
import com.intellij.refactoring.util.CommonRefactoringUtil
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.completion.GoImportInserter
import io.github.golangsupport.ide.inspections.GoImportEdits
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import org.jetbrains.annotations.TestOnly

/** What the in-place name would answer, for tests: the template does not run headless. */
class GoExtractFunctionOptions @TestOnly constructor(val name: String? = null)

/**
 * Extract Function / Method (Ctrl+Alt+M): the selected statements or expression become a new function (a method when they use the
 * receiver) declared after the enclosing top-level declaration, and a call replaces them ([GoExtractFunction] decides parameters,
 * results and refusals). No dialog: the name `extracted` is then edited in place at the declaration and the call.
 */
class GoExtractFunctionHandler @JvmOverloads constructor(private val options: GoExtractFunctionOptions? = null) : RefactoringActionHandler {

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?, dataContext: DataContext?) {
        if (editor == null || file !is GoFile || !GoIdeFeatureGate.enabled(GoIdeFeature.RENAME, project)) return
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val selection = editor.selectionModel
        if (!selection.hasSelection()) return error(project, editor, "Select statements or an expression to extract")
        val plan = try {
            GoExtractFunction.plan(file, selection.selectionStart, selection.selectionEnd, options?.name)
        } catch (e: GoExtractRefusal) {
            return error(project, editor, e.message ?: "")
        }
        if (!CommonRefactoringUtil.checkReadOnlyStatus(project, file)) return
        var declarationAt = -1
        WriteCommandAction.writeCommandAction(project, file).withName(TITLE).run<RuntimeException> {
            val document = GoImportEdits.document(file) ?: return@run
            document.insertString(plan.insertAt, plan.declaration)
            document.replaceString(plan.replace.startOffset, plan.replace.endOffset, plan.call)
            declarationAt = plan.insertAt + plan.call.length - plan.replace.length + 2
            GoImportEdits.commit(file, document)
            val before = document.textLength
            for (path in plan.imports) GoImportInserter.addImport(file, document, path)
            declarationAt += document.textLength - before
            GoImportEdits.commit(file, document)
        }
        selection.removeSelection()
        if (declarationAt < 0 || ApplicationManager.getApplication().isUnitTestMode || !editor.settings.isVariableInplaceRenameEnabled) return
        val declaration = PsiTreeUtil.findElementOfClassAtOffset(file, declarationAt, GoFunctionOrMethodDeclaration::class.java, false) ?: return
        editor.caretModel.moveToOffset(declaration.identifier?.textOffset ?: return)
        MemberInplaceRenamer(declaration, null, editor).performInplaceRename(linkedSetOf(plan.name))
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) = Unit

    private fun error(project: Project, editor: Editor, message: String) =
        CommonRefactoringUtil.showErrorHint(project, editor, "Cannot perform refactoring.\n$message", TITLE, null)

    companion object {
        const val TITLE = "Extract Function"
    }
}
