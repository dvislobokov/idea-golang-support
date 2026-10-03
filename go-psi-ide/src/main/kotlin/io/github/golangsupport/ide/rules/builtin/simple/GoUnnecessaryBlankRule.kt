package io.github.golangsupport.ide.rules.builtin.simple

import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoRangeClause
import io.github.golangsupport.lang.psi.GoRecvStatement
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.semantic.psi.GoPsiUtil.rangeClause
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoTypePredicates

/**
 * staticcheck S1005: `x, _ = <-ch` is `x = <-ch`, `_ = <-ch` is `<-ch`, `for x, _ := range s` is `for x := range s`, `for _ = range s`
 * is `for range s` (go1.4). Not `x, _ = m[k]` (it says the key may be missing) and not range-over-func, whose variables are not optional.
 */
class GoUnnecessaryBlankRule : GoSimpleStatementRule() {
    override val id: String get() = "S1005"
    override val title: String get() = "Drop unnecessary use of the blank identifier"
    override val description: String get() = "In many cases, assigning to the blank identifier is unnecessary."

    override fun checkStatement(statement: GoStatement, ctx: GoRuleContext) {
        when (statement) {
            is GoForStatement -> checkRange(statement.rangeClause ?: return, ctx)
            else -> {
                val lhs = receiveTargets(statement) ?: return
                when {
                    lhs.size == 2 && GoSimplePsi.isBlank(lhs[1]) ->
                        ctx.report(statement, MESSAGE, *GoRewriteFix.offer("Remove assignment to blank identifier", statement, ctx) { e, _ -> dropSecond(e) })
                    lhs.size == 1 && GoSimplePsi.isBlank(lhs[0]) && statement !is GoShortVarDeclaration ->
                        ctx.report(statement, MESSAGE, *GoRewriteFix.offer("Simplify channel receive operation", statement, ctx) { e, _ -> receiveOnly(e) })
                }
            }
        }
    }

    /** The left-hand side of `lhs = <-ch` / `lhs := <-ch` (a statement or a `select` case), or null when the right side is not one receive. */
    private fun receiveTargets(statement: PsiElement): List<PsiElement>? {
        val (lhs, rhs) = when (statement) {
            is GoAssignmentStatement -> {
                if (statement.assignOp.assign == null) return null
                statement.leftHandExprList?.expressionList.orEmpty() to statement.expressionList.singleOrNull()
            }
            is GoShortVarDeclaration -> statement.varDefinitionList to statement.expressionList.singleOrNull()
            is GoRecvStatement -> (statement.leftHandExprList?.expressionList ?: statement.varDefinitionList) to statement.expression
            else -> return null
        }
        if ((GoSimplePsi.unparen(rhs) as? GoUnaryExpr)?.arrow == null || lhs.isEmpty()) return null
        return lhs
    }

    private fun dropSecond(element: PsiElement): List<GoEditPlan.Edit>? {
        val lhs = receiveTargets(element) ?: return null
        if (lhs.size != 2 || !GoSimplePsi.isBlank(lhs[1]) || GoSimplePsi.hasCommentsBetween(lhs[0], lhs[1])) return null
        return listOf(GoEditPlan.Edit(lhs[0].textRange.endOffset, lhs[1].textRange.endOffset, ""))
    }

    private fun receiveOnly(element: PsiElement): List<GoEditPlan.Edit>? {
        val lhs = receiveTargets(element) ?: return null
        if (lhs.size != 1 || !GoSimplePsi.isBlank(lhs[0])) return null
        val rhs = when (element) {
            is GoAssignmentStatement -> element.expressionList.single()
            is GoRecvStatement -> element.expression ?: return null
            else -> return null
        }
        if (GoSimplePsi.hasCommentsBetween(lhs[0], rhs)) return null
        return listOf(GoEditPlan.Edit(element.textRange.startOffset, rhs.textRange.startOffset, ""))
    }

    private fun checkRange(range: GoRangeClause, ctx: GoRuleContext) {
        val vars = range.leftHandExprList?.expressionList ?: range.varDefinitionList
        if (vars.isEmpty()) return
        val key = vars[0]
        val value = vars.getOrNull(1)
        val reported = when {
            value == null && GoSimplePsi.isBlank(key) -> key
            value != null && GoSimplePsi.isBlank(key) && GoSimplePsi.isBlank(value) -> key
            value != null && !GoSimplePsi.isBlank(key) && GoSimplePsi.isBlank(value) -> value
            else -> return
        }
        val version = GoLintPsi.goVersion(ctx.file)
        if (version != null && version < (1 to 4)) return
        val x = range.expression ?: return
        val type = ctx.typeOf(x)
        if (!GoTypePredicates.isKnown(type) || type.underlying() is GoSignatureType) return
        ctx.report(reported, MESSAGE, *GoRewriteFix.offer("Remove assignment to blank identifier", reported, ctx) { e, _ -> dropRangeBlank(e) })
    }

    private fun dropRangeBlank(element: PsiElement): List<GoEditPlan.Edit>? {
        val range = element.parent as? GoRangeClause ?: element.parent?.parent as? GoRangeClause ?: return null
        val vars = range.leftHandExprList?.expressionList ?: range.varDefinitionList
        val key = vars.firstOrNull() ?: return null
        val value = vars.getOrNull(1)
        if (GoSimplePsi.isBlank(key)) {
            // `for _ = range`, `for _, _ := range`: everything up to `range`
            if (value != null && !GoSimplePsi.isBlank(value)) return null
            val keyword = range.range
            if (GoSimplePsi.hasCommentsBetween(key, keyword)) return null
            return listOf(GoEditPlan.Edit(key.textRange.startOffset, keyword.textRange.startOffset, ""))
        }
        if (value == null || !GoSimplePsi.isBlank(value) || GoSimplePsi.hasCommentsBetween(key, value)) return null
        return listOf(GoEditPlan.Edit(key.textRange.endOffset, value.textRange.endOffset, ""))
    }

    private operator fun Pair<Int, Int>.compareTo(other: Pair<Int, Int>): Int = compareValuesBy(this, other, { it.first }, { it.second })

    private companion object {
        const val MESSAGE = "unnecessary assignment to the blank identifier"
    }
}
