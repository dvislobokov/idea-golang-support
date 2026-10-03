package io.github.golangsupport.ide.rules.builtin.simple

import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.lang.psi.GoConditionalExpr
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.semantic.psi.GoPsiUtil.block
import io.github.golangsupport.semantic.psi.GoPsiUtil.condition
import io.github.golangsupport.semantic.psi.GoPsiUtil.elseStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.initStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.rangeClause
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoSliceType

/** staticcheck S1031: `if s != nil { for ... range s { ... } }` around a slice or map: ranging over nil does nothing anyway. */
class GoNilCheckAroundRangeRule : GoSimpleStatementRule() {
    override val id: String get() = "S1031"
    override val title: String get() = "Omit redundant nil check around loop"
    override val description: String get() =
        "You can use range on nil slices and maps, the loop will simply never execute. This makes an additional nil check around the loop unnecessary."

    override fun checkStatement(statement: GoStatement, ctx: GoRuleContext) {
        if (statement !is GoIfStatement || loop(statement, ctx) == null) return
        ctx.report(statement, GoSimplePsi.keywordRange(statement), "unnecessary nil check around range",
            *GoRewriteFix.offer("Remove nil check", statement, ctx, ::fix))
    }

    /** The range loop of `if x != nil { for ... range x {} }`, `x` a variable of a slice or map type. */
    private fun loop(statement: GoIfStatement, ctx: GoRuleContext): GoForStatement? {
        if (statement.initStatement != null || statement.elseStatement != null) return null
        val cond = statement.condition as? GoConditionalExpr ?: return null
        if (cond.neq == null || !GoSimplePsi.isBuiltin(cond.right, "nil", ctx)) return null
        val x = cond.left ?: return null
        val variable = GoSimplePsi.target(x, ctx) ?: return null
        if (variable !is GoVarDefinition && variable !is GoParamDefinition) return null
        val loop = statement.block?.statementList?.singleOrNull() as? GoForStatement ?: return null
        val range = loop.rangeClause?.expression ?: return null
        if (GoSimplePsi.target(range, ctx) != variable) return null
        return loop.takeIf { ctx.typeOf(x).underlying().let { it is GoSliceType || it is GoMapType } }
    }

    private fun fix(element: PsiElement, ctx: GoRuleContext): List<GoEditPlan.Edit>? {
        val statement = element as? GoIfStatement ?: return null
        val loop = loop(statement, ctx) ?: return null
        if (GoSimplePsi.commentsOutside(statement, listOf(loop.textRange))) return null
        return GoSimplePsi.replace(statement, GoSimplePsi.reindented(loop, -1))
    }
}
