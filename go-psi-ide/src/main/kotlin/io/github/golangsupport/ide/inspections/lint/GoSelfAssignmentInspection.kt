package io.github.golangsupport.ide.inspections.lint

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.inspections.GoAnalysisInspectionBase
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoUnaryExpr

/**
 * `x = x`, `s.f = s.f`, `a[i] = a[i]` and `a, b = a, b` (staticcheck SA4018): the assignment does nothing. Only operands without side
 * effects count (a call, a receive or a function literal anywhere in them makes the pair different from itself). Fix: remove the
 * statement (offered when every pair is a self-assignment).
 */
class GoSelfAssignmentInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoAssignmentStatement || element.assignOp.text != "=") return
        val left = element.leftHandExprList.expressionList
        val right = element.expressionList
        if (left.size != right.size) return
        val same = left.indices.filter { isSelf(left[it], right[it]) }
        if (same.isEmpty()) return
        val all = same.size == left.size
        val fixes = if (all) arrayOf<LocalQuickFix>(GoRemoveStatementFix("Remove self-assignment")) else emptyArray()
        for (i in same) {
            val range = if (left.size == 1) TextRange(0, element.textLength) else left[i].textRange.shiftLeft(element.textRange.startOffset)
            holder.registerProblem(element, range, "self-assignment of ${right[i].text} to ${left[i].text}", *fixes)
        }
    }

    private fun isSelf(a: GoExpression, b: GoExpression): Boolean {
        if (a.text.filterNot { it.isWhitespace() } != b.text.filterNot { it.isWhitespace() }) return false
        return pure(a)
    }

    private fun pure(e: GoExpression): Boolean = PsiTreeUtil.findChildOfType(e, GoCallExpr::class.java, false) == null &&
        PsiTreeUtil.findChildOfType(e, GoFunctionLit::class.java, false) == null &&
        PsiTreeUtil.findChildrenOfType(e, GoUnaryExpr::class.java).none { it.arrow != null }
}
