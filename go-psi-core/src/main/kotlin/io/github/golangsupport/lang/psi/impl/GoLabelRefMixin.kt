package io.github.golangsupport.lang.psi.impl

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiReference
import io.github.golangsupport.lang.psi.GoReferenceProvider

/** A label use in `break L`, `continue L`, `goto L`. */
abstract class GoLabelRefMixin(node: ASTNode) : GoCompositeElementImpl(node) {
    override fun getReference(): PsiReference? = GoReferenceProvider.referenceOf(this)
}
