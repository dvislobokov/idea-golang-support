package io.github.golangsupport.ide.inspections.flow

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.inspections.GoAnalysisPsi
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoVarDeclaration
import io.github.golangsupport.semantic.flow.GoControlFlow
import io.github.golangsupport.semantic.flow.GoLiveness
import io.github.golangsupport.semantic.flow.GoValueFate

/**
 * vet `lostcancel`: the cancel function returned by `context.WithCancel` / `WithTimeout` / `WithDeadline` (and their `Cause`
 * variants) is discarded (`_`), or some path from the assignment reaches a `return` without calling, deferring, passing or
 * storing it. Paths that end in a panic or `os.Exit` do not count. A cancel variable captured by a closure is not checked.
 * Reported at the assignment, like vet.
 */
class GoLostCancelInspection : GoFlowInspectionBase() {

    override fun check(flow: GoControlFlow, holder: ProblemsHolder) {
        var liveness: GoLiveness? = null
        for (node in flow.nodes) {
            if (!flow.isReachable(node)) continue
            val statement = node.element ?: continue
            val (call, targets) = contextCall(statement) ?: continue
            val function = withName(call) ?: continue
            val target = targets.getOrNull(1) ?: continue
            if (target.text == "_") {
                holder.registerProblem(
                    target, "the cancel function returned by context.$function should be called, not discarded, to avoid a context leak",
                    ProblemHighlightType.WARNING,
                )
                continue
            }
            val access = node.accesses.firstOrNull { it.isWrite && it.element === target } ?: continue
            if (!flow.isTracked(access.variable)) continue
            val fates = (liveness ?: GoLiveness.of(flow)?.also { liveness = it } ?: return).fatesAfter(access)
            if (GoValueFate.RETURNED !in fates) continue
            holder.registerProblem(
                target, "the ${access.variable.name} function is not used on all paths (possible context leak)", ProblemHighlightType.WARNING,
            )
        }
    }

    /** `a, b := call` / `a, b = call` / `var a, b = call` with a single call on the right: the call and the targets. */
    private fun contextCall(statement: PsiElement): Pair<GoCallExpr, List<PsiElement>>? = when (statement) {
        is GoShortVarDeclaration -> (statement.expressionList.singleOrNull() as? GoCallExpr)?.let { it to statement.varDefinitionList }
        is GoAssignmentStatement -> (statement.expressionList.singleOrNull() as? GoCallExpr)?.takeIf { statement.assignOp?.assign != null }
            ?.let { it to statement.leftHandExprList?.expressionList.orEmpty() }
        is GoVarDeclaration -> statement.varSpecList.singleOrNull()?.let { spec -> (spec.expressionList.singleOrNull() as? GoCallExpr)?.let { it to spec.varDefinitionList } }
        else -> null
    }

    /** `WithCancel`, … when [call] calls that function of package `context`. */
    private fun withName(call: GoCallExpr): String? {
        val callee = call.expression as? GoReferenceExpression ?: return null
        val name = callee.identifier.text
        if (name !in FUNCTIONS) return null
        val target = GoFlowChecks.service(call).resolve(callee).firstOrNull() as? GoFunctionDeclaration ?: return null
        return name.takeIf { GoAnalysisPsi.packagePath(target) == "context" }
    }

    private companion object {
        val FUNCTIONS = setOf("WithCancel", "WithTimeout", "WithDeadline", "WithCancelCause", "WithTimeoutCause", "WithDeadlineCause")
    }
}
