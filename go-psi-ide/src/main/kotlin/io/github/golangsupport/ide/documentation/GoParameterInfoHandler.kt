package io.github.golangsupport.ide.documentation

import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.ParameterInfoUtils
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.psi.PsiElement
import io.github.golangsupport.lang.psi.GoArgumentList
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoType

/** One signature shown by [GoParameterInfoHandler]: rendered parameters and an optional `[T any]` prefix. */
data class GoParameterInfoItem(val typeParameters: String?, val parameters: List<String>, val variadic: Boolean) {
    /** The parameter highlighted for argument [argumentIndex]; extra arguments of a variadic call map to the last parameter. */
    fun parameterIndexFor(argumentIndex: Int): Int = when {
        parameters.isEmpty() -> -1
        variadic && argumentIndex >= parameters.size - 1 -> parameters.size - 1
        argumentIndex < parameters.size -> argumentIndex
        else -> -1
    }

    /** `[T any] a int, b ...string` or `<no parameters>`. */
    val text: String
        get() = (typeParameters?.let { "$it " } ?: "") + if (parameters.isEmpty()) NO_PARAMETERS else parameters.joinToString(", ")

    /** Text range of parameter [index] inside [text]. */
    fun rangeOf(index: Int): IntRange? {
        if (index !in parameters.indices) return null
        var start = typeParameters?.let { it.length + 1 } ?: 0
        for (i in 0 until index) start += parameters[i].length + 2
        return start until start + parameters[index].length
    }

    companion object {
        const val NO_PARAMETERS = "<no parameters>"

        @JvmStatic
        fun of(sig: GoSignatureType): GoParameterInfoItem {
            val params = sig.params.mapIndexed { i, p ->
                val variadicLast = sig.variadic && i == sig.params.lastIndex
                val type: GoType = if (variadicLast) (p.type as? GoSliceType)?.elem ?: p.type else p.type
                val typeText = (if (variadicLast) "..." else "") + GoDocSignature.renderType(type)
                if (p.name != null) "${p.name} $typeText" else typeText
            }
            val typeParams = sig.typeParams.takeIf { it.isNotEmpty() }?.let {
                GoDocSignature.renderType(GoSignatureType(emptyList(), emptyList(), false, it)).removePrefix("func").removeSuffix("()")
            }
            return GoParameterInfoItem(typeParams, params, sig.variadic)
        }
    }
}

/**
 * Parameter info (Ctrl+P) inside call arguments: the callee's signature from
 * [GoSemanticService.typeOf] (method values, function values and generic functions included),
 * the current parameter highlighted by the comma count, variadic tails mapped to the last
 * parameter. Type parameters that the type checker did not instantiate are shown as a `[T any]`
 * prefix.
 */
class GoParameterInfoHandler : ParameterInfoHandler<GoArgumentList, GoParameterInfoItem> {

    override fun findElementForParameterInfo(context: CreateParameterInfoContext): GoArgumentList? {
        val list = findArgumentList(context.file, context.offset) ?: return null
        val item = itemFor(list) ?: return null
        context.itemsToShow = arrayOf(item)
        return list
    }

    override fun showParameterInfo(element: GoArgumentList, context: CreateParameterInfoContext) {
        context.showHint(element, element.textRange.startOffset, this)
    }

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): GoArgumentList? =
        findArgumentList(context.file, context.offset)

    override fun updateParameterInfo(list: GoArgumentList, context: UpdateParameterInfoContext) {
        if (context.parameterOwner != null && context.parameterOwner != list) {
            context.removeHint()
            return
        }
        context.setCurrentParameter(ParameterInfoUtils.getCurrentParameterIndex(list.node, context.offset, GoTypes.COMMA))
    }

    override fun updateUI(item: GoParameterInfoItem, context: ParameterInfoUIContext) {
        val range = item.rangeOf(item.parameterIndexFor(context.currentParameterIndex))
        val text = item.text
        context.setupUIComponentPresentation(
            text,
            range?.first ?: -1,
            range?.let { it.last + 1 } ?: -1,
            !context.isUIComponentEnabled,
            false,
            false,
            context.defaultParameterColor,
        )
    }

    companion object {
        private fun findArgumentList(file: com.intellij.psi.PsiFile, offset: Int): GoArgumentList? {
            val list = ParameterInfoUtils.findParentOfType(file, offset, GoArgumentList::class.java) ?: return null
            // The caret must be after '(' (and before or at ')').
            if (offset <= list.textRange.startOffset) return null
            return list
        }

        /** The signature shown for the call owning [list]. */
        @JvmStatic
        fun itemFor(list: GoArgumentList): GoParameterInfoItem? {
            val call = list.parent as? GoCallExpr ?: return null
            val callee = call.expression ?: return null
            val semantic = GoSemanticService.getInstance(list.project)
            val type = semantic.typeOf(callee)
            val sig = type as? GoSignatureType ?: type.underlying() as? GoSignatureType ?: declaredSignature(callee) ?: return null
            return GoParameterInfoItem.of(sig)
        }

        /** Falls back to the declaration's signature (e.g. when the callee expression's type is unknown). */
        private fun declaredSignature(callee: PsiElement): GoSignatureType? {
            val target = (callee as? GoReferenceExpression)?.reference?.resolve() as? GoNamedElement ?: return null
            if (target !is GoFunctionOrMethodDeclaration) return null
            return GoSemanticService.getInstance(callee.project).declarationType(target) as? GoSignatureType
        }
    }
}
