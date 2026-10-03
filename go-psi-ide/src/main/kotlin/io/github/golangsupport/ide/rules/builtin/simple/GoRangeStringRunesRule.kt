package io.github.golangsupport.ide.rules.builtin.simple

import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoConversionExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoRangeClause
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.rangeClause
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoSliceType

/**
 * staticcheck S1029: `for _, r := range []rune(s)` is `for _, r := range s` when the index is not used (ranging over the string yields
 * the same runes without allocating). Only the conversion written in the range clause itself.
 */
class GoRangeStringRunesRule : GoSimpleStatementRule() {
    override val id: String get() = "S1029"
    override val title: String get() = "Range over the string directly"
    override val description: String get() =
        "Ranging over a string will yield byte offsets and runes. If the offset isn't used, this is functionally equivalent to converting the string " +
            "to a slice of runes and ranging over that. Ranging directly over the string will be more performant, however, as it avoids allocating " +
            "a new slice, the size of which depends on the length of the string."

    override fun checkStatement(statement: GoStatement, ctx: GoRuleContext) {
        if (statement !is GoForStatement) return
        statement.rangeClause?.let { conversion(it, ctx) } ?: return
        ctx.report(statement, GoSimplePsi.keywordRange(statement), "should range over string, not []rune(string)",
            *GoRewriteFix.offer("Range over the string", statement, ctx, ::fix))
    }

    /** `[]rune(s)` of the range clause with `s` a string and a blank key, with its operand. */
    private fun conversion(range: GoRangeClause, ctx: GoRuleContext): Pair<GoExpression, GoExpression>? {
        val vars = range.leftHandExprList?.expressionList ?: range.varDefinitionList
        if (!GoSimplePsi.isBlank(vars.firstOrNull())) return null
        val x = GoSimplePsi.unparen(range.expression) ?: return null
        val operand = when (x) {
            is GoConversionExpr -> GoPsiUtil.children(x, GoExpression::class.java).singleOrNull()
            // `Runes(s)`: a conversion to a named rune slice type, not a call
            is GoCallExpr -> (GoSimplePsi.args(x).singleOrNull() as? GoExpression)?.takeIf {
                val callee = GoSimplePsi.unparen(x.expression) as? GoReferenceExpression
                callee != null && ctx.resolve(callee).singleOrNull() is GoTypeSpec
            }
            else -> null
        } ?: return null
        val slice = ctx.typeOf(x).underlying() as? GoSliceType ?: return null
        if ((slice.elem.underlying() as? GoBasicType)?.kind != GoBasicKind.INT32) return null
        if ((ctx.typeOf(operand).underlying() as? GoBasicType)?.kind?.isString != true) return null
        return x to operand
    }

    private fun fix(element: PsiElement, ctx: GoRuleContext): List<GoEditPlan.Edit>? {
        val loop = element as? GoForStatement ?: return null
        val (x, operand) = loop.rangeClause?.let { conversion(it, ctx) } ?: return null
        if (GoSimplePsi.hasComments(x)) return null
        val range = loop.rangeClause!!.expression ?: return null
        return listOf(GoEditPlan.Edit(range.textRange.startOffset, range.textRange.endOffset, operand.text))
    }
}
