package io.github.golangsupport.lang.psi.impl

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.stubs.IStubElementType
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.lang.stubs.GoAnonymousFieldDefinitionStub

/** Embedded field: its name is the last identifier of the embedded type (`T` in `*pkg.T[int]`). */
abstract class GoAnonymousFieldDefinitionMixin : GoNamedElementImpl<GoAnonymousFieldDefinitionStub> {
    constructor(node: ASTNode) : super(node)
    constructor(stub: GoAnonymousFieldDefinitionStub, type: IStubElementType<*, *>) : super(stub, type)

    override fun getNameIdentifier(): PsiElement? =
        node.findChildByType(GoTypes.TYPE_REFERENCE_EXPRESSION)?.findChildByType(GoTypes.IDENTIFIER)?.psi

    /** True for `*T`. */
    val isPointer: Boolean
        get() = greenStub?.isPointer ?: (node.findChildByType(GoTypes.MUL) != null)
}
