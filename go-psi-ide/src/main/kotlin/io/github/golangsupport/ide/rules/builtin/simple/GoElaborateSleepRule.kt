package io.github.golangsupport.ide.rules.builtin.simple

import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoRecvStatement
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoSelectStatement
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoUnaryExpr

/**
 * staticcheck S1037: `select { case <-time.After(d): }` is `time.Sleep(d)`. The fix keeps the case body after the call (not when it
 * breaks out of the select or its declarations would clash with the enclosing block).
 */
class GoElaborateSleepRule : GoSimpleStatementRule() {
    override val id: String get() = "S1037"
    override val title: String get() = "Elaborate way of sleeping"
    override val description: String get() =
        "Using a select statement with a single case receiving from the result of <code>time.After</code> is a very elaborate way of sleeping " +
            "that can much simpler be expressed with a simple call to <code>time.Sleep</code>."

    override fun checkStatement(statement: GoStatement, ctx: GoRuleContext) {
        if (statement !is GoSelectStatement) return
        val after = afterCall(statement) ?: return
        if (GoSimplePsi.packageFunction(after, "time", AFTER, ctx) == null) return
        ctx.report(statement, GoSimplePsi.keywordRange(statement), "should use time.Sleep instead of elaborate way of sleeping",
            *GoRewriteFix.offer("Use time.Sleep", statement, ctx) { e, _ -> sleep(e) })
    }

    /** `time.After(d)` of `select { case <-time.After(d): ... }` (a receive without assignment, the only case). */
    private fun afterCall(select: GoSelectStatement): GoCallExpr? {
        val clause = select.commClauseList.singleOrNull() ?: return null
        val recv = clause.commCase?.statement as? GoRecvStatement ?: return null
        if (recv.varDefinitionList.isNotEmpty() || recv.leftHandExprList != null) return null
        val unary = GoSimplePsi.unparen(recv.expression) as? GoUnaryExpr ?: return null
        if (unary.arrow == null) return null
        val call = GoSimplePsi.unparen(unary.expression) as? GoCallExpr ?: return null
        return call.takeIf { GoSimplePsi.args(it).size == 1 }
    }

    private fun sleep(element: PsiElement): List<GoEditPlan.Edit>? {
        val select = element as? GoSelectStatement ?: return null
        val call = afterCall(select) ?: return null
        val qualifier = (GoSimplePsi.unparen(call.expression) as? GoReferenceExpression)?.expression?.text ?: return null
        val arg = GoSimplePsi.args(call).single()
        if (arg.text.contains('\n')) return null
        return GoSimplePsi.inlineClause(select, select.commClauseList.single(), "$qualifier.Sleep(${arg.text})", emptyList())
    }

    private companion object {
        val AFTER = setOf("After")
    }
}
