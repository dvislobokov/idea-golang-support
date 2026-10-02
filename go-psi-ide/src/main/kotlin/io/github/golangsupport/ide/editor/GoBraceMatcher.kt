package io.github.golangsupport.ide.editor

import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import io.github.golangsupport.lang.psi.GoTokenSets
import io.github.golangsupport.lang.psi.GoTypes

/** Matches `()`, `[]` and `{}`; braces are structural (used by code blocks and Enter handling). */
class GoBraceMatcher : PairedBraceMatcher {
    override fun getPairs(): Array<BracePair> = PAIRS

    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean =
        contextType == null || ALLOWED_BEFORE.contains(contextType)

    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int = openingBraceOffset

    private companion object {
        val PAIRS = arrayOf(
            BracePair(GoTypes.LPAREN, GoTypes.RPAREN, false),
            BracePair(GoTypes.LBRACK, GoTypes.RBRACK, false),
            BracePair(GoTypes.LBRACE, GoTypes.RBRACE, true),
        )

        /** Auto-closing a typed bracket only makes sense before whitespace, comments, separators and closers. */
        val ALLOWED_BEFORE: TokenSet = TokenSet.orSet(
            GoTokenSets.COMMENTS,
            GoTokenSets.SEMICOLONS,
            TokenSet.create(TokenType.WHITE_SPACE, GoTypes.RPAREN, GoTypes.RBRACK, GoTypes.RBRACE, GoTypes.COMMA, GoTypes.COLON),
        )
    }
}
