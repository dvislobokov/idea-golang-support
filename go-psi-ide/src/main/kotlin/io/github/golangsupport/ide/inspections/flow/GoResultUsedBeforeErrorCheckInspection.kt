package io.github.golangsupport.ide.inspections.flow

import com.intellij.codeInspection.ProblemsHolder
import io.github.golangsupport.lang.psi.GoArgumentList
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoDeferStatement
import io.github.golangsupport.lang.psi.GoIndexOrSliceExpr
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.semantic.flow.GoControlFlow
import io.github.golangsupport.semantic.flow.GoFlowAccess
import io.github.golangsupport.semantic.flow.GoFlowNode
import io.github.golangsupport.semantic.flow.GoLiveness
import io.github.golangsupport.semantic.flow.GoNilness
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType

/**
 * `v, err := f()` followed by a use of `v` (a field or method, an index, a call argument, a dereference) on a path that reaches no
 * read of `err` first, while `err` is read later: the result is used before the error is checked. Only results that can be nil
 * (pointers, slices, maps, interfaces, …) count; comparisons and the check itself are not uses; `defer v.Close()` is left to
 * [GoDeferBeforeErrorCheckInspection]; the io.Reader idiom (`n, err := r.Read(p)`, a count before the error) is exempt. No fix.
 */
class GoResultUsedBeforeErrorCheckInspection : GoFlowInspectionBase() {

    override fun check(flow: GoControlFlow, holder: ProblemsHolder) {
        val liveness = GoLiveness.of(flow) ?: return
        val reported = HashSet<GoReferenceExpression>()
        for (node in flow.nodes) {
            val stmt = node.element
            if (stmt !is GoShortVarDeclaration && stmt !is GoAssignmentStatement || !flow.isReachable(node)) continue
            val writes = node.accesses.filter { it.isWrite && !it.isCompound && it.value != null }
            val errW = writes.firstOrNull { it.resultIndex == 1 && GoFlowChecks.unparen(it.value) is GoCallExpr } ?: continue
            val vW = writes.firstOrNull { it.resultIndex == 0 && it.value === errW.value } ?: continue
            if (writes.count { it.value === errW.value } != 2) continue
            val err = errW.variable
            val v = vW.variable
            if (err === v || !flow.isTracked(err) || !flow.isTracked(v) || !GoFlowChecks.isErrorVariable(err)) continue
            val type = GoFlowChecks.service(v).declarationType(v)
            // pointers and interfaces only: a nil slice, map or chan is safe to read, and partial output before the error is an idiom
            // (`out, err := cmd.CombinedOutput()`, `buf.Write(b)`; seen in GOROOT)
            val u = type.underlying()
            if (u !is io.github.golangsupport.semantic.types.GoPointerType && u !is io.github.golangsupport.semantic.types.GoInterfaceType) continue
            if (isReaderIdiom(errW)) continue
            if (!liveness.isReadAfter(errW)) continue
            val from = maxOf(node.accesses.indexOf(errW), node.accesses.indexOf(vW)) + 1
            val use = firstUse(flow, node, from, err, v) ?: continue
            if (reported.add(use)) holder.registerProblem(use, "${use.text} is used before ${err.name} is checked")
        }
    }

    /** The leftmost use of [v] on a path from [start] (accesses from [from] on) before any access of [err]; null when there is none. */
    private fun firstUse(flow: GoControlFlow, start: GoFlowNode, from: Int, err: GoNamedElement, v: GoNamedElement): GoReferenceExpression? {
        var best: GoReferenceExpression? = null
        /** Scans [accesses] of [node]; true when the paths go on past it. */
        fun scan(node: GoFlowNode, accesses: List<GoFlowAccess>): Boolean {
            for (a in accesses) {
                if (a.variable === err) return false
                if (a.variable !== v) continue
                if (a.isWrite) return false
                val ref = a.element as? GoReferenceExpression ?: continue
                // `if info != nil { … *info }`: the result itself is checked instead of the error (cmd/go modfetch, seen in GOROOT)
                if (GoFlowSupport.isNilComparisonOperand(ref)) return false
                if (!isUse(ref) || node.element is GoDeferStatement) continue
                if (node.accesses.any { it.variable === err }) return false
                val current = best
                if (current == null || ref.textRange.startOffset < current.textRange.startOffset) best = ref
                return false
            }
            return true
        }
        val seen = BooleanArray(flow.nodes.size)
        val stack = ArrayDeque<GoFlowNode>()
        if (scan(start, start.accesses.drop(from))) start.successors.forEach { stack.addLast(it.to) }
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (seen[n.index]) continue
            seen[n.index] = true
            if (scan(n, n.accesses)) n.successors.forEach { stack.addLast(it.to) }
        }
        return best
    }

    private fun isUse(ref: GoReferenceExpression): Boolean = when (val p = ref.parent) {
        is GoReferenceExpression -> p.expression === ref
        is GoIndexOrSliceExpr -> p.expression === ref
        is GoUnaryExpr -> p.mul != null
        is GoCallExpr -> p.expression === ref
        is GoArgumentList -> !isNilSafeBuiltin(p.parent as? GoCallExpr)
        else -> false
    }

    private fun isNilSafeBuiltin(call: GoCallExpr?): Boolean {
        val callee = GoFlowChecks.unparen(call?.expression) as? GoReferenceExpression ?: return false
        return callee.expression == null && callee.identifier.text in NIL_SAFE
    }

    /** `n, err := r.Read(p)`: a method of the io.Reader / io.Writer family, where using the count before the error is fine. */
    private fun isReaderIdiom(errW: GoFlowAccess): Boolean {
        val callee = GoFlowChecks.unparen((GoFlowChecks.unparen(errW.value) as? GoCallExpr)?.expression) as? GoReferenceExpression ?: return false
        return callee.expression != null && callee.identifier.text in IO_METHODS
    }

    private companion object {
        val NIL_SAFE = setOf("len", "cap", "append", "copy", "delete", "clear", "close")
        val IO_METHODS = setOf("Read", "ReadAt", "Write", "WriteString", "ReadFrom", "WriteTo")
    }
}
