package io.github.golangsupport.semantic

import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.semantic.api.GoSemanticService
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
    fun testInstantiation() = check("instantiation")
    fun testSatisfaction() = check("satisfaction")
    fun testInferUnknown() = check("inferunknown")
    fun testGo127() = check("go127")
    fun testRecursiveIface() = check("recursiveiface")
    fun testGo127Errors() = check("go127errors")
    fun testLongString() = check("longstring")
    fun testInitCycles() = check("initcycles")

    /** A method of a `_test.go` file is in the test variant of the package only (prometheus `discovery/kubernetes`, `model/labels`). */
    fun testMethodsOfTestFiles() {
        val main = myFixture.addFileToProject("tm/d.go", """
            package k

            type D struct{}

            type synced interface{ hasSynced() bool }

            func use(d *D) bool { return d.hasSynced() }
        """.trimIndent()) as GoFile
        val test = myFixture.addFileToProject("tm/d_test.go", """
            package k

            func (d *D) hasSynced() bool { return true }

            var _ synced = &D{}

            func f(d *D) bool { var s synced = d; return d.hasSynced() && s.hasSynced() }
        """.trimIndent()) as GoFile
        val service = GoSemanticService.getInstance(project)
        assertEquals(emptyList<String>(), service.check(test).map { it.message })
        assertEquals(listOf("d.hasSynced undefined (type *D has no field or method hasSynced)"), service.check(main).map { it.message })
    }
}
