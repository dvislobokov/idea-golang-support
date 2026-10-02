package io.github.golangsupport.ide.documentation

import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.util.ProcessingContext
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.lang.GoLanguage

/**
 * One name of a doc link in a comment (`Name` and `Method` of `[Name.Method]` are two references): Ctrl+click, Find Usages and Rename
 * reach doc comments through it. Soft: a link to nothing is no error.
 */
class GoDocLinkReference(comment: PsiComment, val link: GoDocLink, val index: Int) : PsiReferenceBase<PsiComment>(comment, link.nameRanges[index], true) {

    override fun resolve(): PsiElement? = ResolveCache.getInstance(element.project).resolveWithCaching(this, RESOLVER, false, false)

    /** The comment is one leaf: its text with the new name in place of this one. */
    override fun handleElementRename(newElementName: String): PsiElement {
        val leaf = element as? LeafPsiElement ?: return element
        val range = rangeInElement
        return leaf.replaceWithText(leaf.text.replaceRange(range.startOffset, range.endOffset, newElementName)).psi
    }

    override fun getVariants(): Array<Any> = emptyArray()

    private companion object {
        val RESOLVER = ResolveCache.AbstractResolver<GoDocLinkReference, PsiElement> { ref, _ -> GoDocLinks.resolve(ref.element, ref.link).getOrNull(ref.index) }
    }
}

/** `psi.referenceContributor`: the doc links of Go comments ([GoDocLinks.linksIn]); with the navigation group of the gate. */
class GoDocLinkReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiComment().withLanguage(GoLanguage), object : PsiReferenceProvider() {
            override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                val comment = element as? PsiComment ?: return PsiReference.EMPTY_ARRAY
                if (!GoIdeFeatureGate.enabled(GoIdeFeature.NAVIGATION, comment.project)) return PsiReference.EMPTY_ARRAY
                val links = GoDocLinks.linksIn(comment)
                if (links.isEmpty()) return PsiReference.EMPTY_ARRAY
                return links.flatMap { link -> link.names.indices.map { GoDocLinkReference(comment, link, it) } }.toTypedArray()
            }
        })
    }
}
