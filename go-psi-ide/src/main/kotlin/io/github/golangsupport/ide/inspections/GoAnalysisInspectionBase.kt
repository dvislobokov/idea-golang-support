package io.github.golangsupport.ide.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.scope.GoPackageModel

/**
 * Base of the analysis inspections that walk the PSI (vet / staticcheck-style checks, not checker diagnostics): silent while the host
 * serves diagnostics from another source ([GoIdeFeature.DIAGNOSTICS] off), like [GoDiagnosticsInspectionBase]. Not dumb-aware:
 * types and resolve go through stub indices. Everything per body comes from [GoSemanticService], cached on `GoBodyCache`.
 */
abstract class GoAnalysisInspectionBase : LocalInspectionTool() {

    /** Called for every element of [file]. */
    protected abstract fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile)

    /** Whether the inspection looks at [file] at all (checked once per pass, after the gate): the Go version gate of `gofix`. */
    protected open fun isApplicable(file: GoFile): Boolean = true

    final override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? GoFile ?: return PsiElementVisitor.EMPTY_VISITOR
        if (!GoIdeFeatureGate.enabled(GoIdeFeature.DIAGNOSTICS, file.project)) return PsiElementVisitor.EMPTY_VISITOR
        if (!isApplicable(file)) return PsiElementVisitor.EMPTY_VISITOR
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) = visit(element, holder, file)
        }
    }
}

/** Package-path checks of resolved declarations shared by the analysis inspections. */
internal object GoAnalysisPsi {

    /** The import path of the package declaring [element] (`context`, `errors`, ...). */
    fun packagePath(element: PsiElement): String? {
        val file = element.containingFile as? GoFile ?: return null
        return GoPackageModel.getInstance(element.project).packagePathOf(file)
    }

    /** Whether [type] is the named type [name] of package [path] (`context.Context`). */
    fun isNamed(type: io.github.golangsupport.semantic.types.GoType, path: String, name: String): Boolean =
        type is io.github.golangsupport.semantic.types.GoNamedType && type.name == name && type.pkgPath == path

    /** Whether [type] is the predeclared `error`. */
    fun isError(type: io.github.golangsupport.semantic.types.GoType): Boolean =
        type is io.github.golangsupport.semantic.types.GoNamedType && type.name == "error" && (type.declaration.containingFile as? GoFile)?.packageName == "builtin"
}
