package io.github.golangsupport.ide.inspections.flow

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoBinaryExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoLiteral
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.semantic.flow.GoControlFlow
import io.github.golangsupport.semantic.flow.GoReachingDefinitions
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoConstant

/**
 * Values the data flow proves constant at a point, for the constant-condition and division checks: a literal expression (`3`, `"a"`,
 * `-1`, `2 * 4`, no named constants: `runtime.GOOS == "linux"` or a build-tagged `const debug` is configuration, not a mistake), or a
 * local variable every reaching definition of which stores the same such value (`n := 0` with no later write on any path; `var n int`
 * counts as its zero value). Escaping variables (address taken, captured by a closure) have no reaching definitions, so they never are.
 */
internal object GoFlowConstants {

    /** The constant value of [e] where it is evaluated in [flow], or null when it is not proven. */
    fun valueOf(e: GoExpression?, flow: GoControlFlow, reaching: GoReachingDefinitions?): GoConstant? {
        val x = GoFlowChecks.unparen(e) ?: return null
        if (x is GoReferenceExpression) return variableValue(x, flow, reaching)
        return literalValue(x)
    }

    /** Whether [e] reads a variable whose value [valueOf] knows (as opposed to a literal operand). */
    fun isVariable(e: GoExpression?): Boolean = GoFlowChecks.unparen(e) is GoReferenceExpression

    /** A constant made of literals and operators only. */
    fun literalValue(e: GoExpression?): GoConstant? {
        val x = GoFlowChecks.unparen(e) ?: return null
        if (!literalOnly(x)) return null
        return GoFlowChecks.service(x).constantValue(x)
    }

    private fun literalOnly(e: PsiElement): Boolean = when (e) {
        is GoLiteral, is GoStringLiteral -> true
        is GoParenthesesExpr, is GoUnaryExpr, is GoBinaryExpr -> e.children.filterIsInstance<GoExpression>().let { it.isNotEmpty() && it.all(::literalOnly) }
        else -> false
    }

    private fun variableValue(ref: GoReferenceExpression, flow: GoControlFlow, reaching: GoReachingDefinitions?): GoConstant? {
        if (ref.expression != null || reaching == null || flow.isConditionallyEvaluated(ref)) return null
        val read = flow.accessesAt(ref).firstOrNull { !it.isWrite } ?: return null
        if (!flow.isTracked(read.variable) || !flow.isReachable(read.node)) return null
        val defs = reaching.definitionsOf(read)
        if (defs.isEmpty()) return null
        var value: GoConstant? = null
        for (d in defs) {
            val v = when {
                d.isCompound || d.resultIndex >= 0 -> return null
                d.isZeroValue -> zeroOf(d.variable) ?: return null
                else -> literalValue(d.value) ?: return null
            }
            if (value != null && value != v) return null
            value = v
        }
        return value
    }

    private fun zeroOf(variable: io.github.golangsupport.lang.psi.GoNamedElement): GoConstant? {
        val basic = GoFlowChecks.service(variable).declarationType(variable).underlying() as? GoBasicType ?: return null
        val k = basic.kind
        return when {
            k == GoBasicKind.BOOL -> GoConstant.Bool(false)
            k == GoBasicKind.STRING -> GoConstant.Str("")
            k.isInteger -> GoConstant.Int(0)
            else -> null
        }
    }

    /** The operator token of a binary expression (`==`, `/`, ...). */
    fun operator(e: GoBinaryExpr): String? {
        var c = e.firstChild
        while (c != null) {
            if (c !is GoExpression && c !is PsiWhiteSpace && c !is PsiComment) return c.text
            c = c.nextSibling
        }
        return null
    }

    /** The expressions of [flow]'s body outside nested function literals (those have their own graph). */
    fun <T : PsiElement> ownElements(flow: GoControlFlow, type: Class<T>): List<T> {
        val result = ArrayList<T>()
        PsiTreeUtil.processElements(flow.body) { e ->
            if (type.isInstance(e) && PsiTreeUtil.getParentOfType(e, GoFunctionLit::class.java)?.let { PsiTreeUtil.isAncestor(flow.body, it, true) } != true) result += type.cast(e)
            true
        }
        return result
    }
}
