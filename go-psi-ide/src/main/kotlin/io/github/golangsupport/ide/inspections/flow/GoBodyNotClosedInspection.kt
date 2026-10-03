package io.github.golangsupport.ide.inspections.flow

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoConditionalExpr
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.semantic.flow.GoControlFlow
import io.github.golangsupport.semantic.flow.GoFlowNode

/**
 * bodyclose: `resp, err := http.Get(…)` (also `Post`, `Head`, `PostForm` and the same methods of `*http.Client`, `Do` included)
 * and some path on which `err` is nil reaches a `return` without `resp.Body.Close()`, called or deferred. Silent when `resp` is
 * handed elsewhere (returned, passed, stored, captured); a path that passes `resp.Body` to a call or stores it counts as closed.
 */
class GoBodyNotClosedInspection : GoFlowInspectionBase() {

    override fun check(flow: GoControlFlow, holder: ProblemsHolder) {
        for (node in flow.nodes) {
            if (!flow.isReachable(node)) continue
            val (call, targets) = GoResourceFlow.callAssignment(node.element ?: continue) ?: continue
            if (targets.size != 2 || !isHttpCall(call)) continue
            val target = targets[0]
            val resp = node.accesses.firstOrNull { it.isWrite && it.element === target }?.variable ?: continue
            if (!flow.isTracked(resp)) continue
            val err = node.accesses.firstOrNull { it.isWrite && it.element === targets[1] }?.variable
            val uses = GoResourceFlow.selectorUses(flow, resp) ?: continue
            val handled = HashSet<GoFlowNode>()
            for ((access, selector) in uses) {
                when (selector.identifier.text) {
                    "Write" -> handled += access.node
                    "Body" -> {
                        // `resp.Body.Close()` closes it; `resp.Body.Read(…)` and `resp.Body != nil` do not; anything else hands it over
                        val parent = selector.parent
                        val keeps = parent is GoReferenceExpression && parent.expression === selector && parent.identifier.text != "Close" || parent is GoConditionalExpr
                        if (!keeps) handled += access.node
                    }
                }
            }
            if (GoResourceFlow.leakingReturn(
                    node,
                    stops = { it in handled || GoResourceFlow.writes(it, resp) },
                    killsGuard = { err != null && GoResourceFlow.writes(it, err) },
                    cut = { e, guard -> guard && GoResourceFlow.isNilEdge(flow, e, err, nonNil = true) || GoResourceFlow.isNilEdge(flow, e, resp, nonNil = false) },
                ) == null
            ) continue
            val name = resp.name ?: continue
            val errName = err?.name
            val fix = if (GoResourceFlow.canAddDefer(target, errName)) arrayOf<LocalQuickFix>(GoResourceFlow.AddDeferFix("$name.Body.Close()", errName)) else emptyArray()
            holder.registerProblem(target, "response body must be closed", ProblemHighlightType.WARNING, *fix)
        }
    }

    /** `http.Get` / `Post` / `Head` / `PostForm`, or those and `Do` on an `http.Client`. */
    private fun isHttpCall(call: GoCallExpr): Boolean {
        val (target, path) = GoResourceFlow.callee(call) ?: return false
        if (path != "net/http") return false
        return when (target) {
            is GoFunctionDeclaration -> target.name in FUNCTIONS
            is GoMethodDeclaration -> (target.name in FUNCTIONS || target.name == "Do") && GoResourceFlow.receiverTypeName(target) == "Client"
            else -> false
        }
    }

    private companion object {
        val FUNCTIONS = setOf("Get", "Post", "Head", "PostForm")
    }
}
