package io.github.golangsupport.lang

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import io.github.golangsupport.lang.lexer.GoLexer
import io.github.golangsupport.lang.psi.GoTokenSets

/**
 * The tokens of a text by the lexer of go-psi, for the helpers that work on text the PSI has not seen yet: what is being typed (the
 * indent, the import a completion item adds), an expression typed for the debugger, files outside the project. The lexer inserts the
 * semicolons of Go at line ends ([io.github.golangsupport.lang.psi.GoTypes.SEMICOLON_SYNTHETIC], the text of the line break).
 */
object GoTokens {
    class Token(val type: IElementType, val start: Int, val end: Int)

    /** Every token of [text] from [from] to [to], white space and comments included. */
    fun all(text: CharSequence, from: Int = 0, to: Int = text.length): Sequence<Token> = sequence {
        val lexer = GoLexer()
        lexer.start(text, from, to, 0)
        while (true) {
            val type = lexer.tokenType ?: break
            yield(Token(type, lexer.tokenStart, lexer.tokenEnd))
            lexer.advance()
        }
    }

    /** The tokens of code: no white space, no comments; the inserted semicolons stay. */
    fun code(text: CharSequence, from: Int = 0, to: Int = text.length): Sequence<Token> = all(text, from, to).filter { isCode(it.type) }

    fun isCode(type: IElementType): Boolean = type != TokenType.WHITE_SPACE && type !in GoTokenSets.COMMENTS
}
