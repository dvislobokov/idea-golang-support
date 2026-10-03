package io.github.golangsupport.ide.inspections.lint

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.inspections.GoAnalysisInspectionBase
import io.github.golangsupport.lang.psi.GoDeferStatement
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration

/** `defer` in the body of a `for` / `range` loop (revive `defer`, "loop"): it runs when the function returns, not at the end of the iteration. */
class GoDeferInLoopInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoDeferStatement) return
        var e: PsiElement? = element.parent
        while (e != null && e !is GoFile) {
            if (e is GoFunctionLit || e is GoFunctionOrMethodDeclaration) return
            if (e is GoForStatement) {
                holder.registerProblem(element.defer, "defer in a loop runs only when the function returns", ProblemHighlightType.WEAK_WARNING)
                return
            }
            e = e.parent
        }
    }
}
