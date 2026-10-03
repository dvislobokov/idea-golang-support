package io.github.golangsupport.ide.rules.builtin.statements

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.ide.rules.builtin.simple.GoRewriteFix
import io.github.golangsupport.ide.rules.builtin.simple.GoSimplePsi
import io.github.golangsupport.ide.rules.builtin.staticcheck.GoExpressionPsi
import io.github.golangsupport.ide.rules.builtin.staticcheck.GoReplaceWithTextFix
import io.github.golangsupport.ide.rules.builtin.staticcheck.GoStaticcheckPsi
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.semantic.psi.GoPsiUtil.hasEllipsis
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSliceType

/** staticcheck SA3001: `b.N = n` in a benchmark (`b` a `*testing.B`) distorts the results. */
class GoBenchmarkNRule : GoSuspiciousStatementRule() {
    override val id: String get() = "SA3001"
    override val title: String get() = "Assigning to b.N in benchmarks distorts the results"
    override val description: String get() =
        "The testing package dynamically sets <code>b.N</code> to improve the reliability of benchmarks and uses it in computations to " +
            "determine the duration of a single operation. Benchmark code must not alter <code>b.N</code> as this would falsify results."

    override fun checkStatement(statement: GoStatement, ctx: GoRuleContext) {
        val (lhs, _) = GoSimplePsi.simpleAssignment(statement) ?: return
        val sel = lhs as? GoReferenceExpression ?: return
        val x = sel.expression ?: return
        if (sel.identifier.text != "N") return
        val type = ctx.typeOf(x) as? GoPointerType ?: return
        if (!GoExpressionPsi.isNamed(type.elem, "testing", "B")) return
        ctx.report(statement, "should not assign to ${GoExpressionPsi.render(sel)}")
    }
}

/**
 * staticcheck SA4029: `x = sort.IntSlice(x)` converts, it does not sort; `sort.Ints(x)` was meant (likewise `Float64Slice`,
 * `StringSlice`). Only for `x` of an unnamed slice type; the fix writes the call.
 */
class GoSortTypeConversionRule : GoSuspiciousStatementRule() {
    override val id: String get() = "SA4029"
    override val title: String get() = "Ineffective attempt at sorting slice"
    override val description: String get() =
        "<code>sort.Float64Slice</code>, <code>sort.IntSlice</code>, and <code>sort.StringSlice</code> are types, not functions. Doing " +
            "<code>x = sort.StringSlice(x)</code> does nothing, especially not sort any values. The correct usage is " +
            "<code>sort.Sort(sort.StringSlice(x))</code> or <code>sort.StringSlice(x).Sort()</code>, but there are more convenient helpers, " +
            "namely <code>sort.Float64s</code>, <code>sort.Ints</code>, and <code>sort.Strings</code>."

    override fun checkStatement(statement: GoStatement, ctx: GoRuleContext) {
        val m = match(statement, ctx) ?: return
        ctx.report(statement, "sort.${m.type} is a type, not a function, and ${GoExpressionPsi.render(m.call)} doesn't sort your values; consider using sort.${m.alt} instead",
            *GoRewriteFix.offer("Replace with call to sort.${m.alt}", statement, ctx, ::fix))
    }

    private class Match(val call: GoCallExpr, val callee: GoReferenceExpression, val target: GoExpression, val type: String, val alt: String)

    private fun match(statement: PsiElement, ctx: GoRuleContext): Match? {
        val (lhs, rhs) = GoSimplePsi.simpleAssignment(statement) ?: return null
        val target = lhs as? GoReferenceExpression ?: return null
        if (target.expression != null) return null
        val call = rhs as? GoCallExpr ?: return null
        val callee = call.expression as? GoReferenceExpression ?: return null
        val type = callee.identifier.text
        val alt = ALTERNATIVES[type] ?: return null
        val arg = GoStaticcheckPsi.arguments(call)?.singleOrNull() as? GoReferenceExpression ?: return null
        if (arg.expression != null || arg.identifier.text != target.identifier.text) return null
        val spec = ctx.resolve(callee).singleOrNull() as? GoTypeSpec ?: return null
        if (spec.name != type || GoLintPsi.packagePath(spec) != "sort") return null
        if (ctx.typeOf(target) !is GoSliceType) return null // `x = sort.StringSlice(x)` with x a sort.StringSlice is fine
        return Match(call, callee, target, type, alt)
    }

    private fun fix(element: PsiElement, ctx: GoRuleContext): List<GoEditPlan.Edit>? {
        val m = match(element, ctx) ?: return null
        if (GoSimplePsi.hasComments(element)) return null
        val qualifier = m.callee.expression?.let { "${it.text}." } ?: ""
        return GoSimplePsi.replace(element, "$qualifier${m.alt}(${m.target.text})")
    }

    private companion object {
        val ALTERNATIVES = mapOf("Float64Slice" to "Float64s", "IntSlice" to "Ints", "StringSlice" to "Strings")
    }
}

