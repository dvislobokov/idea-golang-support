package io.github.golangsupport.lang.psi.impl

import com.intellij.lang.ASTFactory
import com.intellij.psi.impl.source.tree.LeafElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.tree.IElementType
import io.github.golangsupport.lang.psi.GoTokenSets

/**
 * `lang.ast.factory` for Go: comments become [GoCommentImpl] (a `PsiComment` that is not an
 * injection host; a `//go:generate` line is [GoGenerateCommentImpl], which is one), every other leaf a
 * plain [LeafPsiElement] as the default factory would create (without its parser-definition lookup
 * per leaf). Composites keep the default (null).
 */
class GoASTFactory : ASTFactory() {
    override fun createLeaf(type: IElementType, text: CharSequence): LeafElement = when {
        !GoTokenSets.COMMENTS.contains(type) -> LeafPsiElement(type, text)
        GoGenerateCommentImpl.isGenerate(type, text) -> GoGenerateCommentImpl(type, text)
        else -> GoCommentImpl(type, text)
    }
}
