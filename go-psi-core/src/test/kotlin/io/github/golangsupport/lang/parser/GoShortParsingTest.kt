package io.github.golangsupport.lang.parser

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.GoParsingTestCase
import io.github.golangsupport.GoTestUtil
import java.io.File

/**
 * Port of `go/parser/short_test.go` (frozen under `testData/parser/short`): every valid snippet
 * must parse without error elements; every invalid snippet must produce at least one.
 */
class GoShortParsingTest : GoParsingTestCase("parser/short") {

    /**
     * Expected-error substrings (from `invalid/index.txt`) that this parser deliberately does not
     * report; the semantic layer reports them instead.
     */
    private val semanticOnlyErrors: List<String> = listOf(
        "must be function call", "must not be parenthesized", // go/defer operand checks
        "redeclared", // receiver type parameter redeclared
        "middle index required", "final index required", // 3-index slices parse; the checker reports them
    )

    fun testValidSnippets() {
        val failures = mutableListOf<String>()
        for (file in snippets("valid")) {
            val psi = createPsiFile(file.nameWithoutExtension, file.readText())
            ensureParsed(psi)
            val error = PsiTreeUtil.findChildOfType(psi, PsiErrorElement::class.java)
            if (error != null) {
                failures += "${file.name}: '${error.errorDescription}' at ${error.textOffset} in: ${file.readText().trim()}"
            }
        }
        println("short/valid: ${failures.size} failures")
        failures.forEach(::println)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /**
     * Rows of `invalid/index.txt` with an expected message must produce error elements unless the
     * message is semantic-only; rows with an empty message (syntax that became valid upstream,
     * e.g. generic methods in Go 1.27) must parse cleanly.
     */
    fun testInvalidSnippets() {
        val expected = File(GoTestUtil.testDataPath("parser/short/invalid/index.txt")).readLines()
            .filter { it.isNotBlank() }
            .associate { line -> val parts = line.split('\t'); parts[0] to parts[1] }
        val accepted = mutableListOf<String>()
        val unexpected = mutableListOf<String>()
        for (file in snippets("invalid")) {
            val psi = createPsiFile(file.nameWithoutExtension, file.readText())
            ensureParsed(psi)
            val message = expected.getValue(file.nameWithoutExtension)
            val hasErrors = hasErrorElements(psi)
            val line = "${file.name} [${message.ifEmpty { "valid since Go 1.27" }}]: ${file.readText().trim()}"
            if (message.isEmpty()) {
                if (hasErrors) unexpected += "error elements in now-valid snippet $line"
            } else if (!hasErrors) {
                accepted += line
                if (semanticOnlyErrors.none { message.contains(it) }) unexpected += line
            }
        }
        println("short/invalid accepted without errors (semantic-only): ${accepted.size}")
        accepted.forEach(::println)
        assertTrue("Unexpected parser results:\n" + unexpected.joinToString("\n"), unexpected.isEmpty())
    }

    private fun snippets(kind: String): List<File> =
        File(GoTestUtil.testDataPath("parser/short/$kind")).listFiles { f -> f.extension == "go" }!!.sortedBy { it.name }
}
