package io.github.golangsupport.lang.psi.impl

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import io.github.golangsupport.lang.psi.GoCompositeElement

/** Base class of the generated PSI for rules without stubs (Grammar-Kit global `extends`). */
open class GoCompositeElementImpl(node: ASTNode) : ASTWrapperPsiElement(node), GoCompositeElement {
    override fun toString(): String = "${javaClass.simpleName}(${node.elementType})"
}
