package io.github.golangsupport.lang.psi.impl

import com.intellij.lang.ASTFactory
import com.intellij.psi.impl.source.tree.LeafElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.tree.IElementType
import io.github.golangsupport.lang.psi.GoTokenSets

/**
 * `lang.ast.factory` for Go: comments become [GoCommentImpl] (a `PsiComment` that is not an
 * injection host), every other leaf a plain [LeafPsiElement] as the default factory would create
 * (without its parser-definition lookup per leaf). Composites keep the default (null).
 */
class GoASTFactory : ASTFactory() {
    override fun createLeaf(type: IElementType, text: CharSequence): LeafElement =
        if (GoTokenSets.COMMENTS.contains(type)) GoCommentImpl(type, text) else LeafPsiElement(type, text)
}
