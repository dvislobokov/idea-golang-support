package io.github.golangsupport.ide.rules.builtin.statements

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.editor.GoEditText
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.ide.rules.GoRuleNeed
import io.github.golangsupport.ide.rules.GoRule
import io.github.golangsupport.ide.rules.builtin.simple.GoRewriteFix
import io.github.golangsupport.ide.rules.builtin.simple.GoSimplePsi
import io.github.golangsupport.ide.rules.builtin.staticcheck.GoStaticcheckCallRule
import io.github.golangsupport.lang.psi.GoBreakStatement
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoCommClause
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoDeferStatement
import io.github.golangsupport.lang.psi.GoExprSwitchStatement
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoReturnStatement
import io.github.golangsupport.lang.psi.GoSelectStatement
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.block
import io.github.golangsupport.semantic.psi.GoPsiUtil.forClause
import io.github.golangsupport.semantic.psi.GoPsiUtil.rangeClause
import io.github.golangsupport.semantic.types.GoConstant
import io.github.golangsupport.lang.psi.GoBlock

/**
 * staticcheck SA4011: an unlabeled `break` ending a case of a `switch` or `select` that sits directly in a loop body leaves the switch,
 * not the loop. Like staticcheck, only the last statement of a case is looked at, or the last statements of an `if` / `else` ending it.
 */
class GoIneffectiveBreakRule : GoSuspiciousStatementRule() {
    override val id: String get() = "SA4011"
    override val title: String get() = "Break statement with no effect. Did you mean to break out of an outer loop?"
    override val needs: Set<GoRuleNeed> get() = GoRule.SYNTAX_ONLY

    override fun checkStatement(statement: GoStatement, ctx: GoRuleContext) {
        if (statement !is GoForStatement) return
        for (s in GoStatementsPsi.statements(statement.block)) {
            val bodies = when (s) {
                is GoExprSwitchStatement -> s.exprCaseClauseList.map { it.statementList }
                is GoSelectStatement -> s.commClauseList.map { it.statementList }
                else -> continue
            }
            for (body in bodies) {
                val last = body.lastOrNull() ?: continue
                val lasts = ArrayList<GoStatement>()
                if (last is GoIfStatement) {
                    lasts += GoStatementsPsi.statements(last.block).lastOrNull() ?: continue
                    (GoStatementsPsi.elseBranch(last) as? GoBlock)?.let { b -> GoStatementsPsi.statements(b).lastOrNull()?.let { lasts += it } }
                } else {
                    lasts += last
                }
                for (b in lasts) {
                    if (b is GoBreakStatement && b.labelRef == null) ctx.report(b, "ineffective break statement. Did you mean to break out of the outer loop?")
                }
            }
        }
    }
}

/**
 * staticcheck SA5002: an empty `for {}` spins; an empty loop whose condition has no calls or receives (`for x {}`) can only end through a
 * data race. `for false {}` is left alone (a debugging aid).
 */
class GoSpinningLoopRule : GoSuspiciousStatementRule() {
    override val id: String get() = "SA5002"
    override val title: String get() = "The empty for loop (for {}) spins and can block the scheduler"

    override fun checkStatement(statement: GoStatement, ctx: GoRuleContext) {
        if (statement !is GoForStatement || statement.rangeClause != null) return
        val body = statement.block ?: return
        if (GoStatementsPsi.statements(body).isNotEmpty()) return
        val clause = statement.forClause
        val cond = if (clause == null) GoStatementsPsi.condition(statement) else {
            val parts = GoSimplePsi.forParts(clause) ?: return
            if (parts.init != null || parts.post != null) return
            parts.cond
        }
        if (cond != null) {
            if (GoStatementsPsi.mayHaveSideEffects(cond)) return
            if (isConstFalse(cond, ctx)) return
            ctx.report(cond, "loop condition never changes or has a race condition")
        }
        ctx.report(statement, GoStatementsPsi.keywordRange(statement), "this loop will spin, using 100% CPU")
    }

    /** A name of a constant `false` (`for false {}`, `const debug = false`). */
    private fun isConstFalse(cond: GoExpression, ctx: GoRuleContext): Boolean {
        val ref = cond as? GoReferenceExpression ?: return false
        if (ref.expression != null) return false
        if (ctx.resolve(ref).singleOrNull() !is GoConstDefinition) return false
        return ctx.semantic.constantValue(cond) == GoConstant.Bool(false)
    }
}

/**
 * staticcheck SA5003: `defer` inside a `for` without condition that has no `return` and no `break` anywhere in its body: the deferred
 * calls pile up and never run. Function literals are not looked into; a defer of a nested condition-less loop is reported by that loop.
 */
