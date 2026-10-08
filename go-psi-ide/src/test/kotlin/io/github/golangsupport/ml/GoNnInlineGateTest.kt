package io.github.golangsupport.ml

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import io.github.golangsupport.ide.GoIdeTestBase

/**
 * The hard gate of the grey text: nothing inside a string, raw string or rune literal or in a comment, code again right after the
 * closing quote — decided from the PSI token at the caret, or from the lexer while the document is not committed.
 */
class GoNnInlineGateTest : GoIdeTestBase() {
    private val file = """
        package main

        import "fmt"

        /* a block
           comment */
        func main() {
            s := "hello"
            r := `raw
        text`
            c := 'x'
            fmt.Println(s, r, c) // trailing
            //
        }
    """.trimIndent() + "\n"

    private fun gate(marker: String, at: Int = 0): Boolean {
        val offset = file.indexOf(marker) + at
        check(offset >= at) { "no $marker" }
        myFixture.configureByText("main.go", file)
        return inGate(offset)
    }

    private fun inGate(offset: Int): Boolean = GoNnInline.inStringOrComment(myFixture.file, myFixture.editor.document, offset)

    fun testInsideAStringLiteral() {
        assertTrue(gate("hello", 2))                 // `"he⟨⟩llo"`
        assertTrue(gate("\"hello\"", 1))             // `"⟨⟩hello"`: right after the opening quote
        assertTrue(gate("\"hello\"", 6))             // `"hello⟨⟩"`: before the closing quote
        assertTrue(gate("import \"fmt\"", 9))        // the import path is a string too
    }

    fun testRightAfterTheClosingQuoteIsCode() {
        assertFalse(gate("\"hello\"", 7))            // `"hello"⟨⟩`
        assertFalse(gate("'x'", 3))
        assertFalse(gate("text`", 5))
        assertFalse(gate("s := ", 5))                // `s := ⟨⟩"hello"`: before the opening quote
        assertFalse(gate("fmt.Println", 4))
    }

    fun testInsideRawStringAndRune() {
        assertTrue(gate("raw", 1))
        assertTrue(gate("text`", 2))                 // the second line of the raw string
        assertTrue(gate("'x'", 1))
        assertTrue(gate("'x'", 2))                   // `'x⟨⟩'`
    }

    fun testInsideComments() {
        assertTrue(gate("// trailing", 5))
        assertTrue(gate("// trailing", 11))          // at the very end of the line comment
        assertTrue(gate("//\n", 2))                  // `// ⟨⟩` on an empty line comment: still suppressed
        assertTrue(gate("a block", 3))
        assertTrue(gate("comment */", 2))
        assertTrue(gate("comment */", 9))            // `*⟨⟩/`
        assertFalse(gate("comment */", 10))          // right after `*/`
        assertFalse(gate("func main", 0))            // the line after the block comment
        assertFalse(gate("}\n", 0))                  // the line after the empty line comment
    }

    fun testStartOfFile() {
        myFixture.configureByText("main.go", file)
        assertFalse(inGate(0))
    }

    fun testUncommittedDocumentUsesTheLexer() {
        myFixture.configureByText("main.go", file)
        val document = myFixture.editor.document
        val offset = file.indexOf("fmt.Println")
        // the user typed the start of a string: the document is ahead of the PSI
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(offset, "x := \"ab") }
        assertFalse(PsiDocumentManager.getInstance(project).isCommitted(document))
        assertTrue(inGate(offset + 8))               // `x := "ab⟨⟩`: an unterminated string
        assertFalse(inGate(offset))                  // before what was typed
        // the pure lexer path on the text alone
        val text = document.immutableCharSequence
        assertTrue(GoNnInline.inStringOrComment(text, text.indexOf("hello") + 1))
        assertFalse(GoNnInline.inStringOrComment(text, text.indexOf("\"hello\"") + 7))
        assertTrue(GoNnInline.inStringOrComment(text, text.indexOf("a block") + 2))
        assertTrue(GoNnInline.inStringOrComment(text, text.indexOf("//\n") + 2))
        assertFalse(GoNnInline.inStringOrComment(text, 0))
        assertTrue(GoNnInline.inStringOrComment("s := \"a\\\"", 9))     // an escaped quote does not close the string
        assertFalse(GoNnInline.inStringOrComment("s := \"a\\\\\"", 10))  // an escaped backslash before the closing quote does
        assertTrue(GoNnInline.inStringOrComment("/* open", 7))
    }

    fun testLiteralKindTellsStringsFromComments() {
        // strings are allowed by default (log and error messages), comments are not: the kind decides which setting applies
        assertEquals(GoNnInline.Literal.STRING, GoNnInline.literalAt("log.Printf(\"failed to ", 19))
        assertEquals(GoNnInline.Literal.STRING, GoNnInline.literalAt("r := `raw", 9))
        assertEquals(GoNnInline.Literal.COMMENT, GoNnInline.literalAt("// a note", 9))
        assertEquals(GoNnInline.Literal.COMMENT, GoNnInline.literalAt("/* open", 7))
        assertNull(GoNnInline.literalAt("s := \"a\"", 9))
        assertTrue(GoMlSettings.getInstance().inlineInStrings)
        assertFalse(GoMlSettings.getInstance().inlineInComments)
    }
}
