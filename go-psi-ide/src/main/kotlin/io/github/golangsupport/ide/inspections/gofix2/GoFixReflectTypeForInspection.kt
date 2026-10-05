package io.github.golangsupport.ide.inspections.gofix2

import com.intellij.psi.PsiElement
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoCompositeLit
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.semantic.psi.GoPsiUtil.elements

/**
 * modernize `reflecttypefor` (go1.22): `reflect.TypeOf((*T)(nil)).Elem()` and `reflect.TypeOf(T{})` → `reflect.TypeFor[T]()`.
 * Only those two shapes: the type is spelled out, and the argument has no effects.
 */
class GoFixReflectTypeForInspection : GoFix2InspectionBase() {
    override val minVersion = "1.22"

    override fun detect(element: PsiElement, file: GoFile): GoFixFinding? {
        if (element !is GoCallExpr) return null
        val type = pointerElemType(element) ?: zeroLiteralType(element) ?: return null
        return GoFixFinding(element, MESSAGE, listOf("Replace TypeOf by TypeFor" to { s -> listOf(GoFixEdit.replace(element, "${s.prefix("reflect", "reflect")}TypeFor[$type]()")) }))
    }

    /** `T` of `reflect.TypeOf((*T)(nil)).Elem()`. */
    private fun pointerElemType(call: GoCallExpr): String? {
        val elem = call.expression as? GoReferenceExpression ?: return null
        if (elem.identifier.text != "Elem" || GoFixPsi.args(call).isNotEmpty()) return null
        val typeOf = elem.expression as? GoCallExpr ?: return null
        val arg = typeOfArgument(typeOf) ?: return null
        return NIL_POINTER.matchEntire(arg.text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** `T` of `reflect.TypeOf(T{})` (an empty literal of a named or spelled-out type). */
    private fun zeroLiteralType(call: GoCallExpr): String? {
        val lit = typeOfArgument(call) as? GoCompositeLit ?: return null
        if (lit.literalValue?.elements?.isNotEmpty() != false) return null
        return lit.text.substring(0, lit.literalValue!!.startOffsetInParent).trim().takeIf { it.isNotEmpty() }
    }

    private fun typeOfArgument(call: GoCallExpr): PsiElement? {
        if (GoFixPsi.hasEllipsis(call)) return null
        val arg = GoFixPsi.args(call).singleOrNull() ?: return null
        if (!GoFixPsi.isCallTo(call, "reflect.TypeOf")) return null
        return arg
    }

    companion object {
        const val MESSAGE = "reflect.TypeOf call can be simplified using TypeFor"
        private val NIL_POINTER = Regex("""\(\s*\*\s*([^()]+)\)\s*\(\s*nil\s*\)""", RegexOption.DOT_MATCHES_ALL)
    }
}