class GoDeferInInfiniteLoopRule : GoSuspiciousStatementRule() {
    override val id: String get() = "SA5003"
    override val title: String get() = "Defers in infinite loops will never execute"
    override val needs: Set<GoRuleNeed> get() = GoRule.SYNTAX_ONLY

    override fun checkStatement(statement: GoStatement, ctx: GoRuleContext) {
        if (!isInfinite(statement)) return
        val body = (statement as GoForStatement).block ?: return
        val defers = ArrayList<GoDeferStatement>()
        if (!walk(body, defers)) return
        for (d in defers) {
            if (innermostInfinite(d) === statement) ctx.report(d, "defers in this infinite loop will never run")
        }
    }

    private fun isInfinite(s: PsiElement): Boolean = s is GoForStatement && s.rangeClause == null && GoStatementsPsi.condition(s) == null

    /** Collects the defers under [e]; false when a `return` or `break` may leave the loop. */
    private fun walk(e: PsiElement, defers: MutableList<GoDeferStatement>): Boolean {
        when (e) {
            is GoReturnStatement, is GoBreakStatement -> return false
            is GoFunctionLit -> return true
            is GoDeferStatement -> defers += e
        }
        var child = e.firstChild
        while (child != null) {
            if (!walk(child, defers)) return false
            child = child.nextSibling
        }
        return true
    }

    private fun innermostInfinite(d: GoDeferStatement): PsiElement? {
        var e = d.parent
        while (e != null && e !is GoFunctionLit) {
            if (isInfinite(e)) return e
            e = e.parent
        }
        return null
    }
}

/**
 * staticcheck SA5004: `for { select { ... default: } }`: an empty `default` makes the loop spin instead of blocking. The fix removes the
 * empty branch (not offered when it holds comments).
 */
class GoBusySelectLoopRule : GoSuspiciousStatementRule() {
    override val id: String get() = "SA5004"
    override val title: String get() = "for { select { ... } } with an empty default branch spins"
    override val needs: Set<GoRuleNeed> get() = GoRule.SYNTAX_ONLY

    override fun checkStatement(statement: GoStatement, ctx: GoRuleContext) {
        if (statement !is GoForStatement || !GoStatementsPsi.isBareLoop(statement)) return
        val select = GoStatementsPsi.statements(statement.block).singleOrNull() as? GoSelectStatement ?: return
        val clause = select.commClauseList.firstOrNull { it.commCase?.default != null && it.statementList.isEmpty() } ?: return
        ctx.report(clause, "should not have an empty default case in a for+select loop; the loop will spin",
            *GoRewriteFix.offer("Remove empty default branch", clause, ctx, ::removeClause))
    }

    private fun removeClause(element: PsiElement, @Suppress("UNUSED_PARAMETER") ctx: GoRuleContext): List<GoEditPlan.Edit>? {
        val clause = element as? GoCommClause ?: return null
        if (clause.commCase?.default == null || clause.statementList.isNotEmpty()) return null
        if (PsiTreeUtil.findChildOfType(clause, PsiComment::class.java) != null) return null
        // a comment after an empty clause belongs to the select, not the clause: deleting `default:` would move it to the case above
        var leaf = PsiTreeUtil.nextLeaf(clause)
        while (leaf != null && (leaf is PsiWhiteSpace || leaf is PsiComment || leaf.textLength == 0)) {
            if (leaf is PsiComment) return null
            leaf = PsiTreeUtil.nextLeaf(leaf)
        }
        return listOf(GoEditText.replaceStatement(clause, ""))
    }
}

/**
 * staticcheck SA6000: `regexp.Match`, `MatchReader` or `MatchString` with a constant pattern inside a loop compiles the pattern on every
 * iteration. A call in a loop body, condition or post statement of the same function counts.
 */
class GoRegexpInLoopRule : GoStaticcheckCallRule() {
    override val id: String get() = "SA6000"
    override val title: String get() = "Using regexp.Match or related in a loop, should use regexp.Compile"
    override val calleeNames: Set<String> get() = NAMES

    override fun checkCallee(call: GoCallExpr, callee: String, arguments: List<GoExpression>, ctx: GoRuleContext) {
        if (callee !in CALLEES) return
        val pattern = arguments.firstOrNull() ?: return
        if (ctx.semantic.constantValue(pattern) == null) return
        if (!GoStatementsPsi.inLoop(call)) return
        ctx.report(call, "calling $callee in a loop has poor performance, consider using regexp.Compile")
    }

    private companion object {
        val NAMES = setOf("Match", "MatchReader", "MatchString")
        val CALLEES = setOf("regexp.Match", "regexp.MatchReader", "regexp.MatchString")
    }
}
