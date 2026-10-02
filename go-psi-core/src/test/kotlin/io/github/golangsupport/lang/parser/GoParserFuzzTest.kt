package io.github.golangsupport.lang.parser

import io.github.golangsupport.GoTestUtil
import java.io.File

/**
 * Fast fuzz gate: 10 files from `testData/parser/cases` and `testData/parser/goroot`, 5 seeded
 * mutations each. The hard rules and the recovery locality rule are described in
 * [GoParserFuzzTestBase]; the GOROOT-wide version is `GoParserFuzzCorpusTest`.
 */
class GoParserFuzzTest : GoParserFuzzTestBase() {

    fun testMutantsParseLosslesslyAndRecoverLocally() {
        val files = File(GoTestUtil.testDataPath("parser")).walkTopDown()
            .filter { it.isFile && it.extension == "go" && ("/cases/" in it.invariantSeparatorsPath || "/goroot/" in it.invariantSeparatorsPath) }
            .sortedBy { it.invariantSeparatorsPath }
            .toList()
        assertTrue("expected test sources under testData/parser", files.size >= 10)
        // Spread the sample over the sorted list instead of taking the first ten.
        val sample = (0 until 10).map { files[it * files.size / 10] }
        val result = fuzz(sample.map { it.name to it.readText() }, mutationsPerFile = 5, seed = 20260930)
        printSummary("GoParserFuzzTest", result, worst = 5)
        assertNoHardFailures(result)
        assertTrue(
            "recovery locality violated:\n" + result.violations.take(5).joinToString("\n") { it.second.toString() },
            result.violations.isEmpty(),
        )
    }
}
