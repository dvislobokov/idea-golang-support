package io.github.golangsupport.ide.inspections.flow

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.inspections.GoAnalysisInspectionBase
import io.github.golangsupport.ide.inspections.GoImportEdits
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoGoStatement
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.block
import io.github.golangsupport.semantic.psi.GoPsiUtil.expressions

/**
 * vet `waitgroup`: `wg.Add(…)` (`sync.WaitGroup`) as a statement inside a function literal started by `go`: the goroutine may run
 * after `wg.Wait()` returned. Reported when the `Add` is a statement of the literal's own body, or anywhere in it when the literal
 * also calls `wg.Done()`; quiet when the literal also calls `wg.Wait()`. Fix: move the `Add` before the `go` statement, offered
 * when it is a statement of the literal's body and its arguments do not refer to the literal's parameters or locals.
 */
class GoWaitGroupAddInGoroutineInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        val go = element as? GoGoStatement ?: return
        val literal = (GoFlowChecks.unparen((GoFlowChecks.unparen(go.expression) as? GoCallExpr)?.expression) as? GoFunctionLit) ?: return
        val body = literal.block ?: return
        val calls = PsiTreeUtil.findChildrenOfType(body, GoReferenceExpression::class.java).filter { PsiTreeUtil.getParentOfType(it, GoFunctionLit::class.java) === literal }
        for (ref in calls) {
            if (ref.identifier.text != "Add") continue
            val call = ref.parent as? GoCallExpr ?: continue
            val statement = (call.parent?.parent as? GoSimpleStatement)?.takeIf { it.statement == null && it.expressions.singleOrNull() === call } ?: continue
            val receiver = ref.expression?.text?.filterNot { it.isWhitespace() } ?: continue
            val (target, path) = GoResourceFlow.callee(call) ?: continue
            if (path != "sync" || target !is GoMethodDeclaration || GoResourceFlow.receiverTypeName(target) != "WaitGroup") continue
            fun calls(name: String) = calls.any { it.identifier.text == name && it.expression?.text?.filterNot { c -> c.isWhitespace() } == receiver }
            if (calls("Wait")) continue
            val direct = statement.parent === body
            if (!direct && !calls("Done")) continue
            val fix = if (direct && argumentsMovable(call, literal)) arrayOf<LocalQuickFix>(MoveAddFix()) else emptyArray()
            holder.registerProblem(call, "$receiver.Add called inside the goroutine; call it before the go statement", ProblemHighlightType.WARNING, *fix)
        }
    }

    /** The arguments of [call] refer to nothing declared inside [literal] (and everything resolves). */
    private fun argumentsMovable(call: GoCallExpr, literal: GoFunctionLit): Boolean {
        val args = call.argumentList ?: return false
        val service = GoFlowChecks.service(call)
        return PsiTreeUtil.findChildrenOfType(args, GoReferenceExpression::class.java).all { ref ->
            if (ref.expression != null) return@all true
            val target = service.resolve(ref).firstOrNull() ?: return@all false
            !PsiTreeUtil.isAncestor(literal, target, false)
        }
    }

    /** Moves the `wg.Add(…)` line before the `go` statement. */
    class MoveAddFix : LocalQuickFix {
        override fun getFamilyName(): String = "Move Add before the go statement"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val call = descriptor.psiElement as? GoCallExpr ?: return
            val statement = call.parent?.parent as? GoSimpleStatement ?: return
            val go = PsiTreeUtil.getParentOfType(statement, GoGoStatement::class.java) ?: return
            val file = go.containingFile
            val document = GoImportEdits.document(file) ?: return
            val chars = document.charsSequence
            var indentStart = go.textRange.startOffset
            while (indentStart > 0 && (chars[indentStart - 1] == ' ' || chars[indentStart - 1] == '\t')) indentStart--
            val indent = chars.subSequence(indentStart, go.textRange.startOffset).toString()
            val text = statement.text
            val removed = GoFlowChecks.lineRange(document, statement)
            document.deleteString(removed.startOffset, removed.endOffset)
            document.insertString(go.textRange.startOffset, "$text\n$indent")
            GoImportEdits.commit(file, document)
        }
    }
}
