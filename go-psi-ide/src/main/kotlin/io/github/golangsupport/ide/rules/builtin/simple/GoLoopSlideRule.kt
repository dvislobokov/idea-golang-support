package io.github.golangsupport.ide.rules.builtin.simple

import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.lang.psi.GoAddExpr
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoConditionalExpr
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoIncDecStatement
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.forClause
import io.github.golangsupport.semantic.types.GoSliceType

/**
 * staticcheck S1018: `for i := 0; i < n; i++ { bs[i] = bs[offset+i] }` slides elements and is `copy(bs[:n], bs[offset:])`
 * (`bs`, `n` and `offset` plain names, `bs` a slice).
 */
class GoLoopSlideRule : GoSimpleStatementRule() {
    override val id: String get() = "S1018"
    override val title: String get() = "Use copy for sliding elements"
    override val description: String get() =
        "<code>copy()</code> permits using the same source and destination slice, even with overlapping ranges. This makes it ideal for sliding elements in a slice."

    private class Match(val slice: String, val limit: String, val offset: String, val defined: Boolean)

    override fun checkStatement(statement: GoStatement, ctx: GoRuleContext) {
        if (statement !is GoForStatement) return
        match(statement, ctx) ?: return
        if (GoSimplePsi.shadowsBuiltin(statement.containingFile, "copy")) return
        ctx.report(statement, GoSimplePsi.keywordRange(statement), "should use copy() instead of loop for sliding slice elements",
            *GoRewriteFix.offer("Use copy() instead of loop", statement, ctx, ::fix))
    }

    private fun match(loop: GoForStatement, ctx: GoRuleContext): Match? {
        val parts = GoSimplePsi.forParts(loop.forClause ?: return null) ?: return null
        val (name, defined) = when (val init = parts.init) {
            is GoShortVarDeclaration -> {
                if (!GoSimplePsi.intLiteral(init.expressionList.singleOrNull(), "0")) return null
                (init.varDefinitionList.singleOrNull()?.text ?: return null) to true
            }
            is GoAssignmentStatement -> {
                val (lhs, rhs) = GoSimplePsi.simpleAssignment(init) ?: return null
                if (!GoSimplePsi.intLiteral(rhs, "0")) return null
                (GoSimplePsi.identName(lhs) ?: return null) to false
            }
            else -> return null
        }
        val cond = parts.cond as? GoConditionalExpr ?: return null
        if (cond.lss == null || GoSimplePsi.identName(cond.left) != name) return null
        val limit = GoSimplePsi.identName(cond.right) ?: return null
        val post = parts.post as? GoIncDecStatement ?: return null
        if (post.inc == null || GoSimplePsi.identName(post.leftHandExprList?.expressionList?.singleOrNull()) != name) return null
        val body = loop.block?.statementList?.singleOrNull() ?: return null
        val (lhs, rhs) = GoSimplePsi.simpleAssignment(body) ?: return null
        val (dst, dstIndex) = GoSimplePsi.index(lhs) ?: return null
        val slice = GoSimplePsi.identName(dst) ?: return null
        if (GoSimplePsi.identName(dstIndex) != name) return null
        val (src, srcIndex) = GoSimplePsi.index(rhs) ?: return null
        if (GoSimplePsi.identName(src) != slice) return null
        val sum = srcIndex as? GoAddExpr ?: return null
        if (sum.add == null || GoSimplePsi.identName(sum.right) != name) return null
        val offset = GoSimplePsi.identName(sum.left) ?: return null
        if (name == "_" || slice == name || limit == name || offset == name) return null
        if (ctx.typeOf(dst).underlying() !is GoSliceType) return null
        return Match(slice, limit, offset, defined)
    }

    private fun fix(element: PsiElement, ctx: GoRuleContext): List<GoEditPlan.Edit>? {
        val loop = element as? GoForStatement ?: return null
        val m = match(loop, ctx) ?: return null
        // `for i = 0; ...` leaves i set after the loop: the rewrite would change it
        if (!m.defined || GoSimplePsi.hasComments(loop)) return null
        return GoSimplePsi.replace(loop, "copy(${m.slice}[:${m.limit}], ${m.slice}[${m.offset}:])")
    }
}
