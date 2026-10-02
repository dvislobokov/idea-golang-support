package io.github.golangsupport.semantic

/** `expr /*T: type*/` checks over `testData/types/<group>`. */
class GoTypeOfTest : GoSemanticTestBase() {
    override val group: String = "types"

    fun testExprs() = checkFixture("exprs")
    fun testInference() = checkFixture("inference")
}
