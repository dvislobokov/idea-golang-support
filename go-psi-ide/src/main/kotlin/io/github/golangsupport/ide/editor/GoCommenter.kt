package io.github.golangsupport.ide.editor

import com.intellij.lang.CodeDocumentationAwareCommenter
import com.intellij.psi.PsiComment
import com.intellij.psi.tree.IElementType
import io.github.golangsupport.lang.psi.GoTypes

/** `//` line comments and `/* */` block comments. Go doc comments are ordinary comments, so there is no doc comment syntax. */
class GoCommenter : CodeDocumentationAwareCommenter {
    override fun getLineCommentPrefix(): String = "//"

    override fun getBlockCommentPrefix(): String = "/*"

    override fun getBlockCommentSuffix(): String = "*/"

    override fun getCommentedBlockCommentPrefix(): String? = null

    override fun getCommentedBlockCommentSuffix(): String? = null

    override fun getLineCommentTokenType(): IElementType = GoTypes.LINE_COMMENT

    override fun getBlockCommentTokenType(): IElementType = GoTypes.BLOCK_COMMENT

    override fun getDocumentationCommentTokenType(): IElementType? = null

    override fun getDocumentationCommentPrefix(): String? = null

    override fun getDocumentationCommentLinePrefix(): String? = null

    override fun getDocumentationCommentSuffix(): String? = null

    override fun isDocumentationComment(element: PsiComment?): Boolean = false
}
