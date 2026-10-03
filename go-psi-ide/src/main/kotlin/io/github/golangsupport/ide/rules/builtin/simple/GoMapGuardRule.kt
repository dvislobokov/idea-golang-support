package io.github.golangsupport.ide.rules.builtin.simple

import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoCompositeLit
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoIncDecStatement
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.block
import io.github.golangsupport.semantic.psi.GoPsiUtil.elements
import io.github.golangsupport.semantic.psi.GoPsiUtil.elseStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.hasEllipsis
import io.github.golangsupport.semantic.psi.GoPsiUtil.initStatement

/**
 * staticcheck S1036: a guard around a map update that the zero value already handles — `if _, ok := m[k]; ok { m[k] = append(m[k], v) }
 * else { m[k] = []T{v} }`, `{ m[k] += n } else { m[k] = n }`, `{ m[k]++ } else { m[k] = 1 }` — is the update alone.
 */
class GoMapGuardRule : GoSimpleStatementRule() {
    override val id: String get() = "S1036"
    override val title: String get() = "Unnecessary guard around map access"
    override val description: String get() =
        "When accessing a map key that doesn't exist yet, one receives a zero value. Often, the zero value is a suitable value, for example when " +
            "using append or doing integer math."

    override fun checkStatement(statement: GoStatement, ctx: GoRuleContext) {
        if (statement !is GoIfStatement || update(statement, ctx) == null) return
        ctx.report(statement, GoSimplePsi.keywordRange(statement), "unnecessary guard around map access",
            *GoRewriteFix.offer("Simplify map access", statement, ctx, ::fix))
    }

    /** The update statement of the `ok` branch when the `else` branch only does what the update does to a missing key. */
    private fun update(statement: GoIfStatement, ctx: GoRuleContext): GoStatement? {
        if (statement.initStatement == null) return null
        val (map, key) = GoSimplePsi.mapLookupGuard(statement, ctx) ?: return null
        val lookup = (statement.initStatement as? io.github.golangsupport.lang.psi.GoShortVarDeclaration)?.expressionList?.singleOrNull() ?: return null
        if (!GoSimplePsi.isPure(map) || !GoSimplePsi.isPure(key)) return null
        val set = statement.block?.statementList?.singleOrNull() ?: return null
        val otherwise = (statement.elseStatement?.statement as? GoBlock)?.statementList?.singleOrNull() ?: return null
        val (elseLhs, elseRhs) = GoSimplePsi.simpleAssignment(otherwise) ?: return null
        val index = GoSimplePsi.norm(lookup)
        if (GoSimplePsi.norm(elseLhs) != index) return null
        when (set) {
            is GoIncDecStatement -> {
                // m[k]++ / m[k] = 1
                if (set.inc == null || set.leftHandExprList?.expressionList?.singleOrNull()?.let(GoSimplePsi::norm) != index) return null
                if (!GoSimplePsi.intLiteral(elseRhs, "1")) return null
            }
            is GoAssignmentStatement -> {
                val lhs = set.leftHandExprList?.expressionList?.singleOrNull() ?: return null
                val rhs = set.expressionList.singleOrNull() ?: return null
                if (GoSimplePsi.norm(lhs) != index) return null
                when {
                    // m[k] += v / m[k] = v
                    set.assignOp.addAssign != null -> if (GoSimplePsi.norm(rhs) != GoSimplePsi.norm(elseRhs)) return null
                    // m[k] = append(m[k], vs...) / m[k] = T{vs...}
                    set.assignOp.assign != null -> if (!isAppendOfLiteral(rhs, elseRhs, index, ctx)) return null
                    else -> return null
                }
            }
            else -> return null
        }
        return set
    }

    private fun isAppendOfLiteral(append: GoExpression, literal: GoExpression, index: String, ctx: GoRuleContext): Boolean {
        val call = GoSimplePsi.unparen(append) as? GoCallExpr ?: return false
        if (!GoSimplePsi.isBuiltinCall(call, "append", ctx) || call.argumentList?.hasEllipsis == true) return false
        val args = GoSimplePsi.args(call)
        if (args.size < 2 || GoSimplePsi.norm(args[0]) != index) return false
        val lit = GoSimplePsi.unparen(literal) as? GoCompositeLit ?: return false
        val elements = lit.literalValue?.elements ?: return false
        if (elements.size != args.size - 1 || elements.any { it.key != null || it.value?.expression == null }) return false
        return elements.indices.all { GoSimplePsi.norm(elements[it].value!!.expression!!) == GoSimplePsi.norm(args[it + 1]) }
    }

    private fun fix(element: PsiElement, ctx: GoRuleContext): List<GoEditPlan.Edit>? {
        val statement = element as? GoIfStatement ?: return null
        val set = update(statement, ctx) ?: return null
        if (GoSimplePsi.hasComments(statement) || set.text.contains('\n')) return null
        return GoSimplePsi.replace(statement, set.text)
    }
}
