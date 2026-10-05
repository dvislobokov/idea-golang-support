package io.github.golangsupport.ide.inspections.gofix2

import com.intellij.psi.PsiElement
import io.github.golangsupport.lang.psi.GoAddExpr
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoIndexOrSliceExpr
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil.isSlice
import io.github.golangsupport.semantic.types.GoArrayType
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoPointerType

/**
 * modernize `unsafefuncs` (go1.17):
 * - `unsafe.Pointer(uintptr(p) + off)` with `p` an `unsafe.Pointer` → `unsafe.Add(p, off)`;
 * - `(*[N]T)(unsafe.Pointer(p))[:n:n]` with `p` a `*T` → `unsafe.Slice(p, n)` (only the three-index form: `[:n]` keeps capacity N).
 */
class GoFixUnsafeFuncsInspection : GoFix2InspectionBase() {
    override val minVersion = "1.17"

    override fun detect(element: PsiElement, file: GoFile): GoFixFinding? = when (element) {
        is GoCallExpr -> add(element)
        is GoIndexOrSliceExpr -> slice(element)
        else -> null
    }

    private fun add(call: GoCallExpr): GoFixFinding? {
        val sum = GoFixPsi.unparen(unsafePointerArgument(call)) as? GoAddExpr ?: return null
        if (sum.add == null) return null
        val toInt = GoFixPsi.unparen(sum.left) as? GoCallExpr ?: return null
        val offset = sum.right ?: return null
        val callee = toInt.expression as? GoReferenceExpression ?: return null
        if (callee.expression != null || callee.identifier.text != "uintptr") return null
        val pointer = GoFixPsi.args(toInt).singleOrNull() ?: return null
        val service = GoSemanticService.getInstance(call.project)
        if (service.typeOf(toInt) != GoBasicType.UINTPTR || service.typeOf(pointer).underlying() != GoBasicType.UNSAFE_POINTER) return null
        val unsafe = (call.expression as GoReferenceExpression).expression!!.text
        return GoFixFinding(call, "pointer + integer can be simplified using unsafe.Add", listOf("Simplify using unsafe.Add" to { _ ->
            listOf(GoFixEdit.replace(call, "$unsafe.Add(${pointer.text}, ${offset.text})"))
        }))
    }

    private fun slice(expr: GoIndexOrSliceExpr): GoFixFinding? {
        if (!expr.isSlice) return null
        val conversion = expr.expression as? GoCallExpr ?: return null
        val bounds = SLICE.matchEntire(expr.text.substring(conversion.textLength)) ?: return null
        val (length, capacity) = bounds.destructured
        if (length != capacity) return null
        val service = GoSemanticService.getInstance(expr.project)
        val array = (service.typeOf(conversion) as? GoPointerType)?.elem?.underlying() as? GoArrayType ?: return null
        val inner = GoFixPsi.args(conversion).singleOrNull() as? GoCallExpr ?: return null
        val pointer = unsafePointerArgument(inner) ?: return null
        if ((service.typeOf(pointer) as? GoPointerType)?.elem != array.elem) return null
        val unsafe = (inner.expression as GoReferenceExpression).expression!!.text
        return GoFixFinding(expr, "slice conversion can be simplified using unsafe.Slice", listOf("Simplify using unsafe.Slice" to { _ ->
            listOf(GoFixEdit.replace(expr, "$unsafe.Slice(${pointer.text}, $length)"))
        }))
    }

    /** `x` of the conversion `unsafe.Pointer(x)`. */
    private fun unsafePointerArgument(call: GoCallExpr): GoExpression? {
        val callee = call.expression as? GoReferenceExpression ?: return null
        if (callee.identifier.text != "Pointer" || callee.expression !is GoReferenceExpression) return null
        val argument = GoFixPsi.args(call).singleOrNull() ?: return null
        if (GoSemanticService.getInstance(call.project).typeOf(call) != GoBasicType.UNSAFE_POINTER) return null
        return argument
    }

    companion object {
        private val SLICE = Regex("""\[\s*(?:0\s*)?:\s*([^:\[\]]+?)\s*:\s*([^:\[\]]+?)\s*]""")
    }
}
