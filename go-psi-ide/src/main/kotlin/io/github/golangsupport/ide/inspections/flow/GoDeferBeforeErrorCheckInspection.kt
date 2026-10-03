package io.github.golangsupport.ide.inspections.flow

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.inspections.GoImportEdits
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoDeferStatement
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.semantic.flow.GoControlFlow
import io.github.golangsupport.semantic.flow.GoFlowAccess
import io.github.golangsupport.semantic.psi.GoPsiUtil.condition
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoPointerType

/**
 * `x, err := f()` and then `defer x.Close()` (or `defer x.Body.Close()`) before `err` is checked: when `f` fails, `x` is usually
 * nil and the deferred call panics. Fix: move the `defer` after the `if err != nil {…}` that follows it.
 */
class GoDeferBeforeErrorCheckInspection : GoFlowInspectionBase() {

    override fun check(flow: GoControlFlow, holder: ProblemsHolder) {
        for (defer in flow.deferStatements) {
            val node = flow.nodeFor(defer) ?: continue
            if (!flow.isReachable(node)) continue
            val call = defer.expression as? GoCallExpr ?: continue
            val method = call.expression as? GoReferenceExpression ?: continue
            if (method.identifier.text != "Close") continue
            val receiver = method.expression as? GoReferenceExpression ?: continue
            val root = when {
                receiver.expression == null -> receiver
                receiver.identifier.text == "Body" -> receiver.expression as? GoReferenceExpression ?: continue
                else -> continue
            }
            if (root.expression != null) continue
            val variable = flow.variableOf(root) ?: continue
            if (!mayBeNil(root)) continue
            val (source, error) = sourceStatement(flow, defer, variable) ?: continue
            if (between(source, defer).any { mentions(it, error) }) continue
            val name = variable.name ?: continue
            val errName = error.name ?: continue
            val fix = PsiTreeUtil.getNextSiblingOfType(defer, GoStatement::class.java)?.takeIf { checks(it, errName) }?.let { arrayOf<LocalQuickFix>(MoveDeferFix()) } ?: emptyArray()
            holder.registerProblem(call, "$name is used before $errName is checked; $name may be nil", ProblemHighlightType.WARNING, *fix)
        }
    }

    private fun mayBeNil(ref: GoReferenceExpression): Boolean {
        val type = GoFlowChecks.service(ref).typeOf(ref).underlying()
        return type is GoPointerType || type is GoInterfaceType
    }

    /**
     * The statement before [defer] in the same block that last wrote [variable], when it also wrote an `error` variable from the
     * same call (`x, err := f()`): that statement and the error variable.
     */
    private fun sourceStatement(flow: GoControlFlow, defer: GoDeferStatement, variable: GoNamedElement): Pair<GoStatement, GoNamedElement>? {
        var s = PsiTreeUtil.getPrevSiblingOfType(defer, GoStatement::class.java)
        while (s != null) {
            val accesses = flow.nodeFor(s)?.accesses.orEmpty()
            val write = accesses.lastOrNull { it.variable == variable && it.isWrite }
            if (write != null) {
                val call = write.value as? GoCallExpr ?: return null
                if (write.resultIndex < 0) return null
                val error = accesses.firstOrNull { it.isWrite && it.value === call && it !== write && isError(flow, it) } ?: return null
                return s to error.variable
            }
            if (PsiTreeUtil.findChildrenOfType(s, GoReferenceExpression::class.java).any { it.expression == null && flow.variableOf(it) == variable }) return null
            s = PsiTreeUtil.getPrevSiblingOfType(s, GoStatement::class.java)
        }
        return null
    }

    private fun isError(flow: GoControlFlow, a: GoFlowAccess) = flow.isTracked(a.variable) && GoFlowChecks.isErrorVariable(a.variable)

    private fun between(from: GoStatement, to: GoStatement): List<GoStatement> {
        val out = ArrayList<GoStatement>()
        var s = PsiTreeUtil.getNextSiblingOfType(from, GoStatement::class.java)
        while (s != null && s !== to) {
            out += s
            s = PsiTreeUtil.getNextSiblingOfType(s, GoStatement::class.java)
        }
        return out
    }

    private fun mentions(element: PsiElement, v: GoNamedElement): Boolean =
        PsiTreeUtil.findChildrenOfType(element, GoReferenceExpression::class.java).any { it.expression == null && it.identifier.text == v.name }

    /** `if err != nil {…}` / `if err == nil {…}` (any condition mentioning err). */
    private fun checks(s: GoStatement, errName: String): Boolean {
        val condition = (s as? GoIfStatement)?.condition ?: return false
        return PsiTreeUtil.findChildrenOfType(condition, GoReferenceExpression::class.java).any { it.expression == null && it.identifier.text == errName } ||
            (condition is GoReferenceExpression && condition.identifier.text == errName)
    }

    /** Moves the `defer` line after the following `if` statement. */
    class MoveDeferFix : LocalQuickFix {
        override fun getFamilyName(): String = "Move defer after the error check"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val defer = PsiTreeUtil.getParentOfType(descriptor.psiElement, GoDeferStatement::class.java, false) ?: return
            val check = PsiTreeUtil.getNextSiblingOfType(defer, GoStatement::class.java) as? GoIfStatement ?: return
            val file = defer.containingFile
            val document = GoImportEdits.document(file) ?: return
            val text = document.charsSequence
            var indentStart = defer.textRange.startOffset
            while (indentStart > 0 && (text[indentStart - 1] == ' ' || text[indentStart - 1] == '\t')) indentStart--
            val indent = text.subSequence(indentStart, defer.textRange.startOffset).toString()
            val deferText = defer.text
            val insertAt = check.textRange.endOffset
            val removed = GoFlowChecks.lineRange(document, defer)
            document.insertString(insertAt, "\n$indent$deferText")
            document.deleteString(removed.startOffset, removed.endOffset)
            GoImportEdits.commit(file, document)
        }
    }
}
