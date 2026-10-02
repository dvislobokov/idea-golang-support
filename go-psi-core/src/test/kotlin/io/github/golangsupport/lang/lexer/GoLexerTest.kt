package io.github.golangsupport.lang.lexer

import io.github.golangsupport.GoLexerTestCase

/**
 * Golden lexer tests: `testData/lexer/<Name>.go` lexed into `<Name>.txt`. The platform loader
 * trims the file and converts line separators, so the last token is never followed by a newline
 * (EOF without newline) and `\r\n` is covered by the inline tests below and by [GoLexerAsiTest].
 */
class GoLexerTest : GoLexerTestCase() {

    fun testSemicolons() = doGoldenTest()

    fun testNumbers() = doGoldenTest()

    fun testStrings() = doGoldenTest()

    fun testComments() = doGoldenTest()

    fun testOperators() = doGoldenTest()

    fun testUnicode() = doGoldenTest()

    fun testCrLf() {
        // go/scanner skips '\r' as whitespace and inserts the semicolon at the '\n'.
        assertTokens(
            "x\r\ny := 1\r\n\r\n",
            """
            IDENTIFIER ('x')
            WHITE_SPACE ('\r')
            SEMICOLON_SYNTHETIC ('\n')
            IDENTIFIER ('y')
            WHITE_SPACE (' ')
            := (':=')
            WHITE_SPACE (' ')
            INT ('1')
            WHITE_SPACE ('\r')
            SEMICOLON_SYNTHETIC ('\n')
            WHITE_SPACE ('\r\n')
            """.trimIndent(),
        )
    }

    fun testLineCommentKeepsCarriageReturn() {
        // go/scanner ends a line comment at '\n' only.
        assertTokens(
            "x // c\r\n",
            """
            IDENTIFIER ('x')
            WHITE_SPACE (' ')
            LINE_COMMENT ('// c\r')
            SEMICOLON_SYNTHETIC ('\n')
            """.trimIndent(),
        )
    }

    fun testByteOrderMark() {
        // Ignored (whitespace) only as the very first character, like go/scanner.
        assertTokens(
            BOM + "package p" + BOM,
            """
            WHITE_SPACE ('<BOM>')
            package ('package')
            WHITE_SPACE (' ')
            IDENTIFIER ('p')
            BAD_CHARACTER ('<BOM>')
            """.trimIndent(),
        )
    }

    fun testFormFeedIsBadCharacter() {
        // go/scanner does not treat '\f' as whitespace; ILLEGAL keeps the semicolon state.
        assertTokens(
            "x" + FORM_FEED + "\n",
            """
            IDENTIFIER ('x')
            BAD_CHARACTER ('\f')
            SEMICOLON_SYNTHETIC ('\n')
            """.trimIndent(),
        )
    }

    /**
     * Compares with the golden and checks that the lexer restarts correctly from every token
     * (incremental highlighting restarts the lexer at stored states, including MAYBE_SEMICOLON).
     */
    private fun doGoldenTest() {
        doFileTest("go")
        val text = loadTestDataFile(".go")
        checkCorrectRestart(text)
        checkCorrectRestartUsingPosition(text)
    }

    /**
     * Like `doTest`, but escapes CR, LF, form feed and BOM in token texts; the platform printer
     * prints CR and form feed raw, and line-separator normalisation then hides them.
     */
    private fun assertTokens(text: String, expected: String) {
        val lexer = createLexer()
        lexer.start(text)
        val actual = buildString {
            while (lexer.tokenType != null) {
                val token = text.substring(lexer.tokenStart, lexer.tokenEnd)
                    .replace("\r", "\\r")
                    .replace("\n", "\\n")
                    .replace(FORM_FEED, "\\f")
                    .replace(BOM, "<BOM>")
                append(lexer.tokenType).append(" ('").append(token).append("')\n")
                lexer.advance()
            }
        }
        assertEquals(expected, actual.trimEnd())
    }

    private companion object {
        val BOM = Char(0xFEFF).toString()
        val FORM_FEED = Char(0x0C).toString()
    }
}
