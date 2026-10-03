package io.github.golangsupport.ide.rules.builtin.staticcheck

import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression

/**
 * staticcheck SA1001: a constant template passed to `Parse` of a template made right there by `template.New(…)` (text or html) that
 * does not parse. Only syntax errors are reported (messages with "unexpected" or "bad character"), as staticcheck does: an unknown
 * function may be added later with `Funcs`.
 */
class GoTemplateParseRule : GoStaticcheckCallRule() {
    override val id: String get() = "SA1001"
    override val title: String get() = "Invalid template"
    override val description: String get() =
        "A constant template passed to <code>template.New(name).Parse</code> (<code>text/template</code> or <code>html/template</code>) that " +
            "<code>Parse</code> rejects: an unclosed action, an unexpected token, a bad character."
    override val calleeNames: Set<String> get() = NAMES

    override fun checkCallee(call: GoCallExpr, callee: String, arguments: List<GoExpression>, ctx: GoRuleContext) {
        if (callee != "text/template.Template.Parse" && callee != "html/template.Template.Parse") return
        val argument = arguments.singleOrNull() ?: return
        val receiver = GoLintPsi.unparen(GoLintPsi.calleeReference(call)?.expression) as? GoCallExpr ?: return
        val newRef = GoLintPsi.calleeReference(receiver) ?: return
        if (newRef.identifier?.text != "New") return
        val key = GoStaticcheckPsi.calleeKey(newRef, ctx)
        if (key != "text/template.New" && key != "html/template.New") return
        val text = GoStaticcheckPsi.stringConstant(argument, ctx) ?: return
        val error = GoTemplateSyntax.parseError(text) ?: return
        if ("unexpected" !in error && "bad character" !in error) return
        ctx.report(argument, error)
    }

    private companion object {
        val NAMES = setOf("Parse")
    }
}
