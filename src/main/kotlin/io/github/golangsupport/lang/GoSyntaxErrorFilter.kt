package io.github.golangsupport.lang

import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.psi.PsiErrorElement
import io.github.golangsupport.lang.psi.GoFile

/**
 * The syntax errors of the plugin's parser show only when it is their source ([GoFeature.SYNTAX_ERRORS], MIGRATION.md step 8a): with gopls
 * on duty its `syntax` diagnostics underline the same places, and [io.github.golangsupport.lsp] drops those in the other case. Settings and dumb
 * mode only, no indexes: the error elements are highlighted while the IDE indexes too.
 */
class GoSyntaxErrorFilter : HighlightErrorFilter() {
    override fun shouldHighlightErrorElement(element: PsiErrorElement): Boolean =
        element.containingFile !is GoFile || GoFeatures.native(GoFeature.SYNTAX_ERRORS, element.project)
}
