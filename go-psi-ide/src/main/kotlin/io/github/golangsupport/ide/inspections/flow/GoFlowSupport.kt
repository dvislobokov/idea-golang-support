package io.github.golangsupport.ide.inspections.flow

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.inspections.GoAnalysisPsi
import io.github.golangsupport.ide.inspections.GoImportEdits
import io.github.golangsupport.lang.psi.GoAndExpr
import io.github.golangsupport.lang.psi.GoConditionalExpr
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoOrExpr
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.semantic.flow.GoControlFlow
import io.github.golangsupport.semantic.flow.GoNilness
import io.github.golangsupport.semantic.psi.GoPsiUtil.inner
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoUnknownType

/** Helpers shared by the second batch of flow inspections (error flow, nil flow, unreachable code). */
internal object GoFlowSupport {

    fun resultTypes(flow: GoControlFlow): List<GoType> {
        val service = GoFlowChecks.service(flow.owner)
        val type = when (val owner = flow.owner) {
            is GoFunctionOrMethodDeclaration -> service.declarationType(owner)
            is GoFunctionLit -> service.typeOf(owner)
            else -> GoUnknownType
        }
        return (type as? GoSignatureType)?.results?.map { it.type } ?: emptyList()
    }

    fun lastResultIsError(flow: GoControlFlow): Boolean = resultTypes(flow).lastOrNull()?.let(GoAnalysisPsi::isError) == true

    /** Whether [cond] contains a comparison of the variable [v] with nil (`==` when [equal], else `!=`), looking through `&&`, `||` and parentheses. */
    fun comparesToNil(flow: GoControlFlow, cond: PsiElement?, v: GoNamedElement, equal: Boolean): Boolean = when (cond) {
        is GoParenthesesExpr -> comparesToNil(flow, cond.inner, v, equal)
        is GoAndExpr -> comparesToNil(flow, cond.left, v, equal) || comparesToNil(flow, cond.right, v, equal)
        is GoOrExpr -> comparesToNil(flow, cond.left, v, equal) || comparesToNil(flow, cond.right, v, equal)
        is GoConditionalExpr -> nilComparedVariable(flow, cond, equal) == v
        else -> false
    }

    /** The variable [cmp] compares with nil by `==` ([equal]) or `!=`, or null. */
    fun nilComparedVariable(flow: GoControlFlow, cmp: GoConditionalExpr, equal: Boolean): GoNamedElement? {
        if (if (equal) cmp.eql == null else cmp.neq == null) return null
        val service = GoFlowChecks.service(cmp)
        val operand = when {
            GoNilness.isNilLiteral(cmp.right, service) -> cmp.left
            GoNilness.isNilLiteral(cmp.left, service) -> cmp.right
            else -> null
        }
        val ref = GoFlowChecks.unparen(operand) as? GoReferenceExpression ?: return null
        return if (ref.expression == null) flow.variableOf(ref) else null
    }

    /** Whether [element] is an operand of `== nil` / `!= nil`. */
    fun isNilComparisonOperand(element: PsiElement): Boolean {
        var p = element.parent
        while (p is GoParenthesesExpr) p = p.parent
        val cmp = p as? GoConditionalExpr ?: return false
        if (cmp.eql == null && cmp.neq == null) return false
        val service = GoFlowChecks.service(cmp)
        return GoNilness.isNilLiteral(cmp.left, service) || GoNilness.isNilLiteral(cmp.right, service)
    }

    /** Replaces the text range of the problem element with [replacement]. */
    class ReplaceFix(private val fixName: String, private val replacement: String) : LocalQuickFix {
        override fun getFamilyName(): String = fixName

        override fun getName(): String = fixName

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val element = descriptor.psiElement ?: return
            val file = element.containingFile
            val document = GoImportEdits.document(file) ?: return
            document.replaceString(element.textRange.startOffset, element.textRange.endOffset, replacement)
            GoImportEdits.commit(file, document)
        }
    }

    fun range(a: PsiElement, b: PsiElement): TextRange = TextRange(a.textRange.startOffset, b.textRange.endOffset)
}
