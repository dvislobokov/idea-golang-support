package io.github.golangsupport.ide.rules.builtin

import io.github.golangsupport.ide.inspections.lint.GoAssignToBlankFix
import io.github.golangsupport.ide.inspections.lint.GoHandleUncheckedErrorFix
import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.ide.inspections.lint.GoUncheckedErrorInspection
import io.github.golangsupport.ide.rules.GoCallRule
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.ide.rules.GoRuleNeed
import io.github.golangsupport.lang.psi.GoCallExpr

/**
 * errcheck: a call standing alone as a statement whose `error` result is dropped ([GoUncheckedErrorInspection.isUnchecked]: errcheck's
 * default excludes, `go` / `defer`, `_ = f()` left alone). While this rule runs, the old inspection `GoUncheckedError` stands down;
 * `//noinspection GoUncheckedError` still suppresses it.
 */
class GoErrcheckRule : GoCallRule() {
    override val id: String get() = ID
    override val linter: String get() = "errcheck"
    override val title: String get() = "Unchecked error"
    override val description: String get() = "A call whose <code>error</code> result is dropped: <code>f.Close()</code> on a line of its own."
    override val needs: Set<GoRuleNeed> get() = TYPES
    override val aliases: Set<String> get() = ALIASES

    override fun checkCall(call: GoCallExpr, ctx: GoRuleContext) {
        if (!GoUncheckedErrorInspection.isUnchecked(call)) return
        val shown = GoLintPsi.calleeReference(call)?.text
        val message = if (shown != null) "Error return value of `$shown` is not checked" else "Error return value is not checked"
        ctx.report(call, message, GoHandleUncheckedErrorFix(), GoAssignToBlankFix())
    }

    companion object {
        const val ID = "errcheck"
        private val TYPES = setOf(GoRuleNeed.TYPES)
        private val ALIASES = setOf(GoUncheckedErrorInspection.SHORT_NAME)
    }
}
