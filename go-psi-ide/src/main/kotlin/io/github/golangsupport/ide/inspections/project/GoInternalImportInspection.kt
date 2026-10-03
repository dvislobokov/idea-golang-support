package io.github.golangsupport.ide.inspections.project

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.inspections.GoAnalysisInspectionBase
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.project.api.GoImportResolution
import io.github.golangsupport.project.api.GoPackageResolver
import io.github.golangsupport.semantic.psi.GoPsiUtil

/**
 * `use of internal package X not allowed` (`cmd/go/internal/load.disallowInternal`): an import of a package with an `internal`
 * path element from outside the tree rooted at that element's parent. The rule itself is the project model's
 * ([GoPackageResolver.resolveImport] answers [GoImportResolution.InternalDenied]); the checker still resolves such an import, so
 * nothing else reports it. Covers project packages, the module cache and GOROOT (`internal/abi`, `crypto/internal/…`).
 */
class GoInternalImportInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoImportSpec) return
        val path = element.path
        if (path.isEmpty() || "internal" !in path) return
        val vf = GoPsiUtil.originalVirtualFile(file)
        if (vf.fileSystem.protocol != "file") return
        if (GoPackageResolver.getInstance(file.project).resolveImport(path, vf) !is GoImportResolution.InternalDenied) return
        holder.registerProblem(element.stringLiteral, "use of internal package $path not allowed", ProblemHighlightType.GENERIC_ERROR)
    }
}
