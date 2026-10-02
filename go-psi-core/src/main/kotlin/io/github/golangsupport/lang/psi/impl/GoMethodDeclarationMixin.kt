package io.github.golangsupport.lang.psi.impl

import com.intellij.lang.ASTNode
import com.intellij.psi.stubs.IStubElementType
import io.github.golangsupport.lang.psi.GoMethodDeclarationBase
import io.github.golangsupport.lang.psi.GoReceiver
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.lang.stubs.GoMethodDeclarationStub

abstract class GoMethodDeclarationMixin :
    GoFunctionOrMethodDeclarationImpl<GoMethodDeclarationStub>, GoMethodDeclarationBase {
    constructor(node: ASTNode) : super(node)
    constructor(stub: GoMethodDeclarationStub, type: IStubElementType<*, *>) : super(stub, type)

    private fun receiverInfo(): GoPsiImplUtil.ReceiverType? =
        GoPsiImplUtil.receiverBaseType(getStubOrPsiChild(GoTypes.RECEIVER) as GoReceiver?)

    override val receiverTypeName: String?
        get() {
            val stub = greenStub
            return if (stub != null) stub.receiverTypeName else receiverInfo()?.name
        }

    override val isPointerReceiver: Boolean
        get() = greenStub?.isPointerReceiver ?: (receiverInfo()?.pointer == true)
}
