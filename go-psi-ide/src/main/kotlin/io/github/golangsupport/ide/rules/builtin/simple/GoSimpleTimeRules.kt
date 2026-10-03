package io.github.golangsupport.ide.rules.builtin.simple

import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.ide.rules.builtin.staticcheck.GoExpressionPsi
import io.github.golangsupport.ide.rules.builtin.staticcheck.GoStaticcheckPsi
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression

/** `t.Sub(arg)` of `time.Time` with one argument: (receiver, argument), else null. */
private fun timeSub(call: GoCallExpr, ctx: GoRuleContext): Pair<GoExpression, GoExpression>? {
    if (GoSimplePsi.calleeName(call) != "Sub") return null
    val receiver = GoLintPsi.calleeReference(call)?.expression ?: return null
    val arg = GoStaticcheckPsi.arguments(call)?.singleOrNull() ?: return null
    if (GoSimplePsi.callee(call, SUB, ctx) != "time.Time.Sub") return null
    return receiver to arg
}

/** `time.Now()`. */
private fun isNow(e: GoExpression, ctx: GoRuleContext): Boolean =
    e is GoCallExpr && GoSimplePsi.args(e).isEmpty() && GoSimplePsi.isCallTo(e, "time.Now", ctx)

private val SUB = setOf("Sub")

/** staticcheck S1012: `time.Now().Sub(x)` is `time.Since(x)`. */
class GoTimeSinceRule : GoSimpleCallRule() {
    override val id: String get() = "S1012"
    override val title: String get() = "Replace time.Now().Sub(x) with time.Since(x)"
    override val description: String get() = "The <code>time.Since</code> helper has the same effect as using <code>time.Now().Sub(x)</code> but is easier to read."

    override fun checkCall(call: GoCallExpr, ctx: GoRuleContext) {
        val (receiver, _) = timeSub(call, ctx) ?: return
        if (!isNow(receiver, ctx)) return
        ctx.report(call, "should use time.Since instead of time.Now().Sub", *GoRewriteFix.offer("Replace with call to time.Since", call, ctx, ::fix))
    }

    private fun fix(element: PsiElement, ctx: GoRuleContext): List<GoEditPlan.Edit>? {
        val call = element as? GoCallExpr ?: return null
        val (now, arg) = timeSub(call, ctx) ?: return null
        if (now !is GoCallExpr || !isNow(now, ctx)) return null
        if (GoSimplePsi.commentsOutside(call, listOf(arg.textRange))) return null
        val qualifier = GoSimplePsi.qualifier(now) ?: return null
        return GoSimplePsi.replaceWith(call, "${qualifier}Since(${arg.text})")
    }
}

/** staticcheck S1024: `t.Sub(time.Now())` is `time.Until(t)` (Go 1.8). */
class GoTimeUntilRule : GoSimpleCallRule() {
    override val id: String get() = "S1024"
    override val title: String get() = "Replace x.Sub(time.Now()) with time.Until(x)"
    override val description: String get() = "The <code>time.Until</code> helper has the same effect as using <code>x.Sub(time.Now())</code> but is easier to read."

    override fun checkCall(call: GoCallExpr, ctx: GoRuleContext) {
        val (_, arg) = timeSub(call, ctx) ?: return
        if (!isNow(arg, ctx)) return
        if (!GoSimplePsi.goAtLeast(ctx, 1, 8)) return
        ctx.report(call, "should use time.Until instead of t.Sub(time.Now())", *GoRewriteFix.offer("Replace with call to time.Until", call, ctx, ::fix))
    }

    private fun fix(element: PsiElement, ctx: GoRuleContext): List<GoEditPlan.Edit>? {
        val call = element as? GoCallExpr ?: return null
        val (receiver, now) = timeSub(call, ctx) ?: return null
        if (now !is GoCallExpr || !isNow(now, ctx)) return null
        // a *time.Time or a struct embedding time.Time has the method, but time.Until takes a time.Time
        if (!GoExpressionPsi.isNamed(ctx.typeOf(receiver), "time", "Time")) return null
        if (GoSimplePsi.commentsOutside(call, listOf(receiver.textRange))) return null
        val qualifier = GoSimplePsi.qualifier(now) ?: return null
        return GoSimplePsi.replaceWith(call, "${qualifier}Until(${receiver.text})")
    }
}
