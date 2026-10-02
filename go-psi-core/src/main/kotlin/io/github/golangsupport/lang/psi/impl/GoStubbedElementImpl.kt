package io.github.golangsupport.lang.psi.impl

import com.intellij.extapi.psi.StubBasedPsiElementBase
import com.intellij.lang.ASTNode
import com.intellij.psi.stubs.IStubElementType
import com.intellij.psi.stubs.StubElement
import io.github.golangsupport.lang.psi.GoCompositeElement

/**
 * Base class of the generated PSI for stubbed rules without a name. The same class backs nodes
 * that are never stubbed (for example types inside function bodies); those are AST-only.
 */
abstract class GoStubbedElementImpl<S : StubElement<*>> : StubBasedPsiElementBase<S>, GoCompositeElement {
    constructor(node: ASTNode) : super(node)
    constructor(stub: S, type: IStubElementType<*, *>) : super(stub, type)

    override fun toString(): String = "${javaClass.simpleName}($elementTypeImpl)"
}
