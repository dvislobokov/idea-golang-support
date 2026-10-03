package io.github.golangsupport.ide.rules.builtin.simple

import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.lang.psi.GoAddExpr
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoElseStatement
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoLiteral
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.semantic.psi.GoPsiUtil.block
import io.github.golangsupport.semantic.psi.GoPsiUtil.condition
import io.github.golangsupport.semantic.psi.GoPsiUtil.elseStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.initStatement
import io.github.golangsupport.semantic.types.GoConstant

/**
 * staticcheck S1017: `if strings.HasPrefix(s, p) { s = s[len(p):] }` is `s = strings.TrimPrefix(s, p)` (likewise `HasSuffix` with
 * `s[:len(s)-len(p)]`, and `bytes`); a guard around an unconditional `TrimPrefix` / `TrimSuffix` / `Replace` of the same arguments is redundant.
 */
class GoTrimPrefixRule : GoSimpleStatementRule() {
    override val id: String get() = "S1017"
    override val title: String get() = "Replace manual trimming with strings.TrimPrefix"
    override val description: String get() =
        "Instead of using <code>strings.HasPrefix</code> and manual slicing, use the <code>strings.TrimPrefix</code> function. If the string doesn't " +
            "start with the prefix, the original string will be returned. Using <code>strings.TrimPrefix</code> reduces complexity, and avoids common " +
            "bugs, such as off-by-one mistakes."

    /** [unconditional]: the body already calls the trimming function, the fix keeps it alone; else the fix writes [function]. */
    private class Match(val statement: GoIfStatement, val assigned: GoExpression, val cond: GoCallExpr, val function: String, val unconditional: Boolean)

    override fun checkStatement(statement: GoStatement, ctx: GoRuleContext) {
        if (statement !is GoIfStatement) return
        val m = match(statement, ctx) ?: return
        val pkg = if (m.unconditional) "" else "${packageOf(m, ctx)}."
        ctx.report(statement, GoSimplePsi.keywordRange(statement), "should replace this if statement with an unconditional $pkg${m.function}",
            *GoRewriteFix.offer("Replace with ${m.function.substringAfterLast('.')}", statement, ctx, ::fix))
    }

    private fun packageOf(m: Match, ctx: GoRuleContext): String =
        if (GoSimplePsi.packageFunction(m.cond, "strings", CONDITIONS, ctx) != null) "strings" else "bytes"

    private fun match(statement: GoIfStatement, ctx: GoRuleContext): Match? {
        if (statement.parent is GoElseStatement || statement.parent is GoIfStatement) return null
        if (statement.initStatement != null || statement.elseStatement != null) return null
        val cond = statement.condition as? GoCallExpr ?: return null
        val name = GoSimplePsi.packageFunction(cond, "strings", CONDITIONS, ctx)
        val pkg = if (name != null) "strings" else "bytes"
        val fn = name ?: GoSimplePsi.packageFunction(cond, "bytes", CONDITIONS, ctx) ?: return null
        val condArgs = GoSimplePsi.args(cond)
        if (condArgs.size != 2) return null
        val body = statement.block?.statementList?.singleOrNull() ?: return null
        val (lhs, rhs) = GoSimplePsi.simpleAssignment(body) ?: return null
        if (!GoSimplePsi.sameNonDynamic(condArgs[0], lhs, ctx)) return null
        val call = rhs as? GoCallExpr
        if (call != null) {
            val args = GoSimplePsi.args(call)
            if (args.size < 2 || !GoSimplePsi.sameNonDynamic(condArgs[0], args[0], ctx) || !GoSimplePsi.sameNonDynamic(condArgs[1], args[1], ctx)) return null
            val expected = when (fn) {
                "HasPrefix" -> "TrimPrefix"
                "HasSuffix" -> "TrimSuffix"
                else -> "Replace"
            }
            GoSimplePsi.packageFunction(call, pkg, setOf(expected), ctx) ?: return null
            return Match(statement, lhs, cond, "$pkg.$expected", true)
        }
        val slice = GoSimplePsi.slice(rhs) ?: return null
        if (!GoSimplePsi.sameNonDynamic(slice.operand, condArgs[0], ctx)) return null
        when (fn) {
            "HasPrefix" -> if (slice.high != null || !validOffset(slice.low, condArgs[1], pkg, ctx)) return null
            "HasSuffix" -> {
                if (slice.low != null && ctx.semantic.constantValue(slice.low)?.toBigInteger()?.signum() != 0) return null
                val high = GoSimplePsi.unparen(slice.high) as? GoAddExpr ?: return null
                if (high.sub == null) return null
                if (!isLenOf(high.left, condArgs[0], ctx) || !validOffset(high.right, condArgs[1], pkg, ctx)) return null
            }
            else -> return null
        }
        return Match(statement, lhs, cond, if (fn == "HasPrefix") "TrimPrefix" else "TrimSuffix", false)
    }

    /** `len(arg)` of the same [arg], or (strings only) a literal equal to the byte length of a literal [arg]. */
    private fun validOffset(off: GoExpression?, arg: PsiElement, pkg: String, ctx: GoRuleContext): Boolean = when (val o = GoSimplePsi.unparen(off)) {
        is GoCallExpr -> isLenOf(o, arg, ctx)
        is GoLiteral -> {
            val s = (arg as? GoStringLiteral)?.let { ctx.semantic.constantValue(it) } as? GoConstant.Str
            val n = ctx.semantic.constantValue(o)?.toBigInteger()
            pkg == "strings" && s != null && n != null && n.toLong() == s.value.toByteArray(Charsets.UTF_8).size.toLong()
        }
        else -> false
    }

    private fun isLenOf(e: GoExpression?, arg: PsiElement, ctx: GoRuleContext): Boolean {
        val call = GoSimplePsi.unparen(e) as? GoCallExpr ?: return false
        return GoSimplePsi.isBuiltinCall(call, "len", ctx) && GoSimplePsi.args(call).size == 1 && GoSimplePsi.sameNonDynamic(GoSimplePsi.args(call)[0], arg, ctx)
    }

    private fun fix(element: PsiElement, ctx: GoRuleContext): List<GoEditPlan.Edit>? {
        val statement = element as? GoIfStatement ?: return null
        val m = match(statement, ctx) ?: return null
        val body = statement.block?.statementList?.singleOrNull() ?: return null
        if (GoSimplePsi.hasComments(statement)) return null
        if (m.unconditional) return GoSimplePsi.replace(statement, GoSimplePsi.reindented(body, -1))
        val qualifier = (GoSimplePsi.unparen(m.cond.expression) as? GoReferenceExpression)?.expression?.text ?: return null
        val args = GoSimplePsi.args(m.cond)
        if (args.any { it.text.contains('\n') }) return null
        return GoSimplePsi.replace(statement, "${m.assigned.text} = $qualifier.${m.function}(${args[0].text}, ${args[1].text})")
    }

    private companion object {
        val CONDITIONS = setOf("HasPrefix", "HasSuffix", "Contains")
    }
}
