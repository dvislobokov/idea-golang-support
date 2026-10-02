package io.github.golangsupport.ide.documentation

import com.intellij.lang.ExpressionTypeProvider
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.semantic.api.GoSemanticService

/** Type Info (Ctrl+Shift+P): the type of the selected expression from [GoSemanticService.typeOf]. */
class GoExpressionTypeProvider : ExpressionTypeProvider<GoExpression>() {

    override fun getInformationHint(element: GoExpression): String =
        StringUtil.escapeXmlEntities(typeText(element))

    override fun getErrorHint(): String = "No expression found"

    /** Enclosing expressions of [elementAt], innermost first, up to the statement (one per text range). */
    override fun getExpressionsAt(elementAt: PsiElement): List<GoExpression> {
        val result = ArrayList<GoExpression>()
        var e: PsiElement? = elementAt
        while (e != null && e !is GoStatement && e !is PsiFile) {
            if (e is GoExpression && result.none { it.textRange == e.textRange }) result += e
            e = e.parent
        }
        return result
    }

    companion object {
        @JvmStatic
        fun typeText(expr: GoExpression): String {
            val semantic = GoSemanticService.getInstance(expr.project)
            return GoDocSignature.renderType(semantic.typeOf(expr))
        }
    }
}
