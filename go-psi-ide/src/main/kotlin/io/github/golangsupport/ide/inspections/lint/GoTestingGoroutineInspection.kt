package io.github.golangsupport.ide.inspections.lint

import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.inspections.GoAnalysisInspectionBase
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoGoStatement
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil.block
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType

/**
 * `t.Fatal`, `t.Fatalf`, `t.FailNow`, `t.SkipNow`, `t.Skip`, `t.Skipf` of a `testing.T` / `B` / `F` / `TB` called from a function literal
 * started with `go` (vet `testinggoroutine`): they stop only the goroutine that calls them, which is not the test's. Use `t.Error` and
 * return instead.
 */
class GoTestingGoroutineInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoGoStatement) return
        val literal = (GoLintPsi.unparen((GoLintPsi.unparen(element.expression) as? GoCallExpr)?.expression)) as? GoFunctionLit ?: return
        val block = literal.block ?: return
        val service = GoSemanticService.getInstance(file.project)
        scan(block, service, holder)
    }

    private fun scan(element: PsiElement, service: GoSemanticService, holder: ProblemsHolder) {
        var child = element.firstChild
        while (child != null) {
            // A nested `go` statement is visited on its own.
            if (child !is GoGoStatement) {
                if (child is GoCallExpr) check(child, service, holder)
                scan(child, service, holder)
            }
            child = child.nextSibling
        }
    }

    private fun check(call: GoCallExpr, service: GoSemanticService, holder: ProblemsHolder) {
        val callee = GoLintPsi.calleeReference(call) ?: return
        val name = callee.identifier.text
        if (name !in FORBIDDEN) return
        val qualifier = callee.expression ?: return
        val type = service.typeOf(qualifier).let { if (it is GoPointerType) it.elem else it }
        if (type !is GoNamedType || type.pkgPath != "testing" || type.name !in TYPES) return
        val shown = if (type.name == "TB") "(testing.TB)" else "(*${type.name})"
        holder.registerProblem(call, "call to $shown.$name from a non-test goroutine")
    }

    private companion object {
        val FORBIDDEN = setOf("Fatal", "Fatalf", "FailNow", "SkipNow", "Skip", "Skipf")
        val TYPES = setOf("T", "B", "F", "TB")
    }
}
