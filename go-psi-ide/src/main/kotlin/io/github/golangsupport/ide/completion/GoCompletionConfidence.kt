package io.github.golangsupport.ide.completion

import com.intellij.codeInsight.completion.CompletionConfidence
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ThreeState
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.lang.psi.GoTokenSets
import io.github.golangsupport.lang.psi.GoTypes

/**
 * No autopopup in comments, ordinary strings, runes and numbers (`1.` must not pop up members);
 * import path strings do pop up.
 */
class GoCompletionConfidence : CompletionConfidence() {
    override fun shouldSkipAutopopup(editor: Editor, contextElement: PsiElement, psiFile: PsiFile, offset: Int): ThreeState {
        // another source completes: its own confidence decides
        if (!GoIdeFeatureGate.enabled(GoIdeFeature.COMPLETION, psiFile.project)) return ThreeState.UNSURE
        val type = contextElement.node?.elementType ?: return ThreeState.UNSURE
        if (contextElement is PsiComment || GoTokenSets.COMMENTS.contains(type)) return ThreeState.YES
        if (GoTokenSets.STRING_LITERALS.contains(type)) {
            val literal = contextElement.parent
            return if (literal is GoStringLiteral && literal.parent is GoImportSpec) ThreeState.NO else ThreeState.YES
        }
        if (GoTokenSets.NUMBERS.contains(type) || type == GoTypes.CHAR) return ThreeState.YES
        return ThreeState.UNSURE
    }
}
