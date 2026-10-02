package io.github.golangsupport.ide.highlighting

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import io.github.golangsupport.lang.lexer.GoLexer
import io.github.golangsupport.lang.psi.GoTokenSets
import io.github.golangsupport.lang.psi.GoTypes

/** Lexer-based highlighting. SEMICOLON_SYNTHETIC (a line break) and whitespace get no colour. */
class GoSyntaxHighlighter : SyntaxHighlighterBase() {

    override fun getHighlightingLexer(): Lexer = GoLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> =
        pack(attributes[tokenType])

    private companion object {
        val attributes: Map<IElementType, TextAttributesKey> = buildMap {
            GoTokenSets.KEYWORDS.types.forEach { put(it, GoHighlightingColors.KEYWORD) }
            GoTokenSets.OPERATORS.types.forEach { put(it, GoHighlightingColors.OPERATOR) }
            GoTokenSets.NUMBERS.types.forEach { put(it, GoHighlightingColors.NUMBER) }
            GoTokenSets.STRING_LITERALS.types.forEach { put(it, GoHighlightingColors.STRING) }
            put(GoTypes.CHAR, GoHighlightingColors.RUNE)
            put(GoTypes.IDENTIFIER, GoHighlightingColors.IDENTIFIER)
            put(GoTypes.LINE_COMMENT, GoHighlightingColors.LINE_COMMENT)
            put(GoTypes.BLOCK_COMMENT, GoHighlightingColors.BLOCK_COMMENT)
            put(GoTypes.LBRACE, GoHighlightingColors.BRACES)
            put(GoTypes.RBRACE, GoHighlightingColors.BRACES)
            put(GoTypes.LBRACK, GoHighlightingColors.BRACKETS)
            put(GoTypes.RBRACK, GoHighlightingColors.BRACKETS)
            put(GoTypes.LPAREN, GoHighlightingColors.PARENTHESES)
            put(GoTypes.RPAREN, GoHighlightingColors.PARENTHESES)
            put(GoTypes.COMMA, GoHighlightingColors.COMMA)
            put(GoTypes.SEMICOLON, GoHighlightingColors.SEMICOLON)
            put(GoTypes.PERIOD, GoHighlightingColors.DOT)
            put(TokenType.BAD_CHARACTER, GoHighlightingColors.BAD_CHARACTER)
        }
    }
}
