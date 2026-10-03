package io.github.golangsupport.ide.rules.builtin.statements

import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.ide.rules.builtin.staticcheck.GoExpressionPsi
import io.github.golangsupport.ide.rules.builtin.staticcheck.GoReplaceWithTextFix
import io.github.golangsupport.ide.rules.builtin.staticcheck.GoStaticcheckPsi
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoDeferStatement
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.semantic.types.GoSignatureType
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project

/**
 * staticcheck SA2001: `mu.Lock()` directly followed by `mu.Unlock()` (or `RLock` / `RUnlock`) in a block: an empty critical section,
 * usually a missing `defer`. Any method pair of these names without parameters and results counts, on the same receiver text.
 */
class GoEmptyCriticalSectionRule : GoSuspiciousStatementRule() {
    override val id: String get() = "SA2001"
    override val title: String get() = "Empty critical section, did you mean to defer the unlock?"
    override val description: String get() =
        "Empty critical sections of the kind <code>mu.Lock(); mu.Unlock()</code> are very often a typo, and <code>defer mu.Unlock()</code> " +
            "was intended. Sometimes they are used to wait on another goroutine; such code should be commented and the problem suppressed."

    override fun checkStatement(statement: GoStatement, ctx: GoRuleContext) {
        val block = statement.parent as? GoBlock ?: return // go/ast BlockStmt only, not case clauses
        val first = methodCall(statement, ctx) ?: return
        val all = GoStatementsPsi.statements(block)
        val next = all.getOrNull(all.indexOf(statement) + 1) ?: return
        if (first.second != "Lock" && first.second != "RLock") return
        val second = methodCall(next, ctx) ?: return
        if (second.second != if (first.second == "Lock") "Unlock" else "RUnlock") return
        if (GoExpressionPsi.render(first.first) != GoExpressionPsi.render(second.first)) return
        if (ctx.file.packageName == "sync_test") return // staticcheck's exception for the sync package's tests
        ctx.report(next, "empty critical section")
    }

    /** `x.M()` as a statement with `M` a method without parameters and results: (x, M). */
    private fun methodCall(s: GoStatement, ctx: GoRuleContext): Pair<GoExpression, String>? {
        val call = GoStatementsPsi.callStatement(s) ?: return null
        val sel = call.expression as? GoReferenceExpression ?: return null
        val x = sel.expression ?: return null
        val name = sel.identifier.text
        if (name != "Lock" && name != "Unlock" && name != "RLock" && name != "RUnlock") return null
        val target = ctx.resolve(sel).singleOrNull()
        if (target !is GoMethodDeclaration && target !is GoMethodSpec) return null
        val sig = ctx.typeOf(sel) as? GoSignatureType ?: return null
        if (sig.params.isNotEmpty() || sig.results.isNotEmpty() || sig.variadic) return null
        return x to name
    }
}

/**
 * staticcheck SA2003: `mu.Lock()` followed by `defer mu.Lock()` (`sync.Mutex.Lock`, `sync.RWMutex.RLock`): the unlock was meant to be
 * deferred. The fix renames the deferred method.
 */
class GoDeferLockRule : GoSuspiciousStatementRule() {
    override val id: String get() = "SA2003"
    override val title: String get() = "Deferred Lock right after locking, likely meant to defer Unlock instead"
    override val description: String get() =
        "Deferring a call to <code>Lock</code> immediately after locking is almost always a typo: <code>mu.Lock(); defer mu.Lock()</code> " +
            "was meant to be <code>mu.Lock(); defer mu.Unlock()</code>."

    override fun checkStatement(statement: GoStatement, ctx: GoRuleContext) {
        if (statement !is GoDeferStatement) return
        val deferred = lockCall(GoLintPsi.unparen(statement.expression) as? GoCallExpr, ctx) ?: return
        val list = GoStatementsPsi.list(statement) ?: return
        val all = GoStatementsPsi.statements(list)
        val previous = all.getOrNull(all.indexOf(statement) - 1) ?: return
        val locked = lockCall(GoStatementsPsi.callStatement(previous), ctx) ?: return
        if (locked.second != deferred.second || !GoExpressionPsi.sameCode(locked.first, deferred.first)) return
        val name = deferred.second
        val alt = if (name == "Lock") "Unlock" else "RUnlock"
        ctx.report(statement, "deferring $name right after having locked already; did you mean to defer $alt?", DeferUnlockFix(alt))
    }

    /** `x.Lock()` of a `sync.Mutex` or `x.RLock()` of a `sync.RWMutex`: (x, method). */
    private fun lockCall(call: GoCallExpr?, ctx: GoRuleContext): Pair<GoExpression, String>? {
        val sel = call?.expression as? GoReferenceExpression ?: return null
        val x = sel.expression ?: return null
        val name = sel.identifier.text
        if (name != "Lock" && name != "RLock") return null
        if (call.argumentList?.let { GoStaticcheckPsi.arguments(call) }?.isNotEmpty() != false) return null
        val key = GoStaticcheckPsi.calleeKey(sel, ctx) ?: return null
        if (key != "sync.Mutex.Lock" && key != "sync.RWMutex.RLock") return null
        return x to name
    }

    private class DeferUnlockFix(private val alt: String) : LocalQuickFix {
        override fun getFamilyName(): String = "Defer $alt"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val defer = descriptor.psiElement as? GoDeferStatement ?: return
            val sel = (GoLintPsi.unparen(defer.expression) as? GoCallExpr)?.expression as? GoReferenceExpression ?: return
            GoReplaceWithTextFix.replace(sel.identifier, alt)
        }
    }
}
