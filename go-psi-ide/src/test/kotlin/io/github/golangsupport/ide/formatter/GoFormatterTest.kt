package io.github.golangsupport.ide.formatter

import com.intellij.lang.LanguageFormatting
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.TokenType
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import io.github.golangsupport.ide.GoIdeTestBase
import io.github.golangsupport.ide.formatter.printer.GoLayout
import io.github.golangsupport.lang.GoFileType
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.lang.lexer.GoLexer
import io.github.golangsupport.lang.psi.GoElementFactory
import io.github.golangsupport.lang.psi.GoTypes
import java.io.File

/**
 * Golden tests: `testData/formatter/<name>.go` reformatted must equal `<name>.after.go`, which is
 * the output of the real gofmt (created on the first run when missing; the test then fails once
 * on purpose). Every case also checks that only whitespace changed and that formatting the result
 * again is a no-op.
 */
class GoFormatterTest : GoIdeTestBase() {

    override val testDataSubdir = "formatter"

    fun testExpressionSpacing() = doTest()
    fun testStructAlignment() = doTest()
    fun testConstIota() = doTest()
    fun testVarBlock() = doTest()
    fun testCompositeKeyed() = doTest()
    fun testCompositePositional() = doTest()
    fun testNestedLiterals() = doTest()
    fun testSwitchSelect() = doTest()
    fun testLabels() = doTest()
    fun testIfElse() = doTest()
    fun testFuncSignatures() = doTest()
    fun testComments() = doTest()
    fun testBlankLines() = doTest()
    fun testImportsSort() = doTest(sameTokenOrder = false)
    fun testImportsComments() = doTest(sameTokenOrder = false)
    fun testGenerics() = doTest()
    fun testChannels() = doTest()
    fun testMethodChains() = doTest()
    fun testRawStrings() = doTest()
    fun testGoBuild() = doTest()
    fun testIndentation() = doTest()
    fun testOneLineFuncs() = doTest()
    fun testInterfaces() = doTest()
    fun testTrailingComments() = doTest()
    fun testForRange() = doTest()
    fun testReturnLists() = doTest()
    fun testTypeDecls() = doTest()
    fun testUnaryAndKeywords() = doTest()

    fun testExtensionsRegistered() {
        assertInstanceOf(LanguageFormatting.INSTANCE.forLanguage(GoLanguage), GoFormattingModelBuilder::class.java)
        assertInstanceOf(LanguageCodeStyleSettingsProvider.forLanguage(GoLanguage), GoLanguageCodeStyleSettingsProvider::class.java)
        val settings = CodeStyleSettingsManager.getInstance(project).currentSettings
        val options = settings.getIndentOptions(GoFileType)
        assertTrue(options.USE_TAB_CHARACTER)
        assertEquals(4, options.TAB_SIZE)
        assertEquals(4, options.INDENT_SIZE)
        assertTrue(settings.getCommonSettings(GoLanguage).LINE_COMMENT_ADD_SPACE)
    }

    fun testCodeSampleIsGofmtClean() {
        val sample = LanguageCodeStyleSettingsProvider.getCodeSample(GoLanguage, LanguageCodeStyleSettingsProvider.SettingsType.INDENT_SETTINGS)
        assertNotNull(sample)
        assertEquals(sample, reformat(sample!!))
    }

    fun testReformatAction() {
        myFixture.configureByText("a.go", lines("package p", "", "func f( ) {", "x:=1", "_ = x", "}"))
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
        myFixture.checkResult(lines("package p", "", "func f() {", "\tx := 1", "\t_ = x", "}"))
    }

    fun testRangeReformatOnlyTouchesSelection() {
        myFixture.configureByText("a.go", lines("package p", "", "func f() {", "x:=1", "<selection>y:=2</selection>", "_, _ = x, y", "}"))
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
        myFixture.checkResult(lines("package p", "", "func f() {", "x:=1", "\ty := 2", "_, _ = x, y", "}"))
    }

    /** gofmt would drop the `;`, the trailing `,` and the parentheses; only whitespace may change here. */
    fun testTokensGofmtWouldDropAreKept() {
        val input = lines("package p", "", "func f(x, y int) {", "a := 1; b := 2", "g(a, b,)", "if (x > y) {", "}", "}")
        val expected = lines("package p", "", "func f(x, y int) {", "\ta := 1;", "\tb := 2", "\tg(a, b,)", "\tif (x > y) {", "\t}", "}")
        assertEquals(expected, reformat(input))
        assertEquals(tokens(input), tokens(expected))
    }

