package io.github.golangsupport.ide.usages

import com.intellij.codeInsight.highlighting.ReadWriteAccessDetector
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.lang.psi.GoReferenceExpression

/** Exit-point / loop highlighting on control-flow keywords, identifier highlighting with read/write access. */
class GoHighlightUsagesTest : GoSemanticIdeTestBase() {

    override val testDataSubdir: String = "usages/highlight"

    private fun highlighted(file: String): List<String> =
        myFixture.testHighlightUsages(file).map { h -> myFixture.editor.document.getText(h.textRange) }.sorted()

    fun testReturnHighlightsExitPoints() {
        assertEquals(listOf("panic(\"neg\")", "return 0, nil", "return x, nil"), highlighted("returns.go"))
    }

    fun testFuncKeywordHighlightsReturns() {
        assertEquals(listOf("return 0", "return x"), highlighted("func.go"))
    }

    fun testBreakHighlightsLoopAndJumps() {
        assertEquals(listOf("break", "continue", "for"), highlighted("break.go"))
    }

    fun testLabeledContinue() {
        // The unlabeled `break` after the inner loop leaves the outer loop too.
        assertEquals(listOf("break", "break outer", "continue outer", "for"), highlighted("labeled.go"))
    }

    fun testSelectKeyword() {
        assertEquals(listOf("break", "select"), highlighted("loop.go"))
    }

    fun testIdentifierHighlightingWithWriteAccess() {
        myFixture.configureByText("v.go", "package v\n\nfunc f() int {\n\tx<caret> := 1\n\tx = 2\n\tx++\n\treturn x\n}\n")
        com.intellij.codeInsight.highlighting.HighlightUsagesHandler.invoke(project, myFixture.editor, myFixture.file)
        val highlighters = myFixture.editor.markupModel.allHighlighters
        // Declaration + three usages.
        assertEquals(4, highlighters.size)
        val detector = GoReadWriteAccessDetector()
        val refs = PsiTreeUtil.findChildrenOfType(myFixture.file, GoReferenceExpression::class.java).filter { it.text == "x" }
        assertEquals(
            listOf(ReadWriteAccessDetector.Access.Write, ReadWriteAccessDetector.Access.ReadWrite, ReadWriteAccessDetector.Access.Read),
            refs.map { detector.getExpressionAccess(it) },
        )
    }
}
