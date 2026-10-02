package io.github.golangsupport.ide.formatter

import com.intellij.application.options.CodeStyle
import com.intellij.formatting.service.CoreFormattingService
import com.intellij.formatting.service.FormattingServiceUtil
import com.intellij.lang.ASTNode
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.TokenType
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.impl.source.tree.TreeUtil
import io.github.golangsupport.ide.GoIdeTestBase
import io.github.golangsupport.ide.formatter.printer.GoLayout
import io.github.golangsupport.lang.GoFileType
import io.github.golangsupport.lang.lexer.GoLexer
import io.github.golangsupport.lang.psi.GoTypes
import java.io.File

/**
 * The layout cache, the partial layout of files with syntax errors, the file edges (leading and
 * trailing whitespace), range reformat and reformat-on-save.
 */
class GoFormatterLayoutTest : GoIdeTestBase() {

    override val testDataSubdir = "formatter"

    private val clean = lines(
        "package p",
        "",
        "import \"fmt\"",
        "",
        "func f(a, b int) int {",
        "\tif a > b {",
        "\t\treturn a",
        "\t}",
        "\tfmt.Println(a)",
        "\treturn b",
        "}",
    )

    // --- layout cache -----------------------------------------------------------------------------

    fun testRepeatedRequestsReuseTheLayout() {
        myFixture.configureByText("a.go", clean)
        val file = myFixture.file
        reformat(file)
        val computed = GoLayout.computationCount()
        // the file did not change (gofmt-clean): Reformat Code, range reformat and Auto-Indent Lines reuse it
        reformat(file)
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformatText(file, 30, 60)
            CodeStyleManager.getInstance(project).adjustLineIndent(file, TextRange(0, file.textLength))
        }
        assertEquals("layout recomputed for an unchanged file", computed, GoLayout.computationCount())
        assertEquals(clean, myFixture.editor.document.text)
    }

    fun testLayoutCacheIsInvalidatedByEdits() {
        myFixture.configureByText("a.go", clean)
        val file = myFixture.file
        reformat(file)
        val computed = GoLayout.computationCount()
        WriteCommandAction.runWriteCommandAction(project) {
            val document = myFixture.editor.document
            document.insertString(document.text.indexOf("return b"), "  ")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        reformat(file)
        assertEquals(computed + 1, GoLayout.computationCount())
        assertEquals(clean, myFixture.editor.document.text)
        // and by a code style change
        CodeStyle.getSettings(file).modificationTracker.incModificationCount()
        reformat(file)
        assertEquals(computed + 2, GoLayout.computationCount())
    }

    // --- partial layout (syntax errors) -----------------------------------------------------------

    /**
     * One broken function among clean declarations: every clean declaration is formatted exactly
     * like gofmt formats the file without the broken one (`partialLayout.clean.go`, gofmt output).
     */
    fun testCleanDeclarationsAroundABrokenOneAreGofmtExact() {
        val input = read("partial/partialLayout.go")
        val gofmtClean = read("partial/partialLayout.clean.go")
        val layout = GoLayout.compute(psi(input).node)!!
        assertFalse(layout.isComplete)

        val result = reformatText(input)
        for (header in listOf("import (", "type T struct", "func clean1(", "func clean2(", "// clean3 prints.")) {
            assertEquals("declaration starting with '$header'", declaration(gofmtClean, header), declaration(result, header))
        }
        // imports are sorted, otherwise the token order is unchanged
        assertEquals("token multiset changed", tokens(input).sorted(), tokens(result).sorted())
        assertEquals("token stream changed", tokens(input).dropWhile { it != "type:type" }, tokens(result).dropWhile { it != "type:type" })
        // the broken function keeps its tokens and gets structural indentation
        assertTrue(result, result.contains("func broken(a int) {\n\ty := a\n\tx := 1 +\n}\n"))
        // formatting again changes nothing
        assertEquals(result, reformatText(result))
    }

    fun testPartialLayoutKeepsDeclarationRunsTogether() {
        // the two clean functions after the broken one form one run: their blank-line separation is gofmt's
        val input = lines("package p", "", "func broken() {", "x := (", "}", "func a() {", "}", "", "", "", "func b() {", "}")
        val result = reformatText(input)
        assertTrue(result, result.endsWith(lines("func a() {", "}", "", "func b() {", "}")))
        assertEquals(tokens(input), tokens(result))
    }

    // --- file edges -------------------------------------------------------------------------------

    fun testExactlyOneLineFeedAtEndOfFile() {
        assertEquals("package p\n\nfunc f() {}\n", reformatText("package p\n\nfunc f() {}\n\n\n  \n"))
        assertEquals("package p\n\nvar x = 1\n", reformatText("package p\n\nvar x = 1   \n\t\n"))
        assertEquals("package p\n\n// last\n", reformatText("package p\n\n// last\n\n"))
    }

    fun testLineFeedAddedAtEndOfFile() {
        // after `}` the line feed is an inserted semicolon; after a comment on its own line it is whitespace
        for (input in listOf("package p\n\nfunc f() {}", "package p\n\n// last", "package p\n\nvar x = 1 // c")) {
            val file = psi(input)
            WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformat(file) }
            assertEquals(input + "\n", file.text)
            assertEquals("tree differs from what the lexer produces for $input", tokens(file.text, withTrivia = true), leaves(file.node))
        }
    }

    fun testNoLeadingWhitespace() {
        assertEquals("package p\n", reformatText("\n\n  package p\n"))
        assertEquals("// Package p.\npackage p\n", reformatText("\n// Package p.\npackage p\n"))
    }

    fun testEditorReformatNormalizesFileEnd() {
        myFixture.configureByText("a.go", "package p\n\nfunc f( ) {}\n\n\n")
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
        assertEquals("package p\n\nfunc f() {}\n", myFixture.editor.document.text)
    }

    fun testRangeReformatAwayFromTheEndKeepsTrailingLines() {
        val input = "package p\n\nfunc f( ) {}\n\nvar x = 1\n\n\n"
        myFixture.configureByText("a.go", input)
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformatText(myFixture.file, 0, input.indexOf("{}") + 2)
        }
        assertEquals("package p\n\nfunc f() {}\n\nvar x = 1\n\n\n", myFixture.editor.document.text)
    }

    // --- range reformat and reformat-on-save ------------------------------------------------------

    fun testRangeInsideOneFunctionFormatsOnlyItsLines() {
        val input = lines(
            "package p",
            "",
            "func f() {",
            "x:=1",
            "y:=2",
            "z:=3",
            "_, _, _ = x, y, z",
            "}",
            "",
            "func g( ) {",
            "a:=1",
            "_ = a",
            "}",
        )
        myFixture.configureByText("a.go", input)
        val start = input.indexOf("y:=2")
        val end = input.indexOf("z:=3") + "z:=3".length
        WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformatText(myFixture.file, start, end) }
        val expected = lines(
            "package p",
            "",
            "func f() {",
            "x:=1",
            "\ty := 2",
            "\tz := 3",
            "_, _, _ = x, y, z",
            "}",
            "",
            "func g( ) {",
            "a:=1",
            "_ = a",
            "}",
        )
        assertEquals(expected, myFixture.editor.document.text)
    }

    /** Reformat-on-save runs the core formatting service over the whole file; nothing Go-specific beyond the whole-file path. */
    fun testReformatOnSavePath() {
        myFixture.configureByText("a.go", "package p\n\nfunc f( a int ) int {\nreturn a\n}\n\n")
        val file = myFixture.file
        assertInstanceOf(FormattingServiceUtil.findService(file, true, true), CoreFormattingService::class.java)
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformatText(file, listOf(file.textRange))
        }
        val formatted = "package p\n\nfunc f(a int) int {\n\treturn a\n}\n"
        assertEquals(formatted, myFixture.editor.document.text)
        // saving again: nothing to do; the layout of the formatted text is computed once (the first
        // save changed the file after its layout was computed) and reused by later saves
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformatText(file, listOf(file.textRange))
        }
        val computed = GoLayout.computationCount()
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformatText(file, listOf(file.textRange))
        }
        assertEquals(formatted, myFixture.editor.document.text)
        assertEquals(computed, GoLayout.computationCount())
    }

    // --- helpers ----------------------------------------------------------------------------------

    private fun lines(vararg l: String): String = l.joinToString("\n", postfix = "\n")

    private fun read(name: String): String = File(testDataPath, name).readText().replace("\r\n", "\n")

    private fun psi(text: String): PsiFile =
        PsiFileFactory.getInstance(project).createFileFromText("a.go", GoFileType, text, System.currentTimeMillis(), true)

    private fun reformat(file: PsiFile) {
        WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformat(file) }
    }

    private fun reformatText(text: String): String {
        myFixture.configureByText("a.go", text)
        reformat(myFixture.file)
        return myFixture.editor.document.text
    }

    /** The top-level declaration of [text] starting at the line that starts with [header], up to the next blank line. */
    private fun declaration(text: String, header: String): String {
        val start = text.lineSequence().runningFold(0) { offset, line -> offset + line.length + 1 }
            .zip(text.lineSequence()).first { it.second.startsWith(header) }.first
        val end = text.indexOf("\n\n", start).let { if (it < 0) text.length else it + 1 }
        return text.substring(start, end)
    }

    /** Lexer tokens of [text]; without trivia: no whitespace and no inserted semicolons. */
    private fun tokens(text: String, withTrivia: Boolean = false): List<String> {
        val lexer = GoLexer()
        lexer.start(text)
        val result = ArrayList<String>()
        while (lexer.tokenType != null) {
            val type = lexer.tokenType
            if (withTrivia || type != TokenType.WHITE_SPACE && type != GoTypes.SEMICOLON_SYNTHETIC) result += "$type:${lexer.tokenText}"
            lexer.advance()
        }
        return result
    }

    private fun leaves(root: ASTNode): List<String> {
        val result = ArrayList<String>()
        var leaf = TreeUtil.findFirstLeaf(root)
        while (leaf != null) {
            if (leaf.textLength > 0) result += "${leaf.elementType}:${leaf.text}"
            leaf = TreeUtil.nextLeaf(leaf)
        }
        return result
    }
}
