package io.github.golangsupport.ide.completion

import io.github.golangsupport.ide.completion.api.GoCompletionRanker

/** The ranker without a model: frequency in the file and the package, recency of choice, both only between candidates the deterministic order ties. */
class GoHeuristicRankerTest : GoCompletionTestBase() {

    override fun setUp() {
        super.setUp()
        GoCompletionRecency.getInstance(project).clear()
        GoAcceptanceMemory.getInstance(project).clear()
    }

    override fun tearDown() {
        try {
            GoCompletionRecency.getInstance(project).clear()
            // the chosen item of testAChosenItemIsRemembered is counted by the acceptance memory too (one light project for every test)
            GoAcceptanceMemory.getInstance(project).clear()
        } finally {
            super.tearDown()
        }
    }

    fun testRegisteredLastOnTheExtensionPoint() {
        assertTrue(GoCompletionRanker.EP_NAME.extensionList.last() is GoHeuristicRanker)
    }

    fun testTheScoreKeepsTheDeterministicOrder() {
        // a better expected-type match or a closer scope wins whatever the bonus
        assertTrue(GoHeuristicRanker.score(1, 6, 0.0) > GoHeuristicRanker.score(0, 0, 9.0))
        assertTrue(GoHeuristicRanker.score(0, 3, 0.0) > GoHeuristicRanker.score(0, 4, 9.0))
        assertTrue(GoHeuristicRanker.score(0, 3, 1.0) > GoHeuristicRanker.score(0, 3, 0.5))
    }

    fun testIdentifiersAreCountedByTheLexer() {
        val counts = GoIdentifierCounts.count("package p\n\n// beta beta in a comment\nfunc f() { beta(); beta(); _ = \"beta\" }\n")
        assertEquals(2, counts["beta"])
        assertEquals(1, counts["f"])
    }

    private val twoFunctions = """
        package main

        func alphaRun() {}
        func alphaStop() {}

    """

    fun testEqualCandidatesByFrequencyInTheFile() {
        // alphabetical order would put alphaRun first: alphaStop is the one the file uses
        val items = lookups(twoFunctions + """
            func main() {
                alphaStop()
                alphaStop()
                alpha<caret>
            }
        """)
        assertTrue(items.toString(), items.indexOf("alphaStop") < items.indexOf("alphaRun"))
    }

    fun testEqualCandidatesByFrequencyInThePackage() {
        myFixture.addFileToProject("other.go", "package main\n\nfunc use() {\n\talphaStop()\n\talphaStop()\n}\n")
        val items = lookups(twoFunctions + """
            func main() {
                alpha<caret>
            }
        """)
        assertTrue(items.toString(), items.indexOf("alphaStop") < items.indexOf("alphaRun"))
    }

    fun testWithoutUsesTheOrderStaysAlphabetical() {
        val items = lookups(twoFunctions + "func main() {\n    alpha<caret>\n}\n")
        assertTrue(items.toString(), items.indexOf("alphaRun") < items.indexOf("alphaStop"))
    }

    fun testEqualCandidatesByRecency() {
        val text = twoFunctions + "func main() {\n    alpha<caret>\n}\n"
        GoCompletionRecency.getInstance(project).record("alphaStop")
        val items = lookups(text)
        assertTrue(items.toString(), items.indexOf("alphaStop") < items.indexOf("alphaRun"))
    }

    fun testAChosenItemIsRemembered() {
        GoCompletionRecencyListener.recordInTests = true
        try {
            doTestAChosenItemIsRemembered()
        } finally {
            GoCompletionRecencyListener.recordInTests = false
        }
    }

    private fun doTestAChosenItemIsRemembered() {
        checkInsert(twoFunctions + "func main() {\n    alpha<caret>\n}\n", "alphaStop", twoFunctions + "func main() {\n    alphaStop()<caret>\n}\n")
        assertTrue(GoCompletionRecency.getInstance(project).weight("alphaStop") > 0.0)
        assertEquals(0.0, GoCompletionRecency.getInstance(project).weight("alphaRun"))
    }
}
