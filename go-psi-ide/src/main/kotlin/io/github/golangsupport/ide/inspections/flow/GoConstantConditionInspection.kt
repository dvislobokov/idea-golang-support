package io.github.golangsupport.ide.inspections.flow

import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoConditionalExpr
import io.github.golangsupport.lang.psi.GoConstSpec
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.semantic.flow.GoControlFlow
import io.github.golangsupport.semantic.flow.GoNil
import io.github.golangsupport.semantic.flow.GoNilness
import io.github.golangsupport.semantic.flow.GoReachingDefinitions
import io.github.golangsupport.semantic.types.GoChanType
import io.github.golangsupport.semantic.types.GoConstant
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoTypeParamType

/**
 * GoLand's `GoDfaConstantCondition` ("Condition 'x' is always 'true'"): a comparison whose value the data flow knows.
 *
 * - nil comparisons of a variable whose nil-ness is known on every path ([GoImpossibleNilCheckInspection.nilChecks]: after
 *   `if x == nil { return }`, after `&T{}` / `make` / a literal, after `x = nil`);
 * - nil comparisons of an operand that is never nil by its form ([neverNil]: `&T{}`, `&v`, `new(T)`, `make(…)`, `[]T{}`, `map[K]V{}`,
 *   a function literal) — vet-class check of MIGRATION step 13A. Operands typed by a type parameter are skipped; declared functions
 *   and method values are govet `nilfunc` (`GoVetNilFuncRule`), not repeated here;
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
            if (cmp in reported) continue
            val always = neverNilComparison(cmp) ?: continue
            if (reported.add(cmp)) holder.registerProblem(cmp, message(cmp, always))
        }
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

    internal companion object {
        val COMPARISONS = setOf("==", "!=", "<", "<=", ">", ">=")

        /** The constant value of `e == nil` / `e != nil` / `nil == e` when [neverNil] holds for `e`, or null. */
        fun neverNilComparison(cmp: GoConditionalExpr): Boolean? {
            val equal = when {
                cmp.eql != null -> true
                cmp.neq != null -> false
                else -> return null
            }
            val service = GoFlowChecks.service(cmp)
            val operand = when {
                GoNilness.isNilLiteral(cmp.right, service) -> cmp.left
                GoNilness.isNilLiteral(cmp.left, service) -> cmp.right
                else -> return null
            }
            return if (neverNil(operand)) !equal else null
        }

        /**
         * Whether [e] can never be nil by its form alone: an address (`&T{}`, `&v`), `new` / `make`, a slice or map composite literal,
         * a function literal. Its type must be a pointer, slice, map, channel or function (never a type parameter or an interface).
         */
        fun neverNil(e: GoExpression?): Boolean {
            val x = GoFlowChecks.unparen(e) ?: return false
            val service = GoFlowChecks.service(x)
            if (GoNilness.nilnessOf(x, service) != GoNil.NOT_NIL) return false
            val type = service.typeOf(x)
            if (type is GoTypeParamType) return false
            val u = type.underlying()
            return u is GoPointerType || u is GoSliceType || u is GoMapType || u is GoChanType || u is GoSignatureType
        }
    }
}
