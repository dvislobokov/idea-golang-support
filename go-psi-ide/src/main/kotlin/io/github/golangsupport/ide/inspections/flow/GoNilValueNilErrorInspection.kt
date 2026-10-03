package io.github.golangsupport.ide.inspections.flow

import com.intellij.codeInspection.ProblemsHolder
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoReturnStatement
import io.github.golangsupport.semantic.flow.GoControlFlow
import io.github.golangsupport.semantic.flow.GoFlowNode
import io.github.golangsupport.semantic.flow.GoNilness
import io.github.golangsupport.semantic.types.GoChanType
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoTypeParamType

/**
 * nilnil: `return nil, nil` in a function declaration with results `(T, error)` where T is a pointer, interface, map, slice,
 * channel or function: callers must check both and cannot tell "nothing" from success. A doc comment that mentions `nil, nil` or
 * `nil if` documents the contract and silences the report. No fix.
 */
class GoNilValueNilErrorInspection : GoFlowInspectionBase() {

    override fun check(flow: GoControlFlow, holder: ProblemsHolder) {
        val owner = flow.owner as? GoFunctionOrMethodDeclaration ?: return
        val results = GoFlowSupport.resultTypes(flow)
        if (results.size != 2 || !GoFlowSupport.lastResultIsError(flow)) return
        val first = results[0]
        if (first is GoTypeParamType) return
        val u = first.underlying()
        if (u !is GoPointerType && u !is GoInterfaceType && u !is GoMapType && u !is GoSliceType && u !is GoChanType && u !is GoSignatureType) return
        if (documentsNil(owner)) return
        val service = GoFlowChecks.service(owner)
        for (node in flow.nodes) {
            if (node.kind != GoFlowNode.Kind.RETURN || !flow.isReachable(node)) continue
            val ret = node.element as? GoReturnStatement ?: continue
            val values = ret.expressionList
            if (values.size != 2 || !values.all { GoNilness.isNilLiteral(it, service) }) continue
            holder.registerProblem(ret, "nil value and nil error are returned together; return a sentinel error or a non-nil value")
        }
    }

    /** The doc comment of [function] says that `nil, nil` (or "nil if …") is a deliberate result. */
    private fun documentsNil(function: GoFunctionOrMethodDeclaration): Boolean {
        val text = function.docText ?: return false
        return "nil, nil" in text || "nil if" in text
    }
}
