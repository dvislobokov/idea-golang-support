package io.github.golangsupport.ide.inspections.project

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.inspections.GoAnalysisInspectionBase
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoPackageClause
import io.github.golangsupport.semantic.psi.GoPsiUtil

/**
 * `package main` without `func main` in any of its non-test files that match the build context: the linker's
 * `function main is undeclared in the main package`. Reported on the package clause of every non-test file of the package
 * (test files and files excluded by build constraints stay quiet). A non-function `main` is the checker's `cannot declare main - must be func`.
 */
class GoMissingMainFunctionInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoPackageClause || file.packageName != "main" || file.isTestFile || DumbService.isDumb(file.project)) return
        val (dir, pkg) = GoPackageChecks.projectPackageOf(file) ?: return
        if (pkg.name != "main" || GoPsiUtil.originalVirtualFile(file) !in pkg.goFiles) return
        if (GoPackageChecks.hasMain(file.project, dir, pkg)) return
        holder.registerProblem(element.nameIdentifier ?: element, "function main is undeclared in the main package", ProblemHighlightType.GENERIC_ERROR)
    }
}

/**
 * Files of one directory with different package clauses: `go/build`'s `found packages a (a.go) and b (b.go) in <dir>`, on the
 * package clause of each file whose name differs from the first file's (by name order). Files excluded by build constraints and
 * `package documentation` do not count; an external test package `a_test` in a `_test.go` file is package `a` here.
 */
class GoMultiplePackagesInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoPackageClause || DumbService.isDumb(file.project)) return
        val (dir, pkg) = GoPackageChecks.projectPackageOf(file) ?: return
        val message = GoPackageChecks.multiplePackages(file.project, dir, pkg)[GoPsiUtil.originalVirtualFile(file)] ?: return
        holder.registerProblem(element.nameIdentifier ?: element, message, ProblemHighlightType.GENERIC_ERROR)
    }
}
