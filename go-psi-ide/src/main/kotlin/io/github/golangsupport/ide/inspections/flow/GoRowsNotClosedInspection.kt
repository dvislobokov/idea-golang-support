package io.github.golangsupport.ide.inspections.flow

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.semantic.flow.GoControlFlow
import io.github.golangsupport.semantic.flow.GoFlowNode

/**
 * sqlclosecheck / rowserrcheck: `rows, err := db.Query(…)` (`QueryContext` too; `*sql.DB`, `*sql.Tx`, `*sql.Conn`, `*sql.Stmt`)
 * and some path on which `err` is nil reaches a `return` without `rows.Close()`, called or deferred; and a `for rows.Next()` loop
 * without any `rows.Err()` call. Silent when `rows` is handed elsewhere (returned, passed, stored, captured).
 */
class GoRowsNotClosedInspection : GoFlowInspectionBase() {

    override fun check(flow: GoControlFlow, holder: ProblemsHolder) {
        for (node in flow.nodes) {
            if (!flow.isReachable(node)) continue
            val (call, targets) = GoResourceFlow.callAssignment(node.element ?: continue) ?: continue
            if (targets.size != 2 || !isQuery(call)) continue
            val target = targets[0]
            val rows = node.accesses.firstOrNull { it.isWrite && it.element === target }?.variable ?: continue
            if (!flow.isTracked(rows)) continue
            val err = node.accesses.firstOrNull { it.isWrite && it.element === targets[1] }?.variable
            val uses = GoResourceFlow.selectorUses(flow, rows) ?: continue
            val closes = uses.filter { it.second.identifier.text == "Close" }.mapTo(HashSet()) { it.first.node }
            val leak = GoResourceFlow.leakingReturn(
                node,
                stops = { it in closes || GoResourceFlow.writes(it, rows) },
                killsGuard = { err != null && GoResourceFlow.writes(it, err) },
                cut = { e, guard -> guard && GoResourceFlow.isNilEdge(flow, e, err, nonNil = true) || GoResourceFlow.isNilEdge(flow, e, rows, nonNil = false) },
            )
            val name = rows.name ?: continue
            if (leak != null) {
                val errName = err?.name
                val fix = if (GoResourceFlow.canAddDefer(target, errName)) arrayOf<LocalQuickFix>(GoResourceFlow.AddDeferFix("$name.Close()", errName)) else emptyArray()
                holder.registerProblem(target, "$name must be closed", ProblemHighlightType.WARNING, *fix)
            }
            if (uses.any { it.second.identifier.text == "Err" }) continue
            for ((access, selector) in uses) {
                if (selector.identifier.text != "Next") continue
                val next = selector.parent as? GoCallExpr ?: continue
                if (access.node.kind != GoFlowNode.Kind.CONDITION || access.node.element !== next || next.parent !is GoForStatement) continue
                holder.registerProblem(next, "$name.Err() is not checked after the loop", ProblemHighlightType.WEAK_WARNING)
            }
        }
    }

    /** `Query` / `QueryContext` of `database/sql`. */
    private fun isQuery(call: GoCallExpr): Boolean {
        val (target, path) = GoResourceFlow.callee(call) ?: return false
        return path == "database/sql" && target is GoMethodDeclaration && target.name in METHODS && GoResourceFlow.receiverTypeName(target) in RECEIVERS
    }

    private companion object {
        val METHODS = setOf("Query", "QueryContext")
        val RECEIVERS = setOf("DB", "Tx", "Conn", "Stmt")
    }
}
