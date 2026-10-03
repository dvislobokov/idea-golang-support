package io.github.golangsupport.ide.rules.builtin.simple

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoVarDeclaration
import io.github.golangsupport.lang.psi.GoVarDefinition

/**
 * staticcheck S1021: `var x T` followed by `x = v` is `var x T = v` (one name, no value, the next statement of a block, `v` not reading
 * `x`, and `x` assigned only once in the block).
 */
class GoMergeVarAssignRule : GoSimpleStatementRule() {
    override val id: String get() = "S1021"
    override val title: String get() = "Merge variable declaration and assignment"
    override val description: String get() = "<code>var x uint; x = 1</code> is <code>var x uint = 1</code>."

    private class Match(val decl: GoVarDeclaration, val assign: GoAssignmentStatement, val variable: GoVarDefinition, val value: GoExpression)

    override fun checkStatement(statement: GoStatement, ctx: GoRuleContext) {
        if (statement !is GoVarDeclaration) return
        match(statement, ctx) ?: return
        ctx.report(statement, "should merge variable declaration with assignment on next line",
            *GoRewriteFix.offer("Merge declaration with assignment", statement, ctx, ::fix))
    }

    private fun match(decl: GoVarDeclaration, ctx: GoRuleContext): Match? {
        val block = decl.parent as? GoBlock ?: return null
        if (decl.lparen != null) return null
        val spec = decl.varSpecList.singleOrNull() ?: return null
        val variable = spec.varDefinitionList.singleOrNull() ?: return null
        if (spec.expressionList.isNotEmpty() || spec.type == null) return null
        val assign = GoSimplePsi.next(decl) as? GoAssignmentStatement ?: return null
        val (lhs, rhs) = GoSimplePsi.simpleAssignment(assign) ?: return null
        if (GoSimplePsi.target(lhs, ctx) != variable) return null
        if (GoSimplePsi.refersTo(rhs, variable, ctx)) return null
        if (assignments(block, variable, ctx) >= 2) return null
        return Match(decl, assign, variable, rhs)
    }

    /** Assignment statements of [block] (nested ones too) assigning [variable]. */
    private fun assignments(block: GoBlock, variable: GoVarDefinition, ctx: GoRuleContext): Int {
        val name = variable.name ?: return 0
        return PsiTreeUtil.findChildrenOfType(block, GoAssignmentStatement::class.java).sumOf { a ->
            a.leftHandExprList?.expressionList.orEmpty().count { GoSimplePsi.identName(it) == name && GoSimplePsi.target(it, ctx) == variable }
        }
    }

    private fun fix(element: PsiElement, ctx: GoRuleContext): List<GoEditPlan.Edit>? {
        val decl = element as? GoVarDeclaration ?: return null
        val m = match(decl, ctx) ?: return null
        if (GoSimplePsi.hasComments(decl) || GoSimplePsi.hasComments(m.assign) || GoSimplePsi.hasCommentsBetween(decl, m.assign)) return null
        return listOf(GoEditPlan.Edit(decl.textRange.startOffset, m.assign.textRange.endOffset, "${decl.text} = ${m.value.text}"))
    }
}