/** staticcheck SA4021: `append(y)` with nothing to append is just `y`. The fix drops the call. */
class GoSingleArgAppendRule : GoSuspiciousCallRule() {
    override val id: String get() = ID
    override val title: String get() = "x = append(y) is equivalent to x = y"

    override fun checkCall(call: GoCallExpr, ctx: GoRuleContext) {
        val arg = singleArgAppend(call, ctx) ?: return
        ctx.report(call, "x = append(y) is equivalent to x = y", GoReplaceWithTextFix("Replace with the appended slice", arg.text))
    }

    companion object {
        const val ID = "SA4021"

        /** The only argument of `append(x)` (the builtin, no `...`), or null. */
        fun singleArgAppend(call: GoCallExpr, ctx: GoRuleContext): GoExpression? {
            val callee = call.expression as? GoReferenceExpression ?: return null
            if (callee.expression != null || callee.identifier.text != "append") return null
            val list = call.argumentList ?: return null
            if (list.hasEllipsis) return null
            val arg = GoStaticcheckPsi.arguments(call)?.singleOrNull() ?: return null
            if (GoExpressionPsi.builtinName(callee, ctx) != "append") return null
            return arg
        }
    }
}

/** govet `appends`: `append(s)` with no values; quiet while SA4021 (the same check) runs. */
class GoVetAppendsRule : GoSuspiciousCallRule() {
    override val id: String get() = "govet:appends"
    override val linter: String get() = "govet"
    override val title: String get() = "Append with no values"
    override val description: String get() = "govet <code>appends</code>: a call of <code>append</code> with only the slice adds nothing. Same check as SA4021."

    override fun checkCall(call: GoCallExpr, ctx: GoRuleContext) {
        val arg = GoSingleArgAppendRule.singleArgAppend(call, ctx) ?: return
        if (GoStaticcheckPsi.enabled(GoSingleArgAppendRule.ID, ctx)) return
        ctx.report(call, "append with no values", GoReplaceWithTextFix("Replace with the appended slice", arg.text))
    }
}

/**
 * govet `atomic`: `x = atomic.AddInt64(&x, 1)` (or `*p = atomic.AddInt64(p, 1)`) overwrites the atomic update with a plain write.
 * The fix keeps the call and drops the assignment.
 */
class GoVetAtomicRule : GoVetStatementRule() {
    override val id: String get() = "govet:atomic"
    override val title: String get() = "Direct assignment to an atomic value"
    override val description: String get() =
        "govet <code>atomic</code>: <code>x = atomic.AddUint64(&amp;x, 1)</code> is not atomic: the result is written back with an ordinary store."

    override fun checkStatement(statement: GoStatement, ctx: GoRuleContext) {
        val s = GoStatementsPsi.unwrap(statement)
        val pairs: List<Pair<GoExpression, GoExpression>> = when (s) {
            is GoAssignmentStatement -> {
                val lhs = s.leftHandExprList?.expressionList ?: return
                if (lhs.size != s.expressionList.size) return
                lhs.zip(s.expressionList)
            }
            is GoShortVarDeclaration -> return // vet: a single `x := ...` is skipped; several define new names that `&x` cannot mean
            else -> return
        }
        for ((left, right) in pairs) {
            val call = right as? GoCallExpr ?: continue
            if (!isAtomicAdd(call, ctx)) continue
            val arg = GoStaticcheckPsi.arguments(call)?.takeIf { it.size == 2 }?.first() ?: continue
            val broken = when {
                arg is GoUnaryExpr && arg.and != null -> arg.expression?.let { GoExpressionPsi.sameCode(left, it) } == true
                left is GoUnaryExpr && left.mul != null -> left.expression?.let { GoExpressionPsi.sameCode(it, arg) } == true
                else -> false
            }
            if (!broken) continue
            val fixes = if (pairs.size == 1 && (s as? GoAssignmentStatement)?.assignOp?.assign != null) {
                GoRewriteFix.offer("Remove the assignment", left, ctx, ::fix)
            } else emptyArray()
            ctx.report(left, "direct assignment to atomic value", *fixes)
        }
    }

    private fun isAtomicAdd(call: GoCallExpr, ctx: GoRuleContext): Boolean {
        val ref = GoLintPsi.calleeReference(call) ?: return false
        if (ref.identifier.text !in ADDS) return false
        return GoStaticcheckPsi.calleeKey(ref, ctx)?.removePrefix("sync/atomic.") in ADDS
    }

    private fun fix(element: PsiElement, @Suppress("UNUSED_PARAMETER") ctx: GoRuleContext): List<GoEditPlan.Edit>? {
        val s = PsiTreeUtil.getParentOfType(element, GoAssignmentStatement::class.java) ?: return null
        if (s.leftHandExprList?.expressionList?.singleOrNull() !== element) return null
        val call = s.expressionList.singleOrNull() as? GoCallExpr ?: return null
        if (GoSimplePsi.commentsOutside(s, listOf(call.textRange))) return null
        return GoSimplePsi.replace(s, call.text)
    }

    private companion object {
        val ADDS = setOf("AddInt32", "AddInt64", "AddUint32", "AddUint64", "AddUintptr")
    }
}
