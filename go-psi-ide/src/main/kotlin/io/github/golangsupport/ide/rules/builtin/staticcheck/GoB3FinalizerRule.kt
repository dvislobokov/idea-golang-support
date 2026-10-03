package io.github.golangsupport.ide.rules.builtin.staticcheck

import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.ide.rules.GoRuleNeed
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.semantic.psi.GoPsiUtil

/**
 * staticcheck SA5005: `runtime.SetFinalizer(x, func(…) { … x … })`: the finalizer closes over the object, which then stays reachable from
 * its own finalizer and is never collected. The finalizer may be a function literal or a local variable assigned one literal.
 */
class GoCyclicFinalizerRule : GoStaticcheckCallRule() {
    override val id: String get() = "SA5005"
    override val title: String get() = "Finalizer references the finalized object"
    override val description: String get() =
        "A finalizer that closes over the object it is set on keeps that object reachable: it is never collected and the finalizer never runs. " +
            "Use the finalizer's parameter instead."
    override val calleeNames: Set<String> get() = NAMES
    override val needs: Set<GoRuleNeed> get() = GoB3Psi.TYPES_FLOW

    override fun checkCallee(call: GoCallExpr, callee: String, arguments: List<GoExpression>, ctx: GoRuleContext) {
        if (callee != "runtime.SetFinalizer" || arguments.size != 2) return
        val obj = GoLintPsi.unparen(arguments[0]) as? GoReferenceExpression ?: return
        if (obj.expression != null) return
        val variable = ctx.resolve(obj).singleOrNull() ?: return
        if (variable !is GoVarDefinition && variable !is GoParamDefinition || !GoPsiUtil.isInsideFunctionBody(variable) && variable !is GoParamDefinition) return
        val origin = GoB3Psi.origin(arguments[1], ctx)
        val literal = origin.expression as? GoFunctionLit ?: return
        if (origin.resultIndex >= 0) return
        val name = obj.identifier?.text ?: return
        val captures = PsiTreeUtil.findChildrenOfType(literal, GoReferenceExpression::class.java).any { ref ->
            ref.expression == null && ref.identifier?.text == name && ctx.resolve(ref).singleOrNull() == variable
        }
        if (!captures) return
        ctx.report(call, "the finalizer closes over the object, preventing the finalizer from ever running (at ${position(literal, ctx)})")
    }

    /** `file.go:line:column` of the `func` keyword of [literal] (columns count bytes, as go/token does). */
    private fun position(literal: GoFunctionLit, ctx: GoRuleContext): String {
        val text = ctx.file.text
        val offset = literal.textRange.startOffset
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        val line = text.substring(0, offset).count { it == '\n' } + 1
        val column = text.substring(lineStart, offset).toByteArray(Charsets.UTF_8).size + 1
        return "${ctx.file.name}:$line:$column"
    }

    private companion object {
        val NAMES = setOf("SetFinalizer")
    }
}
