package io.github.golangsupport.lang.psi

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import io.github.golangsupport.lang.stubs.GoFileElementType

/**
 * Hand-written element types and token sets. Token and composite types themselves live in the
 * generated [GoTypes]; the entries below are references to them, not duplicates. Only [FILE]
 * is defined here.
 */
object GoElementTypes {
    @JvmField
    val FILE: GoFileElementType = GoFileElementType.INSTANCE

    @JvmField
    val IDENTIFIER: IElementType = GoTypes.IDENTIFIER

    @JvmField
    val LINE_COMMENT: IElementType = GoTypes.LINE_COMMENT

    @JvmField
    val BLOCK_COMMENT: IElementType = GoTypes.BLOCK_COMMENT

    @JvmField
    val WHITE_SPACE: IElementType = TokenType.WHITE_SPACE

    @JvmField
    val BAD_CHARACTER: IElementType = TokenType.BAD_CHARACTER

    @JvmField
    val WHITE_SPACES: TokenSet = GoTokenSets.WHITESPACES

    @JvmField
    val COMMENTS: TokenSet = GoTokenSets.COMMENTS
}
