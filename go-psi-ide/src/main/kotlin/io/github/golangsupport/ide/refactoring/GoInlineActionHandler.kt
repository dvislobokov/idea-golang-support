package io.github.golangsupport.ide.refactoring

import com.intellij.lang.Language
import com.intellij.lang.refactoring.InlineActionHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.util.CommonRefactoringUtil
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.semantic.api.GoSemanticService

/**
 * Inline (Ctrl+Alt+N) for Go: a local variable ([GoInlineVariable]), a constant ([GoInlineConstant]) or a call of a one-statement
 * function ([GoInlineFunction]; the caret on a call inlines that call, on the function's name every call and the declaration). The
 * platform finds the element under the caret (a use resolves to its declaration) and asks each handler; no dialog: a refusal is an
 * error hint naming the reason. Gated by [GoIdeFeature.RENAME] like the other refactorings.
 */
class GoInlineActionHandler : InlineActionHandler() {

    override fun isEnabledForLanguage(l: Language?): Boolean = l == GoLanguage

    override fun canInlineElement(element: PsiElement?): Boolean {
        if (element == null || element.language != GoLanguage || !GoIdeFeatureGate.enabled(GoIdeFeature.RENAME, element.project)) return false
        return element is GoVarDefinition || element is GoConstDefinition || element is GoFunctionOrMethodDeclaration
    }

    override fun getActionName(element: PsiElement?): String = when (element) {
        is GoVarDefinition -> GoInlineVariable.TITLE
        is GoConstDefinition -> GoInlineConstant.TITLE
        else -> GoInlineFunction.TITLE
    }

    override fun inlineElement(project: Project, editor: Editor?, element: PsiElement) {
        if (!canInlineElement(element)) return
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val title = getActionName(element)
        val edits = try {
            when (element) {
                is GoVarDefinition -> GoInlineVariable.plan(element)
                is GoConstDefinition -> GoInlineConstant.plan(element)
                is GoFunctionOrMethodDeclaration -> callAtCaret(project, editor, element)?.let { GoInlineFunction.planCall(element, it) } ?: GoInlineFunction.planAll(element)
                else -> return
            }
        } catch (e: GoInlineRefusal) {
            return CommonRefactoringUtil.showErrorHint(project, editor, "Cannot perform refactoring.\n${e.message}", title, null)
        }
        if (!CommonRefactoringUtil.checkReadOnlyStatus(project, edits.map { it.file }.distinct(), true)) return
        GoInlineSupport.apply(project, title, edits)
    }

    /** The call of [function] whose callee is under the caret, or null (the caret on the declaration's name). */
    private fun callAtCaret(project: Project, editor: Editor?, function: GoFunctionOrMethodDeclaration): GoCallExpr? {
        editor ?: return null
        val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return null
        val offset = editor.caretModel.offset
        val service = GoSemanticService.getInstance(project)
        for (at in listOfNotNull(file.findElementAt(offset), if (offset > 0) file.findElementAt(offset - 1) else null)) {
            val ref = PsiTreeUtil.getParentOfType(at, GoReferenceExpression::class.java, false) ?: continue
            if (ref.identifier != at || !service.resolve(ref).contains(function)) continue
            return GoInlineFunction.callOf(ref) ?: GoInlineSupport.refuse("'${function.name}' is used as a value here, not called")
        }
        return null
    }
}
