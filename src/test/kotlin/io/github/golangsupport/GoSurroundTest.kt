package io.github.golangsupport

import io.github.golangsupport.lang.GoSurrounders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Surround With by the text: lines into a block, a piece of a line into parentheses. */
class GoSurroundTest {
    private val code = "func f() {\n\tx := 1\n\ty := 2\n\treturn x + y\n}\n"
    private fun kind(title: String) = (GoSurrounders.STATEMENTS + GoSurrounders.EXPRESSIONS).first { it.title == title }

    @Test
    fun linesGoIntoAnIfWithTheCaretOnTheCondition() {
        val start = code.indexOf("x := 1")
        val end = code.indexOf("y := 2") + "y := 2".length
        val result = GoSurrounders.surround(code, start, end, kind("if"))!!
        assertEquals("\tif  {\n\t\tx := 1\n\t\ty := 2\n\t}", result.text)
        assertEquals(code.indexOf("\tx := 1"), result.range.startOffset)
        assertEquals(end, result.range.endOffset)
        // the caret between `if` and `{`
        assertEquals(result.range.startOffset + "\tif ".length, result.caret)
        val applied = code.replaceRange(result.range.startOffset, result.range.endOffset, result.text)
        assertEquals("func f() {\n\tif  {\n\t\tx := 1\n\t\ty := 2\n\t}\n\treturn x + y\n}\n", applied)
    }

    @Test
    fun aSelectionOfWholeLinesDoesNotTakeTheLineItEndsAt() {
        val start = code.indexOf("\tx := 1")
        val end = code.indexOf("\treturn")
        val result = GoSurrounders.surround(code, start, end, kind("for { ... }"))!!
        assertEquals("\tfor {\n\t\tx := 1\n\t\ty := 2\n\t}", result.text)
        assertEquals(result.range.startOffset + result.text.length, result.caret)
    }

    @Test
    fun elseAndFunctionLiterals() {
        val start = code.indexOf("x := 1")
        val ifElse = GoSurrounders.surround(code, start, start + 1, kind("if ... else"))!!
        assertEquals("\tif  {\n\t\tx := 1\n\t} else {\n\t\t\n\t}", ifElse.text)
        assertEquals(ifElse.range.startOffset + "\tif ".length, ifElse.caret)
        val goFunc = GoSurrounders.surround(code, start, start + 1, kind("go func() { ... }()"))!!
        assertEquals("\tgo func() {\n\t\tx := 1\n\t}()", goFunc.text)
        // blank lines inside stay blank, without a tab
        val blank = GoSurrounders.surround("a\n\nb\n", 0, 4, kind("{ ... }"))!!
        assertEquals("{\n\ta\n\n\tb\n}", blank.text)
    }

    @Test
    fun aPieceOfALineGoesIntoParentheses() {
        val start = code.indexOf("x + y")
        val result = GoSurrounders.surround(code, start, start + 5, kind("!(expr)"))!!
        assertEquals("!(x + y)", result.text)
        assertEquals(start + 8, result.caret)
        assertNull("across lines the expression kinds do not apply", GoSurrounders.surround(code, code.indexOf("x := 1"), code.indexOf("return"), kind("(expr)")))
        assertNull("nothing to surround", GoSurrounders.surround("\n\n", 0, 1, kind("if")))
    }
}
