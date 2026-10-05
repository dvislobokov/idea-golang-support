package io.github.golangsupport.ide.inspections.flow

import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoConditionalExpr
import io.github.golangsupport.lang.psi.GoConstSpec
import io.github.golangsupport.semantic.flow.GoControlFlow
import io.github.golangsupport.semantic.flow.GoReachingDefinitions
import io.github.golangsupport.semantic.types.GoConstant

/**
 * GoLand's `GoDfaConstantCondition` ("Condition 'x' is always 'true'"): a comparison whose value the data flow knows.
 *
 * - nil comparisons of a variable whose nil-ness is known on every path ([GoImpossibleNilCheckInspection.nilChecks]: after
 *   `if x == nil { return }`, after `&T{}` / `make` / a literal, after `x = nil`);
 * - comparisons of a local variable whose every reaching definition stores the same literal value, with a literal or another such
 *   variable (`n := 0` ... `if n == 0`), see [GoFlowConstants]. Named constants never take part: `if debug` or `runtime.GOOS == "linux"`
 *   is configuration that build tags change.
 *
 * Literal-only comparisons (`if 1 > 2`) are reported too. No fix: which branch should stay is the author's decision.
 */
class GoConstantConditionInspection : GoFlowInspectionBase() {

    override fun check(flow: GoControlFlow, holder: ProblemsHolder) {
        val reported = HashSet<GoConditionalExpr>()
        for (check in GoImpossibleNilCheckInspection.nilChecks(flow)) {
            if (reported.add(check.comparison)) holder.registerProblem(check.comparison, message(check.comparison, check.always))
        }
        val reaching by lazy { GoReachingDefinitions.of(flow) }
        for (cmp in GoFlowConstants.ownElements(flow, GoConditionalExpr::class.java)) {
            if (cmp in reported || PsiTreeUtil.getParentOfType(cmp, GoConstSpec::class.java) != null) continue
            val op = GoFlowConstants.operator(cmp) ?: continue
            if (op !in COMPARISONS) continue
            val variables = GoFlowConstants.isVariable(cmp.left) || GoFlowConstants.isVariable(cmp.right)
            val left = GoFlowConstants.valueOf(cmp.left, flow, if (variables) reaching else null) ?: continue
            val right = GoFlowConstants.valueOf(cmp.right, flow, if (variables) reaching else null) ?: continue
            val value = (GoConstant.binary(op, left, right) as? GoConstant.Bool)?.value ?: continue
            holder.registerProblem(cmp, message(cmp, value))
        }
    }

    private fun message(cmp: GoConditionalExpr, value: Boolean): String = "Condition '${cmp.text}' is always '$value'"

    private companion object {
        val COMPARISONS = setOf("==", "!=", "<", "<=", ">", ">=")
    }
}
