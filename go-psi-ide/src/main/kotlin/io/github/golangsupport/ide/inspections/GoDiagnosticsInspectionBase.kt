package io.github.golangsupport.ide.inspections

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.semantic.api.GoDiagnostic

/**
 * Base of the inspections that report type-checker diagnostics ([GoDiagnosticsCache]): each
 * subclass owns a set of diagnostic classes ([GoDiagnosticClasses]), so every class can be
 * toggled and its severity changed in the inspection profile. Not dumb-aware: the checker
 * resolves through stub indices, so the inspections are skipped while indexing. Reports nothing
 * while the host serves diagnostics from another source ([GoIdeFeature.DIAGNOSTICS] off).
 */
abstract class GoDiagnosticsInspectionBase : LocalInspectionTool() {

    /** Whether this inspection reports diagnostics of class [code]. */
    protected abstract fun accepts(code: String): Boolean

    /** Whether [d] is reported; shaky classes ([GoDiagnosticClasses.SHAKY]) are hidden by default. */
    protected open fun isReported(d: GoDiagnostic): Boolean = d.code !in GoDiagnosticClasses.SHAKY

    protected open fun highlightType(d: GoDiagnostic): ProblemHighlightType = ProblemHighlightType.GENERIC_ERROR_OR_WARNING

    /** Quick fixes for [d] reported on [element] (the anchor whose range equals the diagnostic's, when one exists). */
    protected open fun fixes(d: GoDiagnostic, file: GoFile, element: PsiElement): List<LocalQuickFix> = emptyList()

    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        if (file !is GoFile || !GoIdeFeatureGate.enabled(GoIdeFeature.DIAGNOSTICS, file.project)) return null
        val out = ArrayList<ProblemDescriptor>()
        for (d in GoDiagnosticsCache.diagnostics(file)) {
            if (!accepts(d.code) || !isReported(d)) continue
            ProgressManager.checkCanceled()
            val (element, rangeInElement) = GoDiagnosticsCache.anchor(file, d.range) ?: continue
            val fixes = fixes(d, file, element)
            out += manager.createProblemDescriptor(
                element, rangeInElement, GoDiagnosticsCache.description(d), highlightType(d), isOnTheFly, *fixes.toTypedArray(),
            )
        }
        return if (out.isEmpty()) null else out.toTypedArray()
    }
}
