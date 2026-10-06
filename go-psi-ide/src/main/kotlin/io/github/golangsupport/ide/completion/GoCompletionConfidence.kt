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
 * import path strings, a `%` directive in a call's string argument (Printf verbs) and the layout string of a `time` call do pop up.
 */
class GoCompletionConfidence : CompletionConfidence() {
    override fun shouldSkipAutopopup(editor: Editor, contextElement: PsiElement, psiFile: PsiFile, offset: Int): ThreeState {
        // another source completes: its own confidence decides
        if (!GoIdeFeatureGate.enabled(GoIdeFeature.COMPLETION, psiFile.project)) return ThreeState.UNSURE
        val type = contextElement.node?.elementType ?: return ThreeState.UNSURE
        if (contextElement is PsiComment || GoTokenSets.COMMENTS.contains(type)) return ThreeState.YES
        if (GoTokenSets.STRING_LITERALS.contains(type)) {
            val literal = contextElement.parent
            if (literal is GoStringLiteral && literal.parent is GoImportSpec) return ThreeState.NO
            // a raw-string struct tag at a key, name or option position (the host's tag completion)
            if (GoStructTagCompletion.positionAt(contextElement, offset) != null) return ThreeState.NO
            // `"%` in a call argument: Printf verbs (GoFormatVerbProvider); the provider checks that the call is printf-like.
            if (GoFormatVerbCompletion.isDirectivePosition(contextElement, offset)) return ThreeState.NO
            // the layout of `t.Format("…")`, `time.Parse("…", s)`: GoLand's layout elements (GoTimeLayoutProvider; resolves the call)
            return if (GoTimeLayoutCompletion.layoutLiteral(contextElement) != null) ThreeState.NO else ThreeState.YES
        }
        if (GoTokenSets.NUMBERS.contains(type) || type == GoTypes.CHAR) return ThreeState.YES
        return ThreeState.UNSURE
    }
}
