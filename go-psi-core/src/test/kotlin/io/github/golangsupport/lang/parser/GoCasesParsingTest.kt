package io.github.golangsupport.lang.parser

import io.github.golangsupport.GoParsingTestCase

/** Golden PSI trees for the hand-written cases under `testData/parser/cases` (see docs/GRAMMAR.md). */
class GoCasesParsingTest : GoParsingTestCase("parser/cases") {
    fun testConstSpecTyped() = doTest(false)
    fun testDecls() = doTest(true)
    fun testDirectives() = doTest(true)
    fun testFieldArrayOrInstance() = doTest(true)
    fun testGo127() = doTest(true)
    fun testUnformattedBlock() = doTest(true)
    fun testIfCompositeLit() = doTest(true)
    fun testIndexSliceInstance() = doTest(true)
    fun testInterfaces() = doTest(true)
    fun testLiterals() = doTest(true)
    fun testParamLists() = doTest(true)
    fun testReceivers() = doTest(true)
    fun testSemicolons() = doTest(true)
    fun testSimpleStmts() = doTest(true)
    fun testStatements() = doTest(true)
    fun testStructs() = doTest(true)
    fun testTypeParamsVsArray() = doTest(true)
    fun testTypeParamsParens() = doTest(true)

    /** Deliberately invalid: must produce error elements. */
    fun testTypeParamsVsArrayInvalid() {
        doTest(false)
        assertTrue("expected error elements", hasErrorElements(myFile))
    }
}
