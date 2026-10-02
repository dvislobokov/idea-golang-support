package io.github.golangsupport.ide.highlighting

import com.intellij.psi.TokenType
import io.github.golangsupport.lang.psi.GoTypes
import junit.framework.TestCase

class GoSyntaxHighlighterTest : TestCase() {

    private val highlighter = GoSyntaxHighlighter()

    fun testTokenColours() {
        assertSame(GoHighlightingColors.KEYWORD, highlighter.getTokenHighlights(GoTypes.FUNC).single())
        assertSame(GoHighlightingColors.IDENTIFIER, highlighter.getTokenHighlights(GoTypes.IDENTIFIER).single())
        assertSame(GoHighlightingColors.STRING, highlighter.getTokenHighlights(GoTypes.RAW_STRING).single())
        assertSame(GoHighlightingColors.RUNE, highlighter.getTokenHighlights(GoTypes.CHAR).single())
        assertSame(GoHighlightingColors.NUMBER, highlighter.getTokenHighlights(GoTypes.IMAG).single())
        assertSame(GoHighlightingColors.BRACES, highlighter.getTokenHighlights(GoTypes.LBRACE).single())
        assertSame(GoHighlightingColors.OPERATOR, highlighter.getTokenHighlights(GoTypes.AND_NOT_ASSIGN).single())
        assertSame(GoHighlightingColors.SEMICOLON, highlighter.getTokenHighlights(GoTypes.SEMICOLON).single())
        assertSame(GoHighlightingColors.BAD_CHARACTER, highlighter.getTokenHighlights(TokenType.BAD_CHARACTER).single())
        assertEquals(0, highlighter.getTokenHighlights(GoTypes.SEMICOLON_SYNTHETIC).size)
        assertEquals(0, highlighter.getTokenHighlights(TokenType.WHITE_SPACE).size)
    }

    /** Every token of the colour settings demo, except whitespace and inserted semicolons, is coloured. */
    fun testDemoTextFullyHighlighted() {
        val text = GoColorSettingsPage().demoText
        val lexer = highlighter.highlightingLexer
        lexer.start(text)
        val uncoloured = mutableListOf<String>()
        while (lexer.tokenType != null) {
            val type = lexer.tokenType!!
            if (type != TokenType.WHITE_SPACE && type != GoTypes.SEMICOLON_SYNTHETIC &&
                highlighter.getTokenHighlights(type).isEmpty()
            ) {
                uncoloured += "$type at ${lexer.tokenStart}"
            }
            lexer.advance()
        }
        assertEquals(emptyList<String>(), uncoloured)
    }
}
