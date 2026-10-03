package io.github.golangsupport.ide.inspections.project

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.inspections.GoAnalysisInspectionBase
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.project.api.GoPackageResolver
import io.github.golangsupport.semantic.psi.GoPsiUtil

/**
 * An import that closes a cycle among project packages (`import cycle not allowed`). The graph has one edge per import of a
 * non-test file between project packages ([GoProjectPackages.edges], cached per package); an import `a → b` is a cycle when `a` is
 * reachable from `b`, and the message shows the shortest chain `a → b → … → a`.
 *
 * Test files: an in-package `_test.go` file (package `a`) is compiled into `a`, so its import of `b` is reported when `b` reaches
 * `a` through non-test imports (`go test` says "import cycle not allowed in test"); its imports are not edges of the graph, so they
 * never make a non-test import a cycle. External test packages (`package a_test`) are separate packages and are never checked.
 * Library packages (GOROOT, module cache, vendor) are not part of the graph.
 */
class GoImportCycleInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoImportSpec) return
        val path = element.path
        if (path.isEmpty() || path == "C") return
        val project = file.project
        val vf = GoPsiUtil.originalVirtualFile(file)
        val dir = vf.parent ?: return
        if (vf.fileSystem.protocol != "file" || !GoProjectPackages.isProjectPackage(project, dir)) return
        val resolver = GoPackageResolver.getInstance(project)
        val pkg = resolver.packageOf(dir) ?: return
        if (file.isTestFile && file.packageName != pkg.name) return // external test package
        val target = resolver.resolveImport(path, vf).packageOrNull?.directory ?: return
        if (!GoProjectPackages.isProjectPackage(project, target)) return
        val chain = GoProjectPackages.chain(project, target, dir) ?: return
        val names = (listOf(dir) + chain).map { GoProjectPackages.displayPath(project, it) }
        val prefix = if (file.isTestFile) "import cycle not allowed in test" else "import cycle"
        holder.registerProblem(element.stringLiteral, "$prefix: ${names.joinToString(" → ")}", ProblemHighlightType.GENERIC_ERROR)
    }
}
