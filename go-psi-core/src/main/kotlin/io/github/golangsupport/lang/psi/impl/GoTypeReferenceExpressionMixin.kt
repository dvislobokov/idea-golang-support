package io.github.golangsupport.lang.psi.impl

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiReference
import com.intellij.psi.stubs.IStubElementType
import io.github.golangsupport.lang.psi.GoReferenceProvider
import io.github.golangsupport.lang.stubs.GoTypeReferenceExpressionStub

/** `T` / `pkg.T` in type position; stub-backed name and qualifier, reference from the semantic module. */
abstract class GoTypeReferenceExpressionMixin : GoStubbedElementImpl<GoTypeReferenceExpressionStub> {
    constructor(node: ASTNode) : super(node)
    constructor(stub: GoTypeReferenceExpressionStub, type: IStubElementType<*, *>) : super(stub, type)

    /** The referenced type name (last identifier), from the stub when available. */
    val referenceName: String
        get() = greenStub?.name ?: node.lastChildNode?.text ?: ""

    /** The package qualifier (`pkg` in `pkg.T`), from the stub when available. */
    val qualifierName: String?
        get() {
            val stub = greenStub
            if (stub != null) return stub.qualifier
            return node.firstChildNode?.takeIf { it.elementType === io.github.golangsupport.lang.psi.GoTypes.REFERENCE_EXPRESSION }?.text
        }

    override fun getReference(): PsiReference? = GoReferenceProvider.referenceOf(this)
}
