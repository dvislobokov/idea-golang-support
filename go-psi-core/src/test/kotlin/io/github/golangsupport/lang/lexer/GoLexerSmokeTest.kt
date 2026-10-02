package io.github.golangsupport.lang.lexer

import io.github.golangsupport.GoLexerTestCase

class GoLexerSmokeTest : GoLexerTestCase() {

    fun testIdentifiersAndLineComment() {
        doTest(
            "foo bar // c",
            """
            IDENTIFIER ('foo')
            WHITE_SPACE (' ')
            IDENTIFIER ('bar')
            WHITE_SPACE (' ')
            LINE_COMMENT ('// c')
            """.trimIndent(),
        )
    }

    fun testBlockCommentAndBadCharacter() {
        doTest(
            "a/* b */+#",
            """
            IDENTIFIER ('a')
            BLOCK_COMMENT ('/* b */')
            + ('+')
            BAD_CHARACTER ('#')
            """.trimIndent(),
        )
    }
}
