package io.github.golangsupport.ide.inspections.flow

import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.inspections.GoAnalysisPsi
import io.github.golangsupport.lang.psi.GoAndExpr
import io.github.golangsupport.lang.psi.GoConditionalExpr
import io.github.golangsupport.lang.psi.GoElseStatement
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoReturnStatement
import io.github.golangsupport.semantic.flow.GoControlFlow
import io.github.golangsupport.semantic.flow.GoFlowNode
import io.github.golangsupport.semantic.flow.GoNil
import io.github.golangsupport.semantic.flow.GoNilness
import io.github.golangsupport.semantic.psi.GoPsiUtil.block
import io.github.golangsupport.semantic.psi.GoPsiUtil.condition
import io.github.golangsupport.semantic.psi.GoPsiUtil.inner
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoType

/**
 * nilerr: inside `if err != nil { … }` (or the `else` of `if err == nil`) a `return` gives `nil` for the function's `error` result
 * while `err` is still known non-nil and the branch never uses `err`: the error is swallowed. A comment inside the branch counts as
 * a documented intent and silences the report. No fix (the intent is unknown).
 */
class GoNilErrorReturnInspection : GoFlowInspectionBase() {

    override fun check(flow: GoControlFlow, holder: ProblemsHolder) {
        if (!lastResultIsError(flow)) return
        val nilness = GoNilness.of(flow) ?: return
        for (node in flow.nodes) {
            if (node.kind != GoFlowNode.Kind.RETURN || !flow.isReachable(node)) continue
            val ret = node.element as? GoReturnStatement ?: continue
            val last = ret.expressionList.lastOrNull() ?: continue
            if (!GoNilness.isNilLiteral(last, GoFlowChecks.service(last))) continue
            if (ret.expressionList.size == 1 && resultCount(flow) != 1) continue
            val (variable, branch) = guardingCheck(flow, ret) ?: continue
            if (nilness.before(variable, node) != GoNil.NOT_NIL) continue
            if (mentions(branch, variable.name)) continue
            // A comment in the branch documents that the error is dropped on purpose.
            if (PsiTreeUtil.findChildOfType(branch, PsiComment::class.java) != null) continue
            holder.registerProblem(last, "error is not nil but nil is returned")
        }
    }

    private fun signature(flow: GoControlFlow): GoType = when (val owner = flow.owner) {
        is GoFunctionOrMethodDeclaration -> GoFlowChecks.service(owner).declarationType(owner)
        is GoFunctionLit -> GoFlowChecks.service(owner).typeOf(owner)
        else -> io.github.golangsupport.semantic.types.GoUnknownType
    }

    private fun results(flow: GoControlFlow): List<GoType> = (signature(flow) as? GoSignatureType)?.results?.map { it.type } ?: emptyList()

    private fun resultCount(flow: GoControlFlow) = results(flow).size

    private fun lastResultIsError(flow: GoControlFlow): Boolean = results(flow).lastOrNull()?.let(GoAnalysisPsi::isError) == true

    /**
     * The innermost `if` around [ret] (within this function) whose then-block holds [ret] under `x != nil` (alone or as an `&&`
     * operand) or whose else-branch holds it under `x == nil`; the error variable and that branch.
     */
    private fun guardingCheck(flow: GoControlFlow, ret: GoReturnStatement): Pair<GoNamedElement, PsiElement>? {
        var child: PsiElement = ret
        var e: PsiElement? = ret.parent
        while (e != null && e !== flow.owner) {
            if (e is GoFunctionLit) return null
            if (e is GoIfStatement) {
                val inThen = child === e.block
                val inElse = child is GoElseStatement
                val wanted = when {
                    inThen -> false
                    inElse -> true
                    else -> null
                }
                if (wanted != null) {
                    val ref = comparedWithNil(e.condition, equal = wanted)
                    val v = ref?.let(flow::variableOf)
                    if (v != null && flow.isTracked(v) && GoFlowChecks.isErrorVariable(v)) return v to child
                }
            }
            child = e
            e = e.parent
        }
        return null
    }

    /** The variable compared with nil by [condition] (`==` when [equal]); for `!=` also an operand of a top-level `&&`. */
    private fun comparedWithNil(condition: GoExpression?, equal: Boolean): GoReferenceExpression? {
        var c: PsiElement? = condition
        while (c is GoParenthesesExpr) c = c.inner
        if (!equal && c is GoAndExpr) return comparedWithNil(c.left, false) ?: comparedWithNil(c.right, false)
        val cmp = c as? GoConditionalExpr ?: return null
        if (if (equal) cmp.eql == null else cmp.neq == null) return null
        val service = GoFlowChecks.service(cmp)
        val operand = when {
            GoNilness.isNilLiteral(cmp.right, service) -> cmp.left
            GoNilness.isNilLiteral(cmp.left, service) -> cmp.right
            else -> null
        }
        return (operand as? GoReferenceExpression)?.takeIf { it.expression == null }
    }

    private fun mentions(branch: PsiElement, name: String?): Boolean =
        name == null || PsiTreeUtil.findChildrenOfType(branch, GoReferenceExpression::class.java).any { it.expression == null && it.identifier.text == name }
}
