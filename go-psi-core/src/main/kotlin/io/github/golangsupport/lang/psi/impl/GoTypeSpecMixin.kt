package io.github.golangsupport.lang.psi.impl

import com.intellij.lang.ASTNode
import com.intellij.psi.stubs.IStubElementType
import io.github.golangsupport.lang.psi.GoTypeSpecBase
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.lang.stubs.GoTypeSpecStub

abstract class GoTypeSpecMixin : GoNamedElementImpl<GoTypeSpecStub>, GoTypeSpecBase {
    constructor(node: ASTNode) : super(node)
    constructor(stub: GoTypeSpecStub, type: IStubElementType<*, *>) : super(stub, type)

    override val isAlias: Boolean
        get() = greenStub?.isAlias ?: (node.findChildByType(GoTypes.ASSIGN) != null)
}
