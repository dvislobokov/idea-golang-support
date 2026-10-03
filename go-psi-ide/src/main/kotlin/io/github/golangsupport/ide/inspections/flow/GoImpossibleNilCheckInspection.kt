package io.github.golangsupport.ide.inspections.flow

import com.intellij.codeInspection.ProblemsHolder
import io.github.golangsupport.lang.psi.GoConditionalExpr
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.semantic.flow.GoControlFlow
import io.github.golangsupport.semantic.flow.GoNil
import io.github.golangsupport.semantic.flow.GoNilness
import io.github.golangsupport.semantic.flow.GoReachingDefinitions
import io.github.golangsupport.semantic.types.GoChanType
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoTypeParamType

/**
 * `x == nil` / `x != nil` on a variable whose nil-ness is known on every path (see [GoNilness]): after `&T{}`, `new`, `make` or a
 * literal, after a `x != nil` check whose branch the code is in, or after `x == nil` held / `x = nil`. The condition is constant.
 * A variable that still holds its `var x T` zero value is not reported (declare-then-test is a style, not a mistake). No fix.
 */
class GoImpossibleNilCheckInspection : GoFlowInspectionBase() {

    override fun check(flow: GoControlFlow, holder: ProblemsHolder) {
        val nilness = GoNilness.of(flow) ?: return
        val reaching by lazy { GoReachingDefinitions.of(flow) }
        for (access in flow.accesses) {
            if (access.isWrite || !flow.isReachable(access.node)) continue
            val ref = access.element as? GoReferenceExpression ?: continue
            if (ref.expression != null) continue
            var p = ref.parent
            while (p is GoParenthesesExpr) p = p.parent
            val cmp = p as? GoConditionalExpr ?: continue
            val equal = cmp.eql != null
            if (!equal && cmp.neq == null) continue
            val service = GoFlowChecks.service(ref)
            val other = if (GoFlowChecks.unparen(cmp.left) === ref) cmp.right else cmp.left
            if (!GoNilness.isNilLiteral(other, service)) continue
            val type = service.declarationType(access.variable)
            if (type is GoTypeParamType) continue
            val u = type.underlying()
            if (u !is GoPointerType && u !is GoMapType && u !is GoSliceType && u !is GoChanType && u !is GoSignatureType && u !is GoInterfaceType) continue
            val fact = nilness.at(ref)
            if (fact == GoNil.UNKNOWN) continue
            if (fact == GoNil.NIL) {
                val defs = reaching?.definitionsOf(access) ?: continue
                if (defs.isEmpty() || defs.all { it.isZeroValue }) continue
            }
            val name = ref.text
            val always = if (fact == GoNil.NOT_NIL) !equal else equal
            val first = if (fact == GoNil.NOT_NIL) "$name is never nil here" else "$name is always nil here"
            holder.registerProblem(cmp, "$first; the condition is always $always")
        }
    }
}
