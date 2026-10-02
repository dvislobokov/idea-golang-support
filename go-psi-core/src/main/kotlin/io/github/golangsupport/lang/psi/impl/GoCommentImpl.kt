package io.github.golangsupport.lang.psi.impl

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistry
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.tree.IElementType

/**
 * Go `//` and `/* */` comments. Behaves like the platform's `PsiCommentImpl` (same visitor
 * callback, same `toString`, references from providers so URLs stay clickable) but is deliberately
 * not a `PsiLanguageInjectionHost`: the platform probes every injection host for injected
 * languages on reformat and highlighting, which was about 40% of a Go reformat. Go has no
 * injections into comments; directives (`//go:build`, `//go:embed`, ...) stay plain comments.
 */
class GoCommentImpl(type: IElementType, text: CharSequence) : LeafPsiElement(type, text), PsiComment {

    override fun getTokenType(): IElementType = elementType

    override fun accept(visitor: PsiElementVisitor) {
        visitor.visitComment(this)
    }

    override fun getReferences(): Array<PsiReference> = ReferenceProvidersRegistry.getReferencesFromProviders(this)

    override fun toString(): String = "PsiComment($elementType)"
}
