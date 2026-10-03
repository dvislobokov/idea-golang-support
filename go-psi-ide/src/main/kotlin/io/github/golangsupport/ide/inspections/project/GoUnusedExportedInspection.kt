package io.github.golangsupport.ide.inspections.project

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.safeDelete.SafeDeleteHandler
import com.intellij.util.Processor
import io.github.golangsupport.ide.inspections.GoAnalysisInspectionBase
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.semantic.psi.GoPsiUtil

/**
 * An exported package-level function, type, variable or constant that nothing in the project refers to (outside its own
 * declaration). Off by default: a project-wide reference search per exported name.
 *
 * To stay quiet on library API, only packages whose every importer is project code are checked ([GoProjectPackages.isClosed]):
 * packages under an `internal/` element, and packages of a project module that has a `package main` (an application). Skipped:
 * methods (interfaces call them implicitly), `package main`, `_test.go` files, `Test*` / `Benchmark*` / `Example*` / `Fuzz*`,
 * functions marked `//export` or `//go:linkname`. A reference from a test file counts as a use. Fix: Safe Delete.
 */
class GoUnusedExportedInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoFunctionDeclaration && element !is GoTypeSpec && element !is GoVarDefinition && element !is GoConstDefinition) return
        val named = element as GoNamedElement
        val name = named.name ?: return
        if (!named.isPublic() || file.isTestFile || file.packageName == "main" || TEST_PREFIXES.any(name::startsWith)) return
        if (element !is GoFunctionDeclaration && PsiTreeUtil.getParentOfType(element, GoBlock::class.java) != null) return
        if (element is GoFunctionDeclaration && hasLinkDirective(element)) return
        val vf = GoPsiUtil.originalVirtualFile(file)
        val dir = vf.parent ?: return
        if (vf.fileSystem.protocol != "file" || !GoProjectPackages.isClosed(file.project, dir)) return
        val used = !ReferencesSearch.search(element, GlobalSearchScope.projectScope(file.project)).forEach(Processor { ref ->
            PsiTreeUtil.isAncestor(element, ref.element, false) // a recursive call or a self-referencing type is not a use
        })
        if (used) return
        val identifier = named.nameIdentifier ?: return
        holder.registerProblem(identifier, "Exported ${kind(element)} '$name' is never used in the project", GoSafeDeleteUnusedFix())
    }

    private fun kind(element: PsiElement): String = when (element) {
        is GoFunctionDeclaration -> "function"
        is GoTypeSpec -> "type"
        is GoVarDefinition -> "variable"
        else -> "constant"
    }

    private fun hasLinkDirective(decl: GoFunctionDeclaration): Boolean {
        val func = GoPsiUtil.childToken(decl, GoTypes.FUNC) ?: return false
        val directives = decl.text.substring(0, func.startOffsetInParent)
        return "//export " in directives || "//go:linkname" in directives
    }

    private companion object {
        val TEST_PREFIXES = listOf("Test", "Benchmark", "Example", "Fuzz")
    }
}

/** Platform Safe Delete of the declaration (dialog, usage search and conflicts), not a blind text removal. */
class GoSafeDeleteUnusedFix : LocalQuickFix {
    override fun getFamilyName(): String = "Safe delete"

    override fun startInWriteAction(): Boolean = false

    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = PsiTreeUtil.getParentOfType(descriptor.psiElement, GoNamedElement::class.java, false) ?: return
        SafeDeleteHandler.invoke(project, arrayOf(element), true)
    }
}
