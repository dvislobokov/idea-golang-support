package io.github.golangsupport.lang.lexer

import io.github.golangsupport.lang.psi.GoTypes
import junit.framework.TestCase

/**
 * Automatic semicolon insertion (https://go.dev/ref/spec#Semicolons): a newline after an
 * identifier, a basic literal, `break continue fallthrough return`, `++ -- ) ] }` becomes
 * SEMICOLON_SYNTHETIC whose text is the newline; no semicolon is inserted anywhere else and none
 * at EOF.
 */
class GoLexerAsiTest : TestCase() {

    private val inserted = listOf(
        "x\n",
        "_\n",
        "42\n",
        "0x1F\n",
        "4.2\n",
        "1e3\n",
        "1i\n",
        "'a'\n",
        "\"s\"\n",
        "`raw`\n",
        "`multi\nline`\n",
        "break\n",
        "continue\n",
        "fallthrough\n",
        "return\n",
        "x++\n",
        "x--\n",
        "f()\n",
        "a[i]\n",
        "T{}\n",
        "x \t \n",
        "x\r\n",
        "x // line comment\n",
        "x /* block */\n",
        "x /* multi\nline */\n",
        "x /* a */ /* b */ // c\n",
        "break L\n",
        "x # \n",
    )

    private val notInserted = listOf(
        "x",
        "x // comment at EOF",
        "f(a,\n",
        "x +\n",
        "x =\n",
        "x :=\n",
        "x.\n",
        "{\n",
        "(\n",
        "[\n",
        "x;\n",
        "func\n",
        "if\n",
        "else\n",
        "chan\n",
        "goto\n",
        "default\n",
        "case\n",
        "f(x...\n",
        "~\n",
        "<-\n",
        "x /* multi\nline */ y",
        "// only a comment\n",
        "\n\n",
    )

    fun testSemicolonInserted() {
        for (source in inserted) {
            val semicolons = synthetic(source)
            assertEquals("expected one SEMICOLON_SYNTHETIC in ${quote(source)}", 1, semicolons.size)
            assertEquals("SEMICOLON_SYNTHETIC text in ${quote(source)}", "\n", semicolons.single().second)
            assertEquals("SEMICOLON_SYNTHETIC offset in ${quote(source)}", source.lastIndexOf('\n'), semicolons.single().first)
        }
    }

    fun testSemicolonNotInserted() {
        for (source in notInserted) {
            assertEquals("expected no SEMICOLON_SYNTHETIC in ${quote(source)}", emptyList<Pair<Int, String>>(), synthetic(source))
        }
    }

    fun testOnePerLine() {
        // Blank lines do not add semicolons; a newline inside parentheses after '(' does not either.
        assertEquals(listOf(1, 3, 5), synthetic("a\nb\nc\n\n").map { it.first })
        assertEquals(listOf(4), synthetic("f(\n)\n").map { it.first })
    }

    /** (offset, text) of every SEMICOLON_SYNTHETIC token. */
    private fun synthetic(source: String): List<Pair<Int, String>> {
        val lexer = GoLexer()
        lexer.start(source)
        val result = mutableListOf<Pair<Int, String>>()
        while (lexer.tokenType != null) {
            if (lexer.tokenType == GoTypes.SEMICOLON_SYNTHETIC) {
                result += lexer.tokenStart to source.substring(lexer.tokenStart, lexer.tokenEnd)
            }
            lexer.advance()
        }
        return result
    }

    private fun quote(s: String) = "\"" + s.replace("\r", "\\r").replace("\n", "\\n") + "\""
}
