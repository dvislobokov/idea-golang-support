package io.github.golangsupport.ide.inspections.flow

import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import io.github.golangsupport.lang.psi.GoElseStatement
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoReturnStatement
import io.github.golangsupport.semantic.flow.GoControlFlow
import io.github.golangsupport.semantic.flow.GoFlowNode
import io.github.golangsupport.semantic.flow.GoNil
import io.github.golangsupport.semantic.flow.GoNilness
import io.github.golangsupport.semantic.psi.GoPsiUtil.block
import io.github.golangsupport.semantic.psi.GoPsiUtil.condition

/**
 * The inverse of nilerr: inside `if err == nil { … }` (or the `else` of `if err != nil`) the error result returns `err`, which is
 * known nil there: the author meant another error or `nil`. Fix: write `nil`.
 */
class GoErrNilReturnedInspection : GoFlowInspectionBase() {

    override fun check(flow: GoControlFlow, holder: ProblemsHolder) {
        val resultCount = GoFlowSupport.resultTypes(flow).size
        if (resultCount == 0 || !GoFlowSupport.lastResultIsError(flow)) return
        val nilness = GoNilness.of(flow) ?: return
        for (node in flow.nodes) {
            if (node.kind != GoFlowNode.Kind.RETURN || !flow.isReachable(node)) continue
            val ret = node.element as? GoReturnStatement ?: continue
            if (ret.expressionList.size != resultCount) continue
            val ref = GoFlowChecks.unparen(ret.expressionList.last()) as? GoReferenceExpression ?: continue
            if (ref.expression != null) continue
            val v = flow.variableOf(ref) ?: continue
            if (!flow.isTracked(v) || !GoFlowChecks.isErrorVariable(v)) continue
            if (nilness.at(ref) != GoNil.NIL || !guarded(flow, ret, v)) continue
            holder.registerProblem(ref, "${ref.text} is nil here; nil is returned", GoFlowSupport.ReplaceFix("Return nil", "nil"))
        }
    }

    /** An `if` around [ret] that holds it in the branch where [v] is nil (then of `v == nil`, else of `v != nil`). */
    private fun guarded(flow: GoControlFlow, ret: GoReturnStatement, v: GoNamedElement): Boolean {
        var child: PsiElement = ret
        var e: PsiElement? = ret.parent
        while (e != null && e !== flow.owner) {
            if (e is GoFunctionLit) return false
            if (e is GoIfStatement) {
                if (child === e.block && GoFlowSupport.comparesToNil(flow, e.condition, v, equal = true)) return true
                if (child is GoElseStatement && GoFlowSupport.comparesToNil(flow, e.condition, v, equal = false)) return true
            }
            child = e
            e = e.parent
        }
        return false
    }
}
