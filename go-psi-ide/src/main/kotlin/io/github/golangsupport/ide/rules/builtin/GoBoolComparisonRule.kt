package io.github.golangsupport.ide.rules.builtin

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.inspections.GoImportEdits
import io.github.golangsupport.ide.rules.GoExpressionRule
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.ide.rules.GoRuleLevel
import io.github.golangsupport.ide.rules.GoRuleNeed
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoConditionalExpr
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoConstant

/**
 * staticcheck S1002 (gosimple before golangci-lint v2): `x == true`, `x != false` - omit the comparison (`x`); `== false` / `!= true`
 * become `!x`. Any comparison outside `_test.go` files, with a constant of the predeclared (or untyped) bool type - the predeclared
 * `true` / `false` or a constant declared from them; named bool types are enums, comparing them is fine - when `x` is a boolean.
 */
class GoBoolComparisonRule : GoExpressionRule() {
    override val id: String get() = "S1002"
    override val linter: String get() = "staticcheck"
    override val linterAliases: Set<String> get() = ALIASES
    override val title: String get() = "Omit comparison with a boolean constant"
    override val description: String get() = "<code>if x == true</code> is <code>if x</code>; <code>if x == false</code> is <code>if !x</code>."
    override val defaultLevel: GoRuleLevel get() = GoRuleLevel.WEAK_WARNING
    override val needs: Set<GoRuleNeed> get() = TYPES

    override fun checkExpression(expression: GoExpression, ctx: GoRuleContext) {
        if (expression !is GoConditionalExpr || expression.eql == null && expression.neq == null) return
        if (ctx.file.name.endsWith("_test.go")) return
        val left = expression.left
        val right = expression.right ?: return
        val leftConstant = boolConstant(left, ctx)
        val rightConstant = if (leftConstant == null) boolConstant(right, ctx) else null
        val constant = leftConstant ?: rightConstant ?: return
        val operand = if (leftConstant == null) left else right
        val type = ctx.typeOf(operand).underlying() as? GoBasicType ?: return
        if (!type.kind.isBoolean) return
        val keep = constant == (expression.eql != null)
        val replacement = if (keep) operand.text else negation(operand)
        val fixes = if (PsiTreeUtil.findChildOfType(expression, PsiComment::class.java) != null) emptyArray() else arrayOf<LocalQuickFix>(ReplaceFix(replacement))
        ctx.report(expression, "should omit comparison to bool constant, can be simplified to $replacement", *fixes)
    }

    /** `!x`; `!!x` is `x`, a non-operand is parenthesized. */
    private fun negation(operand: GoExpression): String {
        if (operand is GoUnaryExpr && operand.not != null) operand.expression?.let { return it.text }
        return "!" + if (isOperand(operand)) operand.text else "(${operand.text})"
    }

    /**
     * The value of a constant of type `bool` / untyped bool named by [e] (staticcheck `IsBoolConst`: a plain name, not `pkg.C`), not of a
     * named bool type; null for anything else.
     */
    private fun boolConstant(e: GoExpression, ctx: GoRuleContext): Boolean? {
        val ref = e as? GoReferenceExpression ?: return null
        if (ref.expression != null) return null
        val target = ctx.resolve(ref).singleOrNull() as? GoConstDefinition ?: return null
        if ((target.containingFile as? GoFile)?.packageName == "builtin") {
            return when (ref.identifier.text) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }
        val type = ctx.typeOf(ref) as? GoBasicType ?: return null
        if (!type.kind.isBoolean) return null
        return (ctx.semantic.constantValue(ref) as? GoConstant.Bool)?.value
    }

    /** An operand `!` can take without parentheses. */
    private fun isOperand(e: GoExpression): Boolean = e is GoReferenceExpression || e is GoCallExpr || e is GoParenthesesExpr

    private class ReplaceFix(private val replacement: String) : LocalQuickFix {
        override fun getFamilyName(): String = "Simplify boolean comparison"

        override fun getName(): String = "Replace with '$replacement'"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val element = descriptor.psiElement ?: return
            val file = element.containingFile
            val document = GoImportEdits.document(file) ?: return
            document.replaceString(element.textRange.startOffset, element.textRange.endOffset, replacement)
            GoImportEdits.commit(file, document)
        }
    }

    private companion object {
        val TYPES = setOf(GoRuleNeed.TYPES)
        val ALIASES = setOf("gosimple")
    }
}
