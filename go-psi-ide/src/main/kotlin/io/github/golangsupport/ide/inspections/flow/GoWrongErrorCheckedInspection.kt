package io.github.golangsupport.ide.inspections.flow

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.inspections.GoImportEdits
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoConditionalExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoOrExpr
import io.github.golangsupport.lang.psi.GoAndExpr
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.semantic.flow.GoControlFlow
import io.github.golangsupport.semantic.flow.GoFlowAccess
import io.github.golangsupport.semantic.flow.GoLiveness
import io.github.golangsupport.semantic.flow.GoNilness
import io.github.golangsupport.semantic.flow.GoReachingDefinitions
import io.github.golangsupport.semantic.psi.GoPsiUtil.condition
import io.github.golangsupport.semantic.psi.GoPsiUtil.initStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.inner

/**
 * `a, err2 := f()` directly followed by `if err != nil` that tests another error variable whose value was already checked, while
 * `err2` is never read afterwards: the new error is ignored. Fix: test `err2` instead.
 */
class GoWrongErrorCheckedInspection : GoFlowInspectionBase() {

    override fun check(flow: GoControlFlow, holder: ProblemsHolder) {
        val liveness = GoLiveness.of(flow) ?: return
        val reaching = GoReachingDefinitions.of(flow) ?: return
        for (node in flow.nodes) {
            val statement = node.element as? GoStatement ?: continue
            if (statement !is GoShortVarDeclaration && statement !is GoAssignmentStatement || !flow.isReachable(node)) continue
            val fresh = node.accesses.filter { a -> isFreshError(flow, a) }.singleOrNull() ?: continue
            val next = PsiTreeUtil.getNextSiblingOfType(statement, GoStatement::class.java) as? GoIfStatement ?: continue
            if (next.initStatement != null) continue
            val tested = testedVariable(next.condition) ?: continue
            val checked = flow.variableOf(tested) ?: continue
            if (checked == fresh.variable || !flow.isTracked(checked) || !GoFlowChecks.isErrorVariable(checked)) continue
            if (node.accesses.any { it.variable == checked }) continue
            if (liveness.isReadAfter(fresh)) continue
            if (!alreadyChecked(flow, reaching, checked, node, statement.textRange.startOffset)) continue
            val name = fresh.variable.name ?: continue
            holder.registerProblem(tested, "$name is not checked; the condition tests ${tested.text}", TestOtherErrorFix(name))
        }
    }

    /** A write of an `error` variable from a call result. */
    private fun isFreshError(flow: GoControlFlow, a: GoFlowAccess): Boolean =
        a.isWrite && !a.isCompound && flow.isTracked(a.variable) && a.value is GoCallExpr && GoFlowChecks.isErrorVariable(a.variable)

    /** `x != nil`, `x == nil`, or such a comparison as the first operand of `||` / `&&`. */
    private fun testedVariable(condition: GoExpression?): GoReferenceExpression? {
        var c: GoExpression? = condition
        while (true) {
            c = when (c) {
                is GoParenthesesExpr -> c.inner as? GoExpression
                is GoOrExpr -> c.left
                is GoAndExpr -> c.left
                else -> break
            }
        }
        val cmp = c as? GoConditionalExpr ?: return null
        if (cmp.eql == null && cmp.neq == null) return null
        val service = GoFlowChecks.service(cmp)
        val operand = when {
            GoNilness.isNilLiteral(cmp.right, service) -> cmp.left
            GoNilness.isNilLiteral(cmp.left, service) -> cmp.right
            else -> null
        }
        return (operand as? GoReferenceExpression)?.takeIf { it.expression == null }
    }

    /** Every value of [variable] reaching [node] was read between its write and [before]. */
    private fun alreadyChecked(flow: GoControlFlow, reaching: GoReachingDefinitions, variable: io.github.golangsupport.lang.psi.GoNamedElement, node: io.github.golangsupport.semantic.flow.GoFlowNode, before: Int): Boolean {
        val defs = reaching.reaching(variable, node)
        if (defs.isEmpty()) return false
        val reads = flow.accessesOf(variable).filter { !it.isWrite }.map { it.element.textRange.startOffset }
        return defs.all { d -> val at = d.element.textRange.startOffset; reads.any { it in (at + 1) until before } }
    }

    /** Replaces the tested variable with the fresh one. */
    class TestOtherErrorFix(private val name: String) : LocalQuickFix {
        override fun getFamilyName(): String = "Check the new error"

        override fun getName(): String = "Check '$name' instead"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val ref = descriptor.psiElement as? GoReferenceExpression ?: return
            val file = ref.containingFile
            val document = GoImportEdits.document(file) ?: return
            document.replaceString(ref.textRange.startOffset, ref.textRange.endOffset, name)
            GoImportEdits.commit(file, document)
        }
    }
}
