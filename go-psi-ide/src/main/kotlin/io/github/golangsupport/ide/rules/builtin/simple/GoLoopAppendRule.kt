package io.github.golangsupport.ide.rules.builtin.simple

import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.hasEllipsis
import io.github.golangsupport.semantic.psi.GoPsiUtil.rangeClause
import io.github.golangsupport.semantic.types.GoTypePredicates

/**
 * staticcheck S1011: a loop appending every element (`for _, e := range y { x = append(x, e) }`, `for i := range y { x = append(x, y[i]) }`,
 * `for i := range y { v := y[i]; x = append(x, v) }`) is `x = append(x, y...)` when `x` and `y` have identical types.
 */
class GoLoopAppendRule : GoSimpleStatementRule() {
    override val id: String get() = "S1011"
    override val title: String get() = "Use a single append to concatenate two slices"
    override val description: String get() = "<code>for _, e := range y { x = append(x, e) }</code> is <code>x = append(x, y...)</code>."

    private class Match(val lhs: GoExpression, val x: GoExpression, val defined: Boolean)

    override fun checkStatement(statement: GoStatement, ctx: GoRuleContext) {
        if (statement !is GoForStatement) return
        val m = match(statement, ctx) ?: return
        ctx.report(statement, GoSimplePsi.keywordRange(statement), "should replace loop with ${text(m)}",
            *GoRewriteFix.offer("Replace loop with call to append", statement, ctx, ::fix))
    }

    private fun text(m: Match): String = "${m.lhs.text} = append(${m.lhs.text}, ${m.x.text}...)"

    private fun match(loop: GoForStatement, ctx: GoRuleContext): Match? {
        val range = loop.rangeClause ?: return null
        val vars: List<PsiElement> = range.leftHandExprList?.expressionList ?: range.varDefinitionList
        if (vars.isEmpty() || vars.size > 2) return null
        val x = range.expression ?: return null
        val body = loop.block?.statementList ?: return null
        val key = vars[0]
        val value = vars.getOrNull(1)
        val valueName: String
        val idx: String?
        val appendStatement: GoStatement
        when {
            value != null -> {
                // for _, e := range y { x = append(x, e) }
                if (!GoSimplePsi.isBlank(key) || GoSimplePsi.isBlank(value) || body.size != 1) return null
                valueName = value.text
                idx = null
                appendStatement = body[0]
            }
            body.size == 1 -> {
                // for i := range y { x = append(x, y[i]) }
                if (GoSimplePsi.isBlank(key)) return null
                idx = key.text
                valueName = ""
                appendStatement = body[0]
            }
            body.size == 2 -> {
                // for i := range y { v := y[i]; x = append(x, v) }
                if (GoSimplePsi.isBlank(key)) return null
                idx = key.text
                val decl = body[0] as? GoShortVarDeclaration ?: return null
                val v = decl.varDefinitionList.singleOrNull() ?: return null
                if (!isElement(decl.expressionList.singleOrNull(), x, idx)) return null
                valueName = v.text
                appendStatement = body[1]
            }
            else -> return null
        }
        val (lhs, rhs) = GoSimplePsi.simpleAssignment(appendStatement) ?: return null
        val call = GoSimplePsi.unparen(rhs) as? GoCallExpr ?: return null
        if (!GoSimplePsi.isBuiltinCall(call, "append", ctx) || call.argumentList?.hasEllipsis == true) return null
        val args = GoSimplePsi.args(call)
        if (args.size != 2 || args[0] !is GoExpression || GoSimplePsi.norm(args[0]) != GoSimplePsi.norm(lhs)) return null
        val element = args[1] as? GoExpression ?: return null
        if (valueName.isNotEmpty()) {
            if (GoSimplePsi.identName(GoSimplePsi.unparen(element)) != valueName || GoSimplePsi.mentions(lhs, valueName)) return null
        } else if (!isElement(element, x, idx!!)) return null
        if (idx != null && (!GoSimplePsi.isPure(x) || GoSimplePsi.mentions(lhs, idx))) return null
        if (!GoSimplePsi.isPure(lhs)) return null
        val src = ctx.typeOf(x)
        val dst = ctx.typeOf(lhs)
        if (!GoTypePredicates.isKnown(src) || !GoTypePredicates.isKnown(dst) || !GoTypePredicates.identical(src, dst)) return null
        return Match(lhs, x, range.define != null)
    }

    /** `y[i]` for the range operand `y` and index `i`. */
    private fun isElement(e: GoExpression?, x: GoExpression, idx: String): Boolean {
        val (operand, index) = GoSimplePsi.index(e) ?: return false
        return GoSimplePsi.norm(operand) == GoSimplePsi.norm(x) && GoSimplePsi.identName(GoSimplePsi.unparen(index)) == idx
    }

    private fun fix(element: PsiElement, ctx: GoRuleContext): List<GoEditPlan.Edit>? {
        val loop = element as? GoForStatement ?: return null
        val m = match(loop, ctx) ?: return null
        // `for k, v = range` leaves k and v set after the loop: the rewrite would change them
        if (!m.defined || GoSimplePsi.hasComments(loop)) return null
        return GoSimplePsi.replace(loop, text(m))
    }
}
