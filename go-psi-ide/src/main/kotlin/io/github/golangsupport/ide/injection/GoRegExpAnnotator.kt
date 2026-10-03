package io.github.golangsupport.ide.injection

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiElement
import io.github.golangsupport.lang.psi.GoStringLiteral
import org.intellij.lang.regexp.psi.RegExpBackref
import org.intellij.lang.regexp.psi.RegExpGroup

/**
 * RE2 has no lookahead, atomic groups, branch reset or backreferences, and the platform's annotator has no host hook for them
 * (lookbehind, possessive quantifiers and `\k<name>` it asks the host about). Reports them in regular expressions injected into Go
 * strings only.
 */
class GoRegExpAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val what = when (element) {
            is RegExpBackref -> "backreferences"
            is RegExpGroup -> when (element.type) {
                RegExpGroup.Type.POSITIVE_LOOKAHEAD, RegExpGroup.Type.NEGATIVE_LOOKAHEAD -> "lookahead"
                RegExpGroup.Type.ATOMIC -> "atomic groups"
                RegExpGroup.Type.PCRE_BRANCH_RESET -> "branch reset groups"
                else -> return
            }
            else -> return
        }
        if (InjectedLanguageManager.getInstance(element.project).getInjectionHost(element) !is GoStringLiteral) return
        holder.newAnnotation(HighlightSeverity.ERROR, "RE2 (Go regexp) does not support $what").range(element).create()
    }
}
