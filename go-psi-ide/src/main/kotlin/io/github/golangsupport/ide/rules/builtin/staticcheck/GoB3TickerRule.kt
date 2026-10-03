package io.github.golangsupport.ide.rules.builtin.staticcheck

import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.ide.rules.GoRuleNeed
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoRangeClause
import io.github.golangsupport.semantic.flow.GoControlFlow
import io.github.golangsupport.semantic.flow.GoFlowNode

/**
 * staticcheck SA1015: `time.Tick` in a function that returns (before Go 1.23 the ticker is never collected), outside `package main`
 * (and cobra commands) and tests. A function "returns" when a `return` (or the end of the body) is reachable in its flow graph; leaving
 * a `for range time.Tick(…)` loop does not count, the channel is never closed. The Go version is the `go` directive of the module:
 * without one the rule stays quiet, like staticcheck without a position.
 */
class GoTimeTickLeakRule : GoStaticcheckCallRule() {
    override val id: String get() = "SA1015"
    override val title: String get() = "time.Tick leaks the ticker (go < 1.23)"
    override val description: String get() =
        "Before Go 1.23 a ticker made by <code>time.Tick</code> is never garbage collected: in a function that returns it leaks. Use " +
            "<code>time.NewTicker</code> and <code>Stop</code> it, or keep <code>time.Tick</code> to endless functions, tests and <code>package main</code>."
    override val calleeNames: Set<String> get() = NAMES
    override val needs: Set<GoRuleNeed> get() = GoB3Psi.TYPES_FLOW

    override fun checkCallee(call: GoCallExpr, callee: String, arguments: List<GoExpression>, ctx: GoRuleContext) {
        if (callee != "time.Tick") return
        val file = ctx.file
        if (file.isTestFile || file.packageName == "main") return
        val version = GoLintPsi.goVersion(file) ?: return
        if (compareValues(version.first, 1) > 0 || version.first == 1 && version.second >= 23) return
        if (ctx.packageFiles.any { f -> f.imports.any { it.path == "github.com/spf13/cobra" } }) return
        val flow = ctx.flowOf(call) ?: return
        if (!terminates(flow, ctx)) return
        ctx.report(call, "using time.Tick leaks the underlying ticker, consider using it only in endless functions, tests and the main package, and use time.NewTicker here")
    }

    /** Whether a return of the function is reachable, not counting the way out of a `for range time.Tick(…)` loop. */
    private fun terminates(flow: GoControlFlow, ctx: GoRuleContext): Boolean {
        val seen = BooleanArray(flow.nodes.size)
        val stack = ArrayDeque<GoFlowNode>()
        stack += flow.entry
        seen[flow.entry.index] = true
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (node.kind == GoFlowNode.Kind.RETURN) return true
            val loopBody = if (node.kind == GoFlowNode.Kind.RANGE) tickLoopBody(node, ctx) else null
            for (edge in node.successors) {
                val to = edge.to
                if (loopBody != null && to !== node && to.element?.textRange?.let { loopBody.contains(it) } != true) continue
                if (!seen[to.index]) {
                    seen[to.index] = true
                    stack += to
                }
            }
        }
        return false
    }

    /** The body range of `for range time.Tick(…)` whose head is [node]; null for other loops. */
    private fun tickLoopBody(node: GoFlowNode, ctx: GoRuleContext): com.intellij.openapi.util.TextRange? {
        val range = node.element as? GoRangeClause ?: return null
        val call = GoLintPsi.unparen(range.expression) as? GoCallExpr ?: return null
        val ref = GoLintPsi.calleeReference(call) ?: return null
        if (ref.identifier?.text != "Tick" || GoStaticcheckPsi.calleeKey(ref, ctx) != "time.Tick") return null
        return (range.parent as? GoForStatement)?.block?.textRange
    }

    private companion object {
        val NAMES = setOf("Tick")
    }
}
