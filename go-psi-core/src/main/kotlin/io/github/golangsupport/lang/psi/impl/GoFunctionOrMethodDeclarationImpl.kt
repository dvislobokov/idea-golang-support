package io.github.golangsupport.lang.psi.impl

import com.intellij.lang.ASTNode
import com.intellij.psi.stubs.IStubElementType
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.stubs.GoFunctionOrMethodDeclarationStub

/** Mixin of `FunctionDeclaration`; [GoMethodDeclarationMixin] extends it for methods. */
abstract class GoFunctionOrMethodDeclarationImpl<S : GoFunctionOrMethodDeclarationStub<*>> :
    GoNamedElementImpl<S>, GoFunctionOrMethodDeclaration {
    constructor(node: ASTNode) : super(node)
    constructor(stub: S, type: IStubElementType<*, *>) : super(stub, type)
}
