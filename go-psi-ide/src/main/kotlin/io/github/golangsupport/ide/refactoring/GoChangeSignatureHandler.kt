package io.github.golangsupport.ide.refactoring

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.changeSignature.ChangeSignatureHandler
import com.intellij.refactoring.util.CommonRefactoringUtil
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.navigation.GoImplementations
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoIndexOrSliceExpr
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.semantic.psi.GoPsiUtil.inner

/**
 * Change Signature (Ctrl+F6) of the function, method or interface method at the caret: on its name or header, on a reference to it,
 * or inside the arguments of a call to it. Library declarations are refused; the dialog is [GoChangeSignatureDialog].
 */
class GoChangeSignatureHandler : ChangeSignatureHandler {

    override fun findTargetMember(element: PsiElement): PsiElement? {
        if (!GoIdeFeatureGate.enabled(GoIdeFeature.RENAME, element.project)) return null
        val ref = PsiTreeUtil.getParentOfType(element, GoReferenceExpression::class.java, false)
        if (ref != null && ref.identifier === element) resolved(ref)?.let { return it }
        val decl = PsiTreeUtil.getParentOfType(element, GoFunctionOrMethodDeclaration::class.java, GoMethodSpec::class.java)
        val call = PsiTreeUtil.getParentOfType(element, GoCallExpr::class.java)
        if (call != null && (decl == null || PsiTreeUtil.isAncestor(decl, call, true)) && call.argumentList?.let { PsiTreeUtil.isAncestor(it, element, false) } == true) {
            callee(call)?.let(::resolved)?.let { return it }
        }
        if (decl is GoFunctionOrMethodDeclaration && decl.block?.let { PsiTreeUtil.isAncestor(it, element, false) } == true) return null
        return decl
    }

    override fun findTargetMember(file: PsiFile, editor: Editor): PsiElement? =
        file.findElementAt(editor.caretModel.offset)?.let(::findTargetMember)
            ?: editor.caretModel.offset.takeIf { it > 0 }?.let { file.findElementAt(it - 1) }?.let(::findTargetMember)

    override fun invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext?) {
        val target = findTargetMember(file, editor)
        if (target == null) {
            CommonRefactoringUtil.showErrorHint(project, editor, targetNotFoundMessage, TITLE, null)
            return
        }
        invoke(project, target, editor)
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {
        val target = elements.singleOrNull()?.takeIf(GoChangeSignature::isSupported) ?: return
        invoke(project, target, dataContext?.let { CommonDataKeys.EDITOR.getData(it) })
    }

    private fun invoke(project: Project, target: PsiElement, editor: Editor?) {
        if (!GoImplementations.isInProject(target)) {
            CommonRefactoringUtil.showErrorHint(project, editor, "Cannot change the signature of ${GoChangeSignature.displayName(target)}: it is not in the project", TITLE, null)
            return
        }
        if (!CommonRefactoringUtil.checkReadOnlyStatus(project, target)) return
        GoChangeSignatureDialog(project, target).show()
    }

    override fun getTargetNotFoundMessage(): String = "The caret should be on the name of a Go function or method, or inside a call to one"

    private fun resolved(ref: GoReferenceExpression): PsiElement? = ref.reference?.resolve()?.takeIf(GoChangeSignature::isSupported)

    private fun callee(call: GoCallExpr): GoReferenceExpression? {
        var e: PsiElement? = call.expression
        while (true) {
            e = when (e) {
                is GoParenthesesExpr -> e.inner
                is GoIndexOrSliceExpr -> e.firstChild // `f[int](x)`
                else -> break
            }
        }
        return e as? GoReferenceExpression
    }

    companion object {
        const val TITLE: String = "Change Signature"
    }
}
