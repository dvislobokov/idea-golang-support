package io.github.golangsupport.ide.inspections.flow

import com.intellij.codeInspection.ProblemsHolder
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoMulExpr
import io.github.golangsupport.semantic.flow.GoControlFlow
import io.github.golangsupport.semantic.flow.GoReachingDefinitions
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoConstant

/**
 * GoLand's `GoDivisionByZero`: `x / d`, `x % d`, `x /= d`, `x %= d` where the divisor is zero. The compiler already rejects a constant
 * zero divisor of an integer division and a constant division (the checker's "division by zero"), so this reports what it lets through:
 * a variable divisor the data flow proves zero (`d := 0` with no other write reaching the division: an integer division panics at run
 * time, see [GoFlowConstants]) and a literal zero dividing a non-constant float or complex value (`f / 0` is ±Inf or NaN). No fix.
 */
class GoDivisionByZeroInspection : GoFlowInspectionBase() {

    override fun check(flow: GoControlFlow, holder: ProblemsHolder) {
        val reaching by lazy { GoReachingDefinitions.of(flow) }
        fun inspect(dividend: GoExpression?, divisor: GoExpression?) {
            if (divisor == null) return
            val service = GoFlowChecks.service(divisor)
            val zero = if (GoFlowConstants.isVariable(divisor)) {
                if (service.constantValue(divisor) != null) return // a named constant: the checker's business (integer) or intended (float)
                GoFlowConstants.valueOf(divisor, flow, reaching)
            } else {
                if (dividend == null || service.constantValue(dividend) != null) return
                val kind = (service.typeOf(dividend).underlying() as? GoBasicType)?.kind
                if (kind == null || !kind.isFloat && !kind.isComplex) return
                GoFlowConstants.literalValue(divisor)
            } ?: return
            if (zero.toBigDecimal()?.signum() == 0 || zero is GoConstant.Complex && zero.re.signum() == 0 && zero.im.signum() == 0) holder.registerProblem(divisor, "Division by zero")
        }
        for (mul in GoFlowConstants.ownElements(flow, GoMulExpr::class.java)) {
            val op = GoFlowConstants.operator(mul)
            if (op == "/" || op == "%") inspect(mul.left, mul.right)
        }
        for (assignment in GoFlowConstants.ownElements(flow, GoAssignmentStatement::class.java)) {
            val op = assignment.assignOp.text
            if (op != "/=" && op != "%=") continue
            inspect(assignment.leftHandExprList.expressionList.singleOrNull(), assignment.expressionList.singleOrNull())
        }
    }
}
