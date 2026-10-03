package io.github.golangsupport.ide.inspections.flow

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.expressions
import io.github.golangsupport.lang.psi.GoCallExpr
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.inspections.GoImportEdits
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCommClause
import io.github.golangsupport.lang.psi.GoExprCaseClause
import io.github.golangsupport.lang.psi.GoLabeledStatement
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoTypeCaseClause
import io.github.golangsupport.semantic.flow.GoControlFlow

/**
 * vet `unreachable`: the first statement of a statement list that the control-flow graph cannot reach (after `return`, `panic`,
 * `os.Exit`, `log.Fatal`, a `for {}` without `break`, `goto`, a `switch` whose clauses all end). One report per run. A labeled
 * statement ends the run (a `goto` may jump to it). Fix: delete the run.
 */
class GoUnreachableCodeInspection : GoFlowInspectionBase() {

    private fun afterTerminatingCall(first: GoStatement): Boolean {
        val previous = PsiTreeUtil.getPrevSiblingOfType(first, GoStatement::class.java) as? GoSimpleStatement ?: return false
        val call = GoFlowChecks.unparen(previous.expressions.singleOrNull()) as? GoCallExpr ?: return false
        return call.expression?.text != "panic"
    }

    override fun check(flow: GoControlFlow, holder: ProblemsHolder) {
        val live = flow.nodes.filter { flow.isReachable(it) && it.element != null && it.element !== flow.body }.map { it.element!!.textRange }
        val firsts = LinkedHashMap<PsiElement, GoStatement>()
        for (node in flow.nodes) {
            val element = node.element
            if (flow.isReachable(node) || element == null || element === flow.body) continue
            val top = outermostDead(element, flow, live) ?: continue
            val container = top.parent
            val known = firsts[container]
            if (known == null || top.textRange.startOffset < known.textRange.startOffset) firsts[container] = top
        }
        for (first in firsts.values) {
            if (first is GoLabeledStatement) continue
            // after os.Exit / log.Fatal / t.Fatal the compiler still wants its `return`; vet unreachable only counts return, panic, goto
            if (afterTerminatingCall(first)) continue
            // A dead statement inside a dead run is reported by the run it belongs to.
            if (insideReportedRun(first, firsts.values)) continue
            // greyed out like unused code (as GoLand shows it), over the whole run the fix deletes
            val last = run(first).last()
            holder.registerProblem(holder.manager.createProblemDescriptor(first, last, "unreachable code", ProblemHighlightType.LIKE_UNUSED_SYMBOL, holder.isOnTheFly, DeleteRunFix()))
        }
    }

    /** The highest statement around [element] that sits in a statement list and holds no reachable node, or null. */
    private fun outermostDead(element: PsiElement, flow: GoControlFlow, live: List<com.intellij.openapi.util.TextRange>): GoStatement? {
        var top: GoStatement? = null
        var e: PsiElement? = element
        while (e != null && e !== flow.body) {
            if (e is GoStatement && isListContainer(e.parent) && e.parent !== null) {
                val r = e.textRange
                if (live.none { r.contains(it) }) top = e else break
            }
            e = e.parent
        }
        return top
    }

    private fun insideReportedRun(statement: GoStatement, all: Collection<GoStatement>): Boolean =
        all.any { other -> other !== statement && other.textRange.contains(statement.textRange) }

    private class DeleteRunFix : LocalQuickFix {
        override fun getFamilyName(): String = "Delete unreachable code"

        override fun getName(): String = "Delete unreachable code"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            // a range descriptor gives the common parent as psiElement: the run starts at startElement
            val first = descriptor.startElement as? GoStatement ?: return
            val last = run(first).last()
            val file = first.containingFile
            val document = GoImportEdits.document(file) ?: return
            val start = GoFlowChecks.lineRange(document, first).startOffset
            val end = GoFlowChecks.lineRange(document, last).endOffset
            document.deleteString(start, end)
            GoImportEdits.commit(file, document)
        }
    }

    private companion object {
        /** [first] and the statements after it up to a label (a `goto` may jump there): what is reported and deleted together. */
        fun run(first: GoStatement): List<GoStatement> = statements(first.parent).dropWhile { it !== first }.takeWhile { it !is GoLabeledStatement }.ifEmpty { listOf(first) }

        fun isListContainer(e: PsiElement?): Boolean = e is GoBlock || e is GoExprCaseClause || e is GoTypeCaseClause || e is GoCommClause

        fun statements(container: PsiElement?): List<GoStatement> = when (container) {
            is GoBlock -> container.statementList
            is GoExprCaseClause -> container.statementList
            is GoTypeCaseClause -> container.statementList
            is GoCommClause -> container.statementList
            else -> emptyList()
        }
    }
}
