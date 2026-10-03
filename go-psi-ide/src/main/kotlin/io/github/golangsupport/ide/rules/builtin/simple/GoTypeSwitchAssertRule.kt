package io.github.golangsupport.ide.rules.builtin.simple

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoConditionalExpr
import io.github.golangsupport.lang.psi.GoIncDecStatement
import io.github.golangsupport.lang.psi.GoRangeClause
import io.github.golangsupport.lang.psi.GoRecvStatement
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoTypeAssertionExpr
import io.github.golangsupport.lang.psi.GoTypeCaseClause
import io.github.golangsupport.lang.psi.GoTypeSwitchStatement
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.semantic.psi.GoPsiUtil.guard
import io.github.golangsupport.semantic.psi.GoPsiUtil.initStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.types

/**
 * staticcheck S1034: `switch x.(type) { case T: use(x.(T)) }` asserts again what the case already knows; `switch x := x.(type)` binds it.
 * Clauses with other assertions (another value, another type) are left alone. The fix binds the variable and drops the assertions; it is
 * not offered when such a clause exists, when an assertion is the two-value form, or when `x` is assigned, compared or addressed in a
 * single-type clause (the bound variable has the case type there).
 */
class GoTypeSwitchAssertRule : GoSimpleStatementRule() {
    override val id: String get() = "S1034"
    override val title: String get() = "Use result of type assertion to simplify cases"
    override val description: String get() = "<code>switch x.(type) { case T: use(x.(T)) }</code> is <code>switch x := x.(type) { case T: use(x) }</code>."

    private class Match(val x: GoReferenceExpression, val offenders: List<GoTypeAssertionExpr>, val fixable: Boolean)

    override fun checkStatement(statement: GoStatement, ctx: GoRuleContext) {
        if (statement !is GoTypeSwitchStatement) return
        val m = match(statement, ctx) ?: return
        val guard = statement.guard ?: return
        val name = m.x.text
        val range = TextRange(m.x.textRange.startOffset, guard.rparen?.textRange?.endOffset ?: guard.textRange.endOffset).shiftLeft(statement.textRange.startOffset)
        ctx.report(statement, range,
            "assigning the result of this type assertion to a variable (switch $name := $name.(type)) could eliminate type assertions in switch cases",
            *GoRewriteFix.offer("Simplify type switch", statement, ctx, ::fix))
    }

    private fun match(statement: GoTypeSwitchStatement, ctx: GoRuleContext): Match? {
        if (statement.initStatement != null) return null
        val guard = statement.guard ?: return null
        if (guard.varDefinition != null) return null
        val x = guard.expression as? GoReferenceExpression ?: return null
        if (!GoSimplePsi.isIdent(x)) return null
        val variable = GoSimplePsi.target(x, ctx) ?: return null
        val all = ArrayList<GoTypeAssertionExpr>()
        var fixable = true
        for (clause in statement.typeCaseClauseList) {
            val type = clause.types.singleOrNull() ?: continue
            var unrelated = false
            val offenders = ArrayList<GoTypeAssertionExpr>()
            for (assertion in PsiTreeUtil.findChildrenOfType(clause, GoTypeAssertionExpr::class.java)) {
                val operand = GoSimplePsi.unparen(assertion.expression)
                if (!GoSimplePsi.isIdent(operand) || GoSimplePsi.target(operand, ctx) != variable || assertion.type?.let(GoSimplePsi::norm) != GoSimplePsi.norm(type)) {
                    unrelated = true
                    break
                }
                if (isCommaOk(assertion)) fixable = false
                offenders += assertion
            }
            if (!unrelated) {
                all += offenders
                if (offenders.isNotEmpty() && changesMeaning(clause, x.text, offenders)) fixable = false
            }
            fixable = fixable && !unrelated
        }
        if (all.isEmpty()) return null
        return Match(x, all, fixable)
    }

    /** `v, ok := x.(T)`: dropping the assertion would not compile. */
    private fun isCommaOk(assertion: GoTypeAssertionExpr): Boolean {
        var e: PsiElement = assertion
        while (e.parent is io.github.golangsupport.lang.psi.GoParenthesesExpr) e = e.parent
        val parent = e.parent
        val lhs = when (parent) {
            is GoAssignmentStatement -> parent.leftHandExprList?.expressionList?.size
            is GoShortVarDeclaration -> parent.varDefinitionList.size
            is GoVarSpec -> parent.varDefinitionList.size
            is GoRecvStatement, is GoRangeClause -> 2
            else -> 1
        }
        return (lhs ?: 1) == 2
    }

    /** `x` assigned, compared with something or addressed in the clause outside the assertions: binding it to the case type may break that. */
    private fun changesMeaning(clause: GoTypeCaseClause, name: String, offenders: List<GoTypeAssertionExpr>): Boolean =
        PsiTreeUtil.findChildrenOfType(clause, GoReferenceExpression::class.java).any { ref ->
            if (ref.expression != null || ref.identifier.text != name || offenders.any { PsiTreeUtil.isAncestor(it, ref, false) }) return@any false
            var e: PsiElement = ref
            while (e.parent is io.github.golangsupport.lang.psi.GoParenthesesExpr) e = e.parent
            val p = e.parent
            p is io.github.golangsupport.lang.psi.GoLeftHandExprList || p is GoConditionalExpr || (p as? GoUnaryExpr)?.and != null || p is GoIncDecStatement
        }

    private fun fix(element: PsiElement, ctx: GoRuleContext): List<GoEditPlan.Edit>? {
        val statement = element as? GoTypeSwitchStatement ?: return null
        val m = match(statement, ctx) ?: return null
        if (!m.fixable) return null
        val name = m.x.text
        return listOf(GoEditPlan.Edit(m.x.textRange.startOffset, m.x.textRange.startOffset, "$name := ")) +
            m.offenders.map { GoEditPlan.Edit(it.textRange.startOffset, it.textRange.endOffset, name) }
    }
}
