package io.github.golangsupport.ide.inspections.flow

import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCommClause
import io.github.golangsupport.lang.psi.GoExprCaseClause
import io.github.golangsupport.lang.psi.GoExprSwitchStatement
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.block
import io.github.golangsupport.semantic.psi.GoPsiUtil.condition
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoSelectStatement
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoTypeCaseClause
import io.github.golangsupport.lang.psi.GoTypeSwitchStatement
import io.github.golangsupport.semantic.flow.GoControlFlow
import io.github.golangsupport.semantic.flow.GoFlowAccess
import io.github.golangsupport.semantic.flow.GoFlowNode

/**
 * `err` declared with `:=` in an inner block (the body of an `if`, `for`, `switch`, … but not an `if` initializer) shadows an outer
 * `err` that is read after the block, while the inner value is never used beyond nil checks: it is not propagated, the outer
 * (usually nil) value is what the function returns. Quiet as soon as the inner `err` is read in any other way (returned, wrapped,
 * logged, assigned). No fix.
 */
class GoShadowedErrorInspection : GoFlowInspectionBase() {

    override fun check(flow: GoControlFlow, holder: ProblemsHolder) {
        for (node in flow.nodes) {
            if (node.kind != GoFlowNode.Kind.STATEMENT || !flow.isReachable(node)) continue
            val stmt = node.element as? GoShortVarDeclaration ?: continue
            val container = stmt.parent
            if (container !is GoBlock && container !is GoExprCaseClause && container !is GoTypeCaseClause && container !is GoCommClause) continue
            if (container === flow.body) continue
            val inner = node.accesses.firstOrNull { isFreshErr(it, stmt) } ?: continue
            val v = inner.variable
            if (!GoFlowChecks.isErrorVariable(v)) continue
            val reads = flow.accessesOf(v).filter { !it.isWrite }
            if (reads.any { !GoFlowSupport.isNilComparisonOperand(it.element) }) continue
            // `f, err := elf.NewFile(r); if err != nil { return … }`: the inner error is handled by leaving (debug/buildinfo)
            if (reads.any { handledByExit(it.element) }) continue
            val outer = outerOf(flow, v, stmt) ?: continue
            val scope = if (container is GoBlock) container else PsiTreeUtil.getParentOfType(container, GoStatement::class.java) ?: container
            val scopeEnd = scope.textRange.endOffset
            if (flow.accessesOf(outer).none { !it.isWrite && it.element.textRange.startOffset >= scopeEnd && flow.isReachable(it.node) }) continue
            holder.registerProblem(v, "err declared in this block shadows the outer err; the outer value is returned")
        }
    }

    private fun handledByExit(read: PsiElement): Boolean {
        val ifStatement = PsiTreeUtil.getParentOfType(read, GoIfStatement::class.java) ?: return false
        if (ifStatement.condition?.let { PsiTreeUtil.isAncestor(it, read, false) } != true) return false
        val last = ifStatement.block?.statementList?.lastOrNull()?.text?.trimStart() ?: return false
        return EXITS.any { last.startsWith(it) }
    }

    private companion object {
        val EXITS = listOf("return", "goto", "panic(")
    }

    private fun isFreshErr(a: GoFlowAccess, stmt: GoShortVarDeclaration): Boolean =
        a.kind == GoFlowAccess.Kind.DEFINE && a.variable.name == "err" && PsiTreeUtil.isAncestor(stmt, a.variable, true)

    /** The nearest earlier `err` of this function whose scope encloses [stmt]. */
    private fun outerOf(flow: GoControlFlow, inner: GoNamedElement, stmt: PsiElement): GoNamedElement? =
        flow.variables.filter {
            it !== inner && it.name == "err" && it.textRange.startOffset < stmt.textRange.startOffset && GoFlowChecks.isErrorVariable(it) &&
                PsiTreeUtil.isAncestor(scopeOf(flow, it), stmt, true)
        }.maxByOrNull { it.textRange.startOffset }

    private fun scopeOf(flow: GoControlFlow, d: PsiElement): PsiElement {
        var e: PsiElement? = d.parent
        while (e != null && e !== flow.owner) {
            if (e is GoBlock || e is GoExprCaseClause || e is GoTypeCaseClause || e is GoCommClause || e is GoIfStatement || e is GoForStatement ||
                e is GoExprSwitchStatement || e is GoTypeSwitchStatement || e is GoSelectStatement) return e
            e = e.parent
        }
        return flow.owner
    }
}