    fun testSyntaxErrorFallsBackWithoutChangingTokens() {
        val text = lines("package p", "", "func f() {", "x:=1+", "}", "", "func g(  a int) {", "return", "}")
        assertFalse(GoLayout.compute(GoElementFactory.createFileFromText(project, text).node)!!.isComplete)
        val result = reformat(text)
        assertEquals(tokens(text), tokens(result))
        assertTrue(result, result.contains("\treturn"))
    }

    fun testEnterIndentsInsideBlock() = checkTyping(
        lines("package p", "", "func f() {<caret>", "}"),
        "\nx",
        lines("package p", "", "func f() {", "\tx<caret>", "}"),
    )

    fun testEnterIndentsCaseBody() = checkTyping(
        lines("package p", "", "func f(x int) {", "\tswitch x {", "\tcase 1:<caret>", "\t}", "}"),
        "\nx",
        lines("package p", "", "func f(x int) {", "\tswitch x {", "\tcase 1:", "\t\tx<caret>", "\t}", "}"),
    )

    fun testEnterInsideStruct() = checkTyping(
        lines("package p", "", "type T struct {<caret>", "}"),
        "\nA",
        lines("package p", "", "type T struct {", "\tA<caret>", "}"),
    )

    fun testEnterAfterStatementKeepsIndent() = checkTyping(
        lines("package p", "", "func f() {", "\tx := 1<caret>", "}"),
        "\ny",
        lines("package p", "", "func f() {", "\tx := 1", "\ty<caret>", "}"),
    )

    fun testAutoIndentLines() {
        myFixture.configureByText("a.go", lines("package p", "", "func f(x int) {", "\tswitch x {", "\tcase 1:", "<caret>x++", "\t}", "}"))
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_AUTO_INDENT_LINES)
        assertEquals(lines("package p", "", "func f(x int) {", "\tswitch x {", "\tcase 1:", "\t\tx++", "\t}", "}"), myFixture.editor.document.text)
    }

    // --- helpers ------------------------------------------------------------------------------

    private fun lines(vararg l: String): String = l.joinToString("\n", postfix = "\n")

    private fun checkTyping(before: String, typed: String, after: String) {
        myFixture.configureByText("a.go", before)
        myFixture.type(typed)
        val caret = myFixture.editor.caretModel.offset
        val text = myFixture.editor.document.text
        assertEquals(after, text.substring(0, caret) + "<caret>" + text.substring(caret))
    }

    private fun doTest(sameTokenOrder: Boolean = true) {
        val name = getTestName(true)
        val input = File(testDataPath, "$name.go").readText().replace("\r\n", "\n")
        val afterFile = File(testDataPath, "$name.after.go")
        if (!afterFile.exists()) {
            val gofmt = GofmtRunner.find() ?: error("gofmt not found; cannot create ${afterFile.name}")
            afterFile.writeText(gofmt.format(input))
            fail("Created golden ${afterFile.path} with gofmt; review it and rerun")
        }
        val expected = afterFile.readText().replace("\r\n", "\n")

        myFixture.configureByText("$name.go", input)
        WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformat(myFixture.file) }
        val actual = myFixture.editor.document.text
        assertEquals("gofmt golden $name", expected, actual)

        if (sameTokenOrder) {
            assertEquals("token stream changed", tokens(input), tokens(actual))
        } else {
            assertEquals("token multiset changed", tokens(input).sorted(), tokens(actual).sorted())
        }
        assertEquals("formatting is not idempotent", expected, reformat(expected))

        // the flat segment blocks of Reformat Code and the PSI-shaped block tree apply the same layout
        GoFormattingModelBuilder.segmentsEnabled = false
        try {
            assertEquals("PSI-shaped block tree", expected, reformat(input))
        } finally {
            GoFormattingModelBuilder.segmentsEnabled = true
        }
    }

    private fun reformat(text: String): String {
        myFixture.configureByText("again.go", text)
        WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformat(myFixture.file) }
        return myFixture.editor.document.text
    }

    /** Non-whitespace tokens (inserted semicolons are line breaks, so they are excluded). */
    private fun tokens(text: String): List<String> {
        val lexer = GoLexer()
        lexer.start(text)
        val result = ArrayList<String>()
        while (lexer.tokenType != null) {
            val type = lexer.tokenType
            if (type != TokenType.WHITE_SPACE && type != GoTypes.SEMICOLON_SYNTHETIC) result += "$type:${lexer.tokenText}"
            lexer.advance()
        }
        return result
    }
}
