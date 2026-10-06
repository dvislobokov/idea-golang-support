package io.github.golangsupport.semantic

import java.io.File

/** Builtin, constant, comparison and declaration checks (`testData/check/builtinsconsts.go`), same ERROR protocol as [GoCheckTest]. */
class GoCheckBuiltinsConstsTest : GoErrorSiteTestBase() {

    fun testBuiltinsConsts() = check("builtinsconsts")

    /** `func main` signature rules need package main. */
    fun testMainSignature() = check("builtinsconsts_main")

    private fun check(name: String) {
        val f = File(testDataPath("check/$name.go").toString())
        val r = checkFile(f, "check/$name.go", emptyMap(), "check_$name")
        println(report(listOf(r)))
        assertTrue("false positives:\n" + r.falsePositives.joinToString("\n"), r.falsePositives.isEmpty())
        assertTrue("unmatched ERROR sites:\n" + r.missed.joinToString("\n"), r.missed.isEmpty())
    }
}
