package io.github.golangsupport.ide.rules.builtin.simple

import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.condition
import io.github.golangsupport.semantic.psi.GoPsiUtil.forClause
import io.github.golangsupport.semantic.psi.GoPsiUtil.rangeClause
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoConstant

/**
 * staticcheck S1006: `for true { ... }` is `for { ... }`. The condition is a name of a constant `true` of type `bool` or untyped bool
 * (`true` itself or `const forever = true`); a custom bool type is left alone, like staticcheck does.
 */
class GoForTrueRule : GoSimpleStatementRule() {
    override val id: String get() = "S1006"
    override val title: String get() = "Use for { ... } for infinite loops"
    override val description: String get() = "For infinite loops, using <code>for { ... }</code> is the most idiomatic choice."

    override fun checkStatement(statement: GoStatement, ctx: GoRuleContext) {
        if (statement !is GoForStatement || !isForTrue(statement, ctx)) return
        ctx.report(statement, GoSimplePsi.keywordRange(statement), "should use for {} instead of for true {}",
            *GoRewriteFix.offer("Remove the condition", statement, ctx, ::fix))
    }

    private fun isForTrue(loop: GoForStatement, ctx: GoRuleContext): Boolean {
        if (loop.forClause != null || loop.rangeClause != null) return false
        val cond = loop.condition ?: return false
        if (!GoSimplePsi.isIdent(cond)) return false
        if (GoSimplePsi.target(cond, ctx) !is GoConstDefinition) return false
        val type = ctx.typeOf(cond) as? GoBasicType ?: return false
        if (type.kind != GoBasicKind.BOOL && type.kind != GoBasicKind.UNTYPED_BOOL) return false
        return ctx.semantic.constantValue(cond) == GoConstant.Bool(true)
    }

    private fun fix(element: PsiElement, ctx: GoRuleContext): List<GoEditPlan.Edit>? {
        val loop = element as? GoForStatement ?: return null
        if (!isForTrue(loop, ctx)) return null
        val block = loop.block ?: return null
        if (GoSimplePsi.hasCommentsBetween(loop.`for`, block)) return null
        return listOf(GoEditPlan.Edit(loop.`for`.textRange.endOffset, block.textRange.startOffset, " "))
    }
}
