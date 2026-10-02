package io.github.golangsupport.lang.psi.impl

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiReference
import io.github.golangsupport.lang.psi.GoReferenceProvider

/** `x` and `a.b`: the reference is supplied by the semantic module via [GoReferenceProvider]. */
abstract class GoReferenceExpressionMixin(node: ASTNode) : GoCompositeElementImpl(node) {
    override fun getReference(): PsiReference? = GoReferenceProvider.referenceOf(this)
}
