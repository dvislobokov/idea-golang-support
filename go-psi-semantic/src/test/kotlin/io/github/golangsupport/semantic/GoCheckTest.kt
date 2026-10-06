package io.github.golangsupport.semantic

import java.io.File

/** `check(file)` over the hand-written fixtures under `testData/check` (same ERROR protocol as go/types testdata, no allowlist). */
class GoCheckTest : GoErrorSiteTestBase() {

    private fun check(name: String) {
        val f = File(testDataPath("check/$name.go").toString())
        val r = checkFile(f, "check/$name.go", emptyMap(), "check_$name")
        println(report(listOf(r)))
        assertTrue("false positives:\n" + r.falsePositives.joinToString("\n"), r.falsePositives.isEmpty())
        assertTrue("unmatched ERROR sites:\n" + r.missed.joinToString("\n"), r.missed.isEmpty())
    }

    fun testUndefined() = check("undefined")
    fun testUnused() = check("unused")
    fun testAssignability() = check("assignability")
    fun testCalls() = check("calls")
    fun testOperators() = check("operators")
    fun testLiterals() = check("literals")
    fun testGenerics() = check("generics")
    fun testStatements() = check("statements")
    fun testClean() = check("clean")
    fun testDeclarations() = check("declarations")
    fun testTypeParams() = check("typeparams")
    fun testControlFlow() = check("controlflow")
    fun testGo127() = check("go127")
}
