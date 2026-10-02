package io.github.golangsupport.lang.psi.impl

import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.ItemPresentationProviders
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.search.SearchScope
import com.intellij.psi.stubs.IStubElementType
import com.intellij.psi.stubs.NamedStub
import com.intellij.util.IncorrectOperationException
import io.github.golangsupport.lang.psi.GoElementFactory
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoTypes

/**
 * Base class of stub-based named declarations. [getName] reads the (green) stub when there is one,
 * so it never loads the AST; the name identifier defaults to the direct `IDENTIFIER` child.
 */
abstract class GoNamedElementImpl<S : NamedStub<*>> : GoStubbedElementImpl<S>, GoNamedElement {
    constructor(node: ASTNode) : super(node)
    constructor(stub: S, type: IStubElementType<*, *>) : super(stub, type)

    override fun getNameIdentifier(): PsiElement? = findChildByType(GoTypes.IDENTIFIER)

    override fun getName(): String? {
        val stub = greenStub
        return if (stub != null) stub.name else nameIdentifier?.text
    }

    override fun setName(name: String): PsiElement {
        val identifier = nameIdentifier ?: throw IncorrectOperationException("$this has no name identifier")
        identifier.replace(GoElementFactory.createIdentifier(project, name))
        return this
    }

    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()

    override fun isPublic(): Boolean = GoPsiImplUtil.isExported(name)

    override val docComment: PsiComment? get() = GoDocComments.docComment(this)

    override val docText: String? get() = GoDocComments.docText(this)

    /** Narrowed per declaration kind, see [GoUseScopes]. */
    override fun getUseScope(): SearchScope = GoUseScopes.useScope(this) { super.getUseScope() }

    /** Delegates to the registered `itemPresentationProvider` (go-psi-ide) so Goto popups show signatures and locations. */
    override fun getPresentation(): ItemPresentation? = ItemPresentationProviders.getItemPresentation(this)
}
