package io.github.golangsupport.ide.inspections.gofix2

import com.intellij.psi.PsiElement
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.lang.psi.GoVarDeclaration
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.semantic.psi.GoPsiUtil.condition
import io.github.golangsupport.semantic.psi.GoPsiUtil.initStatement

/**
 * modernize `errorsastype` (go1.26): `var e *T` right before `if errors.As(err, &e) { … }`, with `e` used nowhere but in that `if`,
 * → `if e, ok := errors.AsType[*T](err); ok { … }`. Only the exact shape: the call is the whole condition, the `if` has no init.
 */
class GoFixErrorsAsTypeInspection : GoFix2InspectionBase() {
    override val minVersion = "1.26"

    override fun detect(element: PsiElement, file: GoFile): GoFixFinding? {
        if (element !is GoCallExpr) return null
        val statement = element.parent as? GoIfStatement ?: return null
        if (statement.condition !== element || statement.initStatement != null) return null
        val args = GoFixPsi.args(element)
        if (args.size != 2 || GoFixPsi.hasEllipsis(element)) return null
        val address = args[1] as? GoUnaryExpr ?: return null
        if (address.and == null) return null
        val ref = address.expression as? GoReferenceExpression ?: return null
        if (ref.expression != null || !GoFixPsi.isCallTo(element, "errors.As")) return null
        val variable = GoFixPsi.resolve(ref) as? GoVarDefinition ?: return null
        val spec = variable.parent as? GoVarSpec ?: return null
        val declaration = spec.parent as? GoVarDeclaration ?: return null
        val type = spec.type ?: return null
        if (spec.varDefinitionList.size != 1 || spec.expressionList.isNotEmpty() || declaration.varSpecList.size != 1 || declaration.lparen != null) return null
        if (declaration.parent?.let(GoFixPsi::isStatementList) != true || GoFixPsi.previousStatement(statement) !== declaration) return null
        val body = GoFixPsi.enclosingBody(statement) ?: return null
        if (GoFixPsi.references(body, variable).any { !statement.textRange.contains(it.textRange) }) return null
        if (OK.containsMatchIn(statement.text)) return null
        val name = variable.name ?: return null
        val typeText = type.text
        val callee = GoFixPsi.unparen(element.expression) as? GoReferenceExpression ?: return null
        return GoFixFinding(element, "errors.As can be simplified using AsType[$typeText]", listOf("Replace errors.As with AsType[$typeText]" to { s ->
            listOf(
                GoFixPsi.deleteStatement(declaration),
                GoFixEdit.replace(element, "$name, ok := ${s.prefix("errors", "errors")}AsType[$typeText](${args[0].text}); ok"),
            )
        }), callee.textRange.shiftLeft(element.textRange.startOffset))
    }

    companion object {
        private val OK = Regex("""\bok\b""")
    }
}
