package io.github.golangsupport.lang.psi

import com.intellij.psi.PsiElement

/** Common view of [GoFunctionDeclaration] and [GoMethodDeclaration]. */
interface GoFunctionOrMethodDeclaration : GoNamedElement {
    val identifier: PsiElement?
    val typeParameters: GoTypeParameters?
    val signature: GoSignature?

    /** The body; `null` for declarations without a body (assembly-backed functions). Always loads the AST. */
    val block: GoBlock?
}
