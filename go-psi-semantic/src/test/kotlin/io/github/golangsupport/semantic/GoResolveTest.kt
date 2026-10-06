package io.github.golangsupport.semantic

/** Marker-based resolve tests over `testData/resolve/<group>` (see [GoSemanticTestBase]). */
class GoResolveTest : GoSemanticTestBase() {
    override val group: String = "resolve"

    fun testLocals() = checkFixture("locals")
    fun testBigblock() = checkFixture("bigblock")
    fun testPkglevel() = checkFixture("pkglevel")
    fun testImports() = checkFixture("imports")
    fun testSelectors() = checkFixture("selectors")
    fun testLiterals() = checkFixture("literals")
    fun testLabels() = checkFixture("labels")
    fun testTypeswitch() = checkFixture("typeswitch")
    fun testGenerics() = checkFixture("generics")
    fun testGenerics2() = checkFixture("generics2")
    fun testUniverse() = checkFixture("universe")
    fun testCgo() = checkFixture("cgo")
    fun testTestfiles() = checkFixture("testfiles")
    fun testPromotedkeys() = checkFixture("promotedkeys")
}
