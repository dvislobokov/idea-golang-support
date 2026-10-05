package io.github.golangsupport.lang.psi.impl

import com.intellij.psi.LiteralTextEscaper
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistry
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.tree.IElementType
import io.github.golangsupport.lang.psi.GoTypes

/**
 * Go `//` and `/* */` comments. Behaves like the platform's `PsiCommentImpl` (same visitor
 * callback, same `toString`, references from providers so URLs stay clickable) but is deliberately
 * not a `PsiLanguageInjectionHost`: the platform probes every injection host for injected
 * languages on reformat and highlighting, which was about 40% of a Go reformat. The one exception
 * is [GoGenerateCommentImpl]; other directives (`//go:build`, `//go:embed`, ...) stay plain comments.
 */
open class GoCommentImpl(type: IElementType, text: CharSequence) : LeafPsiElement(type, text), PsiComment {

    override fun getTokenType(): IElementType = elementType

    override fun accept(visitor: PsiElementVisitor) {
        visitor.visitComment(this)
    }

    override fun getReferences(): Array<PsiReference> = ReferenceProvidersRegistry.getReferencesFromProviders(this)

    override fun toString(): String = "PsiComment($elementType)"
}

/**
 * A `//go:generate` line: the only comment that is an injection host (go-psi-ide injects Shell Script into the command where the IDE has
 * it). Such lines are rare, so the probing cost [GoCommentImpl] avoids does not come back.
 */
class GoGenerateCommentImpl(type: IElementType, text: CharSequence) : GoCommentImpl(type, text), PsiLanguageInjectionHost {

    override fun isValidHost(): Boolean = true

    // the new leaf comes from GoASTFactory again; the prefix is outside the injected range, so it stays a go:generate comment
    override fun updateText(text: String): PsiLanguageInjectionHost = replaceWithText(text) as? PsiLanguageInjectionHost ?: this

    override fun createLiteralTextEscaper(): LiteralTextEscaper<out PsiLanguageInjectionHost> = LiteralTextEscaper.createSimple(this, true)

    companion object {
        const val PREFIX = "//go:generate"

        /** `//go:generate` followed by a space, a tab or nothing, as `go generate` reads it. */
        fun isGenerate(type: IElementType, text: CharSequence): Boolean =
            type == GoTypes.LINE_COMMENT && text.startsWith(PREFIX) && (text.length == PREFIX.length || text[PREFIX.length] == ' ' || text[PREFIX.length] == '\t')
    }
}
