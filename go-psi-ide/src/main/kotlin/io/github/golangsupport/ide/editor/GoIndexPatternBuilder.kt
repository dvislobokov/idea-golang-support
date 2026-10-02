package io.github.golangsupport.ide.editor

import com.intellij.lexer.Lexer
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.search.IndexPatternBuilder
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import io.github.golangsupport.lang.lexer.GoLexer
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoTokenSets
import io.github.golangsupport.lang.psi.GoTypes

/**
 * TODO/FIXME patterns in Go comments. The platform finds them from the parser definition's comment
 * tokens alone; this builder only excludes the `//`, `/*` and `*/` delimiters from the items.
 */
class GoIndexPatternBuilder : IndexPatternBuilder {
    override fun getIndexingLexer(file: PsiFile): Lexer? = if (file is GoFile) GoLexer() else null

    override fun getCommentTokenSet(file: PsiFile): TokenSet? = if (file is GoFile) GoTokenSets.COMMENTS else null

    override fun getCommentStartDelta(tokenType: IElementType): Int = if (GoTokenSets.COMMENTS.contains(tokenType)) 2 else 0

    override fun getCommentEndDelta(tokenType: IElementType): Int = if (tokenType == GoTypes.BLOCK_COMMENT) 2 else 0
}
