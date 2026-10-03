package io.github.golangsupport.ide.rules.builtin.simple

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.lang.psi.GoBinaryExpr
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoConditionalExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoIndexOrSliceExpr
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoReturnStatement
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.semantic.psi.GoPsiUtil.block
import io.github.golangsupport.semantic.psi.GoPsiUtil.condition
import io.github.golangsupport.semantic.psi.GoPsiUtil.elseStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.initStatement

/**
 * staticcheck S1008: `if c { return true }; return false` at the end of a statement list is `return c` (`return !c` for the other
 * order, a comparison negated by its operator). Not after another `if` (a series of ifs reads better as is), not when either
 * statement is commented, not for `&&` / `||` / arithmetic conditions.
 */
class GoIfReturnBoolRule : GoSimpleStatementRule() {
    override val id: String get() = "S1008"
    override val title: String get() = "Simplify returning boolean expression"
    override val description: String get() = "<code>if &lt;expr&gt; { return true }; return false</code> is <code>return &lt;expr&gt;</code>."

    private class Match(val statement: GoIfStatement, val last: GoReturnStatement, val cond: GoExpression, val first: String, val second: String)

    override fun checkStatement(statement: GoStatement, ctx: GoRuleContext) {
        if (statement !is GoIfStatement) return
        val m = match(statement, ctx) ?: return
        val replacement = replacement(m, ctx)
        ctx.report(statement, GoSimplePsi.keywordRange(statement),
            "should use 'return $replacement' instead of 'if ${render(m.cond)} { return ${m.first} }; return ${m.second}'",
            *GoRewriteFix.offer("Replace with 'return $replacement'", statement, ctx, ::fix))
    }

    private fun match(statement: GoIfStatement, ctx: GoRuleContext): Match? {
        val list = GoSimplePsi.list(statement) ?: return null
        val all = GoSimplePsi.statements(list)
        if (all.size < 2 || all[all.size - 2] !== statement) return null
        if (all.size >= 3 && all[all.size - 3] is GoIfStatement) return null
        val last = all.last() as? GoReturnStatement ?: return null
        if (statement.initStatement != null || statement.elseStatement != null) return null
        val cond = statement.condition ?: return null
        val inner = statement.block?.statementList?.singleOrNull() as? GoReturnStatement ?: return null
        val first = boolResult(inner, ctx) ?: return null
        val second = boolResult(last, ctx) ?: return null
        if (first == second) return null
        if (cond is GoBinaryExpr && cond !is GoConditionalExpr) return null
        if (commented(statement) || commented(last)) return null
        return Match(statement, last, cond, first, second)
    }

    /** `true` / `false` of `return true` (the predeclared constants). */
    private fun boolResult(ret: GoReturnStatement, ctx: GoRuleContext): String? {
        val e = ret.expressionList.singleOrNull() ?: return null
        val name = GoSimplePsi.identName(e) ?: return null
        if (name != "true" && name != "false") return null
        return name.takeIf { GoSimplePsi.isBuiltin(e, name, ctx) }
    }

    /** A comment in [s], right before it or after it on its line (go/ast's comment map ties those to the statement). */
    private fun commented(s: PsiElement): Boolean {
        if (GoSimplePsi.hasComments(s)) return true
        val text = s.containingFile.node.chars
        var prev = PsiTreeUtil.prevLeaf(s)
        while (prev != null && isBlank(prev)) prev = PsiTreeUtil.prevLeaf(prev)
        if (prev is PsiComment && !text.subSequence(prev.textRange.endOffset, s.textRange.startOffset).contains("\n\n")) return true
        var next = PsiTreeUtil.nextLeaf(s)
        while (next != null && isBlank(next)) next = PsiTreeUtil.nextLeaf(next)
        return next is PsiComment && !text.subSequence(s.textRange.endOffset, next.textRange.startOffset).contains('\n')
    }

    private fun isBlank(e: PsiElement): Boolean = e is PsiWhiteSpace || e.node.elementType == GoTypes.SEMICOLON_SYNTHETIC

    private fun replacement(m: Match, ctx: GoRuleContext): String = if (m.first == "false") negate(m.cond, ctx) else render(m.cond)

    /** One line of the expression, like go/printer prints it in a message. */
    private fun render(e: PsiElement): String = e.text.replace(Regex("\\s*\\n\\s*"), " ")

    /** `!c` with the comparison flipped, a `!` removed, or parentheses added where needed (staticcheck's negate). */
    private fun negate(e: GoExpression, ctx: GoRuleContext): String = when (e) {
        is GoConditionalExpr -> {
            val left = e.left
            val right = e.right
            val op = when {
                e.eql != null -> "!="
                e.neq != null -> "=="
                e.lss != null -> ">="
                e.leq != null -> ">"
                e.geq != null -> "<"
                // len/cap/copy are never negative: `len(x) > 0` negated is `len(x) == 0`
                e.gtr != null -> if (lenLike(left, ctx) && GoSimplePsi.intLiteral(right, "0")) "==" else "<="
                else -> null
            }
            if (op == null || right == null) "!(${render(e)})" else "${render(left)} $op ${render(right)}"
        }
        is GoReferenceExpression -> if (e.expression == null) "!${render(e)}" else "!(${render(e)})"
        is GoCallExpr, is GoIndexOrSliceExpr -> "!${render(e)}"
        is GoUnaryExpr -> when {
            e.not != null -> e.expression?.let(::render) ?: "!(${render(e)})"
            else -> "!${render(e)}"
        }
        else -> "!(${render(e)})"
    }

    private fun lenLike(e: GoExpression?, ctx: GoRuleContext): Boolean =
        e is GoCallExpr && listOf("len", "cap", "copy").any { GoSimplePsi.isBuiltinCall(e, it, ctx) }

    private fun fix(element: PsiElement, ctx: GoRuleContext): List<GoEditPlan.Edit>? {
        val statement = element as? GoIfStatement ?: return null
        val m = match(statement, ctx) ?: return null
        val cond = replacement(m, ctx)
        if (m.cond.text.contains('\n')) return null
        return listOf(GoEditPlan.Edit(statement.textRange.startOffset, m.last.textRange.endOffset, "return $cond"))
    }
}
