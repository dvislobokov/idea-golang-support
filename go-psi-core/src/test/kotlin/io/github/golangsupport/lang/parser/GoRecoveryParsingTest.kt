package io.github.golangsupport.lang.parser

import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.GoParsingTestCase
import io.github.golangsupport.lang.psi.GoFunctionDeclaration

/**
 * Error recovery goldens under `testData/parser/recovery`. Every file has a broken declaration
 * followed by `func ok() {}` (and sometimes more); the declarations after the broken one must
 * parse cleanly, so errors stay local.
 */
class GoRecoveryParsingTest : GoParsingTestCase("parser/recovery") {
    fun testIncompleteFuncDecl() = doRecoveryTest()
    fun testMissingClosingBrace() = doRecoveryTest()
    fun testUnclosedBlockBeforeDecls() = doRecoveryTest()
    fun testMissingParenInCall() = doRecoveryTest()
    fun testStrayTopLevel() = doRecoveryTest()
    fun testIncompleteIfFor() = doRecoveryTest()
    fun testIncompleteCompositeLit() = doRecoveryTest()
    fun testIncompleteExpression() = doRecoveryTest()
    fun testBadCharacters() = doRecoveryTest()

    /** Unclosed lazy bodies whose trailing semicolons are interleaved with comments (lazyBlock's raw-token scan). */
    fun testUnclosedBodyTrailingComments() = doRecoveryTest()

    private fun doRecoveryTest() {
        doTest(false)
        assertTrue("the file should contain error elements", hasErrorElements(myFile))
        val ok = PsiTreeUtil.findChildrenOfType(myFile, GoFunctionDeclaration::class.java)
            .firstOrNull { it.identifier.text == "ok" }
        assertNotNull("func ok() must be parsed as a top-level FunctionDeclaration", ok)
        assertFalse("func ok() must not contain error elements", hasErrorElements(ok!!))
        assertEquals("func ok() must be complete", "func ok() {}", ok.text)
    }
}
