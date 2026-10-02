package io.github.golangsupport.ide.documentation

import com.intellij.lang.ExpressionTypeProvider
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.semantic.api.GoSemanticService

/** Type Info (Ctrl+Shift+P): the type of the selected expression from [GoSemanticService.typeOf]. Stands down when [GoIdeFeature.HOVER] is off. */
class GoExpressionTypeProvider : ExpressionTypeProvider<GoExpression>() {

    override fun getInformationHint(element: GoExpression): String =
        StringUtil.escapeXmlEntities(typeText(element))

    override fun getErrorHint(): String = "No expression found"

    /** Enclosing expressions of [elementAt], innermost first, up to the statement (one per text range); none when the gate is closed. */
    override fun getExpressionsAt(elementAt: PsiElement): List<GoExpression> {
        if (!GoIdeFeatureGate.enabled(GoIdeFeature.HOVER, elementAt.project)) return emptyList()
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
