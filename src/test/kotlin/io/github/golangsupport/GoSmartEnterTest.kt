package io.github.golangsupport

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Complete Statement (Ctrl+Shift+Enter) by the PSI of the caret line: `<caret>` before and after. */
class GoSmartEnterTest : BasePlatformTestCase() {
    private fun complete(before: String, after: String) {
        myFixture.configureByText("a.go", before)
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_COMPLETE_STATEMENT)
        myFixture.checkResult(after)
    }

    private fun body(lines: String) = "package a\n\nfunc f(x int, ok bool) {\n$lines\n}\n"

    fun testAnIfHeaderGetsItsBodyAndTheCaretInside() =
        complete(body("\tif x > 0<caret>"), body("\tif x > 0 {\n\t\t<caret>\n\t}"))

    fun testAnIfHeaderWithAnUnclosedCallIsClosedFirst() =
        complete(body("\tif check(x<caret>"), body("\tif check(x) {\n\t\t<caret>\n\t}"))

    fun testABareForDoesNotTakeTheNextLineAsItsHeader() =
        complete(body("\tfor<caret>\n\tg()"), body("\tfor {\n\t\t<caret>\n\t}\n\tg()"))

    fun testSwitchAndSelectGetBraces() {
        complete(body("\tswitch x<caret>"), body("\tswitch x {\n\t\t<caret>\n\t}"))
        complete(body("\tselect<caret>"), body("\tselect {\n\t\t<caret>\n\t}"))
    }

    fun testElseAfterAClosingBraceGetsItsBody() {
        complete(body("\tif ok {\n\t} else<caret>"), body("\tif ok {\n\t} else {\n\t\t<caret>\n\t}"))
        complete(body("\tif ok {\n\t} else if x > 1<caret>"), body("\tif ok {\n\t} else if x > 1 {\n\t\t<caret>\n\t}"))
    }

    fun testAFunctionWithoutParametersGetsThemAndABody() =
        complete("package a\n\nfunc run<caret>\n", "package a\n\nfunc run() {\n\t<caret>\n}\n")

    fun testAFunctionWithADocCommentIsStillAHeader() =
        complete("package a\n\n// run runs.\nfunc run(n int<caret>\n", "package a\n\n// run runs.\nfunc run(n int) {\n\t<caret>\n}\n")

    fun testAStructTypeGetsItsBraces() =
        complete("package a\n\ntype Server struct<caret>\n", "package a\n\ntype Server struct {\n\t<caret>\n}\n")

    fun testACallMissingItsParenthesesIsClosedAndTheCaretGoesToTheNextLine() =
        complete(body("\tprintln(add(x<caret>"), body("\tprintln(add(x))\n\t<caret>"))

    fun testParenthesesInsideAStringDoNotCount() =
        complete(body("\tprintln(\"(\" + str(x<caret>"), body("\tprintln(\"(\" + str(x))\n\t<caret>"))

    fun testAOneLineStructLiteralGetsItsClosingBrace() =
        complete(body("\tp := Point{X: 1<caret>"), body("\tp := Point{X: 1}\n\t<caret>"))

    fun testAnElementOfAMultiLineLiteralGetsItsComma() =
        complete(body("\tp := Point{\n\t\tX: 1<caret>\n\t}"), body("\tp := Point{\n\t\tX: 1,\n\t\t<caret>\n\t}"))

    fun testAnArgumentOfAMultiLineCallGetsItsComma() =
        complete(body("\tprintln(\n\t\tx<caret>\n\t)"), body("\tprintln(\n\t\tx,\n\t\t<caret>\n\t)"))

    fun testACompleteStatementMovesTheCaretToTheNextLine() =
        complete(body("\ty := <caret>x + 1"), body("\ty := x + 1\n\t<caret>"))

    fun testATrailingCommentStaysAfterTheCode() =
        complete(body("\ty := x<caret> // why"), body("\ty := x\n\t<caret> // why"))

    fun testAGoroutineLiteralIsOpenedAndCalled() {
        complete(body("\tgo func<caret>"), body("\tgo func() {\n\t\t<caret>\n\t}()"))
        complete(body("\th := func<caret>"), body("\th := func() {\n\t\t<caret>\n\t}"))
    }

    fun testAnOpenBraceWithItsClosingGetsANewLineInside() =
        complete(body("\tif ok {<caret>\n\t}"), body("\tif ok {\n\t\t<caret>\n\t}"))

    fun testAnOpenBraceWithoutItsClosingGetsIt() =
        complete(body("\tp := Point{<caret>"), body("\tp := Point{\n\t\t<caret>\n\t}"))

    fun testAnEmptyCommentAboveADeclarationGetsItsName() =
        complete("package a\n\n//<caret>\nfunc (s *Server) Start() {}\n", "package a\n\n// Start <caret>\nfunc (s *Server) Start() {}\n")
}
