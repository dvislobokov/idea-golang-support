package io.github.golangsupport.ide.inspections.flow

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.inspections.GoImportEdits
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCommClause
import io.github.golangsupport.lang.psi.GoExprCaseClause
import io.github.golangsupport.lang.psi.GoForClause
import io.github.golangsupport.lang.psi.GoIncDecStatement
import io.github.golangsupport.lang.psi.GoTypeCaseClause
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.semantic.flow.GoControlFlow
import io.github.golangsupport.semantic.flow.GoFlowAccess
import io.github.golangsupport.semantic.flow.GoFlowNode
import io.github.golangsupport.semantic.flow.GoLiveness
import io.github.golangsupport.semantic.flow.GoValueFate

/**
 * ineffassign: a value stored in a local variable that no path reads afterwards. Not reported: `_`, escaping variables (captured
 * by a closure, address taken), parameters at entry, named results read by a bare `return` or at the exit, zero values and
 * constants (`var x T`, `x := 0`, `x = nil`, `T{}`, `mode := ModeDefault` before a `switch` that assigns in every case), one
 * result of a multi-value call, the initial values of `for` loop variables, variables never read at all (the compiler reports
 * those), values only followed by a panic, and `error` values that [GoErrorOverwrittenInspection] reports. Fix: remove an `=` / `op=` / `++`
 * statement whose right side has no calls.
 */
class GoIneffectualAssignmentInspection : GoFlowInspectionBase() {

    /** `x, y = y, x`: half of a swap is often unread on purpose (both kept symmetric before a `fallthrough`, cmd/compile prove.go). */
    private fun isSwap(element: PsiElement): Boolean {
        val statement = PsiTreeUtil.getParentOfType(element, GoAssignmentStatement::class.java) ?: return false
        val left = statement.leftHandExprList?.expressionList?.map { it.text } ?: return false
        val right = statement.expressionList.map { it.text }
        return left.size > 1 && left.size == right.size && left.sorted() == right.sorted() && left != right
    }

    private fun isGenerated(file: com.intellij.psi.PsiFile): Boolean = io.github.golangsupport.ide.inspections.GoAnalysisScope.isGenerated(file)

    override fun check(flow: GoControlFlow, holder: ProblemsHolder) {
        val liveness = GoLiveness.of(flow) ?: return
        // generated code (the ssa rewrite rules of cmd/compile) is full of harmless dead stores nobody edits by hand
        if (flow.accesses.firstOrNull()?.element?.containingFile?.let { isGenerated(it) } == true) return
        for (access in flow.accesses) {
            if (!isCandidate(flow, access) || isSwap(access.element)) continue
            val fates = liveness.fatesAfter(access)
            if (fates.isEmpty() || GoValueFate.READ in fates) continue
            if (GoFlowChecks.errorOverwrites(flow, liveness, access).isNotEmpty()) continue
            val name = access.variable.name ?: continue
            holder.registerProblem(access.element, "ineffectual assignment to $name", *fixFor(access))
        }
    }

    private fun isCandidate(flow: GoControlFlow, a: GoFlowAccess): Boolean {
        if (!a.isWrite || a.node.kind == GoFlowNode.Kind.ENTRY || !flow.isReachable(a.node)) return false
        if (a.value == null && !a.isCompound) return false
        if (!flow.isTracked(a.variable) || a.variable.name == "_") return false
        val value = a.value
        if (value != null) {
            // one result of a multi-value call (`b, d.len = consume(b)`): kept for symmetry, and only `_` could replace it
            if (a.resultIndex >= 0) return false
            // defaults: zero values and constants (`family := syscall.AF_UNSPEC` before a switch that assigns in every case)
            if (GoFlowChecks.isZeroLiteral(value) || GoFlowChecks.service(value).constantValue(value) != null) return false
        }
        // `for start, end := a, a; …`: the loop variables' initial values
        if (a.node.element?.parent is GoForClause) return false
        if (a.variable is GoVarDefinition && flow.accessesOf(a.variable).none { !it.isWrite }) return false
        return true
    }

    private fun fixFor(a: GoFlowAccess): Array<LocalQuickFix> {
        val statement = a.node.element ?: return emptyArray()
        val removable = when (statement) {
            is GoAssignmentStatement -> statement.leftHandExprList?.expressionList?.size == 1 && statement.expressionList.none { GoFlowChecks.hasSideEffects(it) }
            is GoIncDecStatement -> true
            else -> false
        }
        if (!removable || !isStatementLevel(statement)) return emptyArray()
        return arrayOf(RemoveAssignmentFix(a.variable.name ?: return emptyArray()))
    }

    private fun isStatementLevel(statement: PsiElement): Boolean = when (statement.parent) {
        is GoBlock, is GoExprCaseClause, is GoTypeCaseClause, is GoCommClause -> true
        else -> false
    }

    /** Deletes the assignment statement (its line when it stands alone). */
    class RemoveAssignmentFix(private val name: String) : LocalQuickFix {
        override fun getFamilyName(): String = "Remove ineffectual assignment"

        override fun getName(): String = "Remove assignment to '$name'"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val element = descriptor.psiElement ?: return
            val statement = PsiTreeUtil.getParentOfType(element, GoAssignmentStatement::class.java, GoIncDecStatement::class.java) ?: return
            val file = statement.containingFile
            val document = GoImportEdits.document(file) ?: return
            val range = GoFlowChecks.lineRange(document, statement)
            document.deleteString(range.startOffset, range.endOffset)
            GoImportEdits.commit(file, document)
        }
    }
}
