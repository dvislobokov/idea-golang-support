package io.github.golangsupport.ide.inspections.flow

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoAndExpr
import io.github.golangsupport.lang.psi.GoArgumentList
import io.github.golangsupport.lang.psi.GoBinaryExpr
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoDeferStatement
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoOrExpr
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.semantic.flow.GoControlFlow
import io.github.golangsupport.semantic.flow.GoFlowNode
import io.github.golangsupport.semantic.psi.GoPsiUtil.condition
import io.github.golangsupport.semantic.psi.GoPsiUtil.expressions

/**
 * `mu.Lock()` / `mu.RLock()` (`sync.Mutex`, `sync.RWMutex`, on a local, a parameter or a field path of one, receiver included)
 * and some path from it reaches a `return` or the end of the function without the matching `Unlock()` / `RUnlock()`, called or
 * deferred. The mutex is tracked by the text of its path (`s.mu`) until its root variable is assigned.
 *
 * Quiet, to avoid false positives: functions whose name says they lock (`lock…`, `…Lock`, `acquire…`); functions with no matching
 * unlock of that path at all (the lock is handed over on purpose); paths through a panic; paths that pass the mutex to a call,
 * mention a method value `mu.Unlock` (`return mu.Unlock`) or call a method whose name contains `unlock` / `release`; paths
 * re-testing a condition that guards the `Lock` (`if c { mu.Lock() } … if c { mu.Unlock() }`).
 */
class GoLockNotReleasedInspection : GoFlowInspectionBase() {

    override fun check(flow: GoControlFlow, holder: ProblemsHolder) {
        if (namedAsLocking(flow.owner)) return
        val refs by lazy { PsiTreeUtil.findChildrenOfType(flow.body, GoReferenceExpression::class.java) }
        for (node in flow.nodes) {
            if (!flow.isReachable(node) || node.kind != GoFlowNode.Kind.STATEMENT) continue
            val statement = node.element as? GoSimpleStatement ?: continue
            val call = GoFlowChecks.unparen(statement.expressions.singleOrNull()) as? GoCallExpr ?: continue
            val method = GoFlowChecks.unparen(call.expression) as? GoReferenceExpression ?: continue
            val lockName = method.identifier.text
            val unlockName = PAIRS[lockName] ?: continue
            val receiver = GoFlowChecks.unparen(method.expression) ?: continue
            val root = rootOf(receiver) ?: continue
            val variable = flow.variableOf(root) ?: continue
            if (!isSyncLock(call)) continue
            val path = normalize(receiver.text)
            val ownUnlocks = refs.filter { it.identifier.text == unlockName && it.expression?.let { q -> normalize(q.text) } == path }
            if (ownUnlocks.isEmpty()) continue
            // `defer mu.Unlock()` anywhere: a later Lock re-acquires after a temporary Unlock (net/http Server.Close, x/term) and the
            // deferred call releases it; an Unlock under a bare flag (`if shouldUnlock`, testing.go) is released on purpose by state
            if (ownUnlocks.any { u -> PsiTreeUtil.getParentOfType(u, GoDeferStatement::class.java) != null || underFlag(u) }) continue
            // the first lock operation of the function is an Unlock: it runs with the lock held and hands it back (x/term handleKey)
            val firstLock = refs.filter { it.identifier.text == lockName && it.expression?.let { q -> normalize(q.text) } == path }.minOf { it.textRange.startOffset }
            if (ownUnlocks.minOf { it.textRange.startOffset } < firstLock) continue
            val stops = HashSet<GoFlowNode>()
            for (ref in refs) {
                val name = ref.identifier.text
                val q = ref.expression?.let { normalize(it.text) }
                val releases = q == path && (name == "Unlock" || name == "RUnlock" || name == lockName) ||
                    name.lowercase().let { "unlock" in it || "release" in it } ||
                    normalize(ref.text) == path && handsOver(ref)
                if (releases) GoResourceFlow.nodeAt(flow, ref)?.let { stops += it }
            }
            val guards = guardConditions(statement)
            val leak = GoResourceFlow.leakingReturn(
                node,
                stops = { n ->
                    n in stops || n === node || GoResourceFlow.writes(n, variable) ||
                        n.kind == GoFlowNode.Kind.CONDITION && n.element?.let { normalize(it.text) } in guards
                },
            )
            if (leak != null) holder.registerProblem(call, "$path.$lockName() is not released on all paths", ProblemHighlightType.WARNING)
        }
    }

    /** Inside `if flag { … }` whose condition is a single identifier (a bool that remembers the lock is held). */
    private fun underFlag(ref: PsiElement): Boolean {
        val ifStatement = PsiTreeUtil.getParentOfType(ref, GoIfStatement::class.java) ?: return false
        return GoFlowChecks.unparen(ifStatement.condition) is GoReferenceExpression
    }

    /** `f(mu)`, `f(&s.mu)`: the mutex is passed to a call that may unlock it. */
    private fun handsOver(ref: GoReferenceExpression): Boolean {
        val e: PsiElement = (ref.parent as? GoUnaryExpr)?.takeIf { it.and != null } ?: ref
        return e.parent is GoArgumentList
    }

    /** The conditions (and their `&&` / `||` operands) of the `if` statements around [statement], as normalized text. */
    private fun guardConditions(statement: PsiElement): Set<String> {
        val out = HashSet<String>()
        var e: PsiElement? = statement.parent
        while (e != null && e !is GoFunctionOrMethodDeclaration && e !is GoFunctionLit) {
            if (e is GoIfStatement) e.condition?.let { c -> collectOperands(c, out) }
            e = e.parent
        }
        return out
    }

    private fun collectOperands(e: PsiElement, out: MutableSet<String>) {
        out += normalize(e.text).removePrefix("!")
        val x = GoFlowChecks.unparen(e)
        when {
            x is GoUnaryExpr && x.not != null -> x.expression?.let { collectOperands(it, out) }
            x is GoAndExpr || x is GoOrExpr ->
                (x as GoBinaryExpr).let { b -> b.left?.let { collectOperands(it, out) }; b.right?.let { collectOperands(it, out) } }
        }
    }

    /** The leftmost identifier of a selector chain `a.b.c`, or null for anything else (calls, index expressions). */
    private fun rootOf(e: PsiElement): GoReferenceExpression? {
        var x = e
        while (true) {
            val ref = x as? GoReferenceExpression ?: return null
            x = GoFlowChecks.unparen(ref.expression) ?: return ref
        }
    }

    /** `Lock` / `RLock` of `sync.Mutex` / `sync.RWMutex` (embedded too). */
    private fun isSyncLock(call: GoCallExpr): Boolean {
        val (target, path) = GoResourceFlow.callee(call) ?: return false
        return path == "sync" && target is GoMethodDeclaration && GoResourceFlow.receiverTypeName(target) in MUTEXES
    }

    private fun namedAsLocking(owner: PsiElement): Boolean {
        val name = (owner as? GoNamedElement)?.name?.lowercase() ?: return false
        return name.startsWith("lock") || name.endsWith("lock") || name.endsWith("locked") || name.startsWith("acquire") || name.endsWith("acquire")
    }

    private fun normalize(text: String): String = text.filterNot { it.isWhitespace() }

    private companion object {
        val PAIRS = mapOf("Lock" to "Unlock", "RLock" to "RUnlock")
        val MUTEXES = setOf("Mutex", "RWMutex")
    }
}
