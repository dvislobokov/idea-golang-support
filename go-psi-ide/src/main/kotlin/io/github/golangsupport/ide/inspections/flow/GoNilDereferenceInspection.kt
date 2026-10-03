package io.github.golangsupport.ide.inspections.flow

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoIncDecStatement
import io.github.golangsupport.lang.psi.GoIndexOrSliceExpr
import io.github.golangsupport.lang.psi.GoLeftHandExprList
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.semantic.flow.GoControlFlow
import io.github.golangsupport.semantic.flow.GoNil
import io.github.golangsupport.semantic.flow.GoNilness
import io.github.golangsupport.semantic.psi.GoPsiUtil.isSlice
import io.github.golangsupport.semantic.resolve.GoResolver
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoLookup
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSignatureType

/**
 * vet `nilness`: a variable known to be nil on every path to a use that panics — a field selection or `*x` through a nil pointer,
 * a method with a value receiver called through a nil pointer, a method called on a nil interface, a write into a nil map, a call
 * of a nil function. Known nil means after `x == nil` held (without a reassignment or exit), after `x = nil`, or `var x *T`.
 * Calls of pointer-receiver methods are legal on nil and not reported.
 */
class GoNilDereferenceInspection : GoFlowInspectionBase() {

    override fun check(flow: GoControlFlow, holder: ProblemsHolder) {
        val nilness = GoNilness.of(flow) ?: return
        for (access in flow.accesses) {
            if (access.isWrite || !flow.isReachable(access.node)) continue
            val ref = access.element as? GoReferenceExpression ?: continue
            if (nilness.at(ref) != GoNil.NIL || inUnevaluatedOperand(ref)) continue
            val message = panicOf(ref) ?: continue
            holder.registerProblem(ref, message, ProblemHighlightType.WARNING)
        }
    }

    /** Inside the argument of `unsafe.Sizeof/Alignof/Offsetof`: the operand is not evaluated (`unsafe.Sizeof(*r)` in runtime/malloc.go). */
    private fun inUnevaluatedOperand(ref: GoReferenceExpression): Boolean {
        var e: com.intellij.psi.PsiElement? = ref.parent
        while (e != null && e !is com.intellij.psi.PsiFile) {
            val callee = (e as? GoCallExpr)?.expression as? GoReferenceExpression
            if (callee != null && callee.expression?.text == "unsafe" && callee.identifier.text in UNEVALUATED) return true
            e = e.parent
        }
        return false
    }

    private companion object {
        val UNEVALUATED = setOf("Sizeof", "Alignof", "Offsetof")
    }

    /** The message when the use of nil [ref] around it panics, or null. */
    private fun panicOf(ref: GoReferenceExpression): String? {
        val name = ref.identifier.text
        val service = GoFlowChecks.service(ref)
        val type = service.typeOf(ref).underlying()
        return when (val parent = ref.parent) {
            is GoReferenceExpression -> {
                if (parent.expression !== ref) return null
                val selection = GoResolver.getInstance(ref.project).resolveReferenceExpression(parent)
                    .firstNotNullOfOrNull { (it as? GoResolver.Result.Selection)?.selection } ?: return null
                when {
                    type is GoInterfaceType && selection is GoLookup.Selection.Method -> "nil dereference of $name"
                    type !is GoPointerType -> null
                    selection is GoLookup.Selection.Field -> "nil dereference of $name"
                    selection is GoLookup.Selection.Method && !selection.method.pointerReceiver && selection.path.isEmpty() -> "nil dereference of $name"
                    else -> null
                }
            }
            is GoUnaryExpr -> if (parent.mul != null && type is GoPointerType) "nil dereference of $name" else null
            is GoIndexOrSliceExpr -> {
                if (parent.expression !== ref || parent.isSlice || type !is GoMapType) return null
                val list = parent.parent as? GoLeftHandExprList ?: return null
                if (list.parent is GoAssignmentStatement || list.parent is GoIncDecStatement) "write to nil map $name" else null
            }
            is GoCallExpr -> if (parent.expression === ref && type is GoSignatureType) "call of nil function $name" else null
            else -> null
        }
    }
}
