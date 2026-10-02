package io.github.golangsupport.lang.psi.impl

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.search.SearchScope
import com.intellij.util.IncorrectOperationException
import io.github.golangsupport.lang.psi.GoElementFactory
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoTypes

/** Labels live in function bodies only and are therefore never stubbed. */
abstract class GoLabelDefinitionMixin(node: ASTNode) : GoCompositeElementImpl(node), GoNamedElement {
    override fun getNameIdentifier(): PsiElement? = findChildByType(GoTypes.IDENTIFIER)

    override fun getName(): String? = nameIdentifier?.text

    override fun setName(name: String): PsiElement {
        val identifier = nameIdentifier ?: throw IncorrectOperationException("$this has no name identifier")
        identifier.replace(GoElementFactory.createIdentifier(project, name))
        return this
    }

    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()

    override fun isPublic(): Boolean = false

    override val docComment: PsiComment? get() = null

    override val docText: String? get() = null

    /** The enclosing function, see [GoUseScopes]. */
    override fun getUseScope(): SearchScope = GoUseScopes.useScope(this) { super.getUseScope() }
}
