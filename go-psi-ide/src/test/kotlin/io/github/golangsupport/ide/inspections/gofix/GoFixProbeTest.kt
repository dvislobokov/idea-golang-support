package io.github.golangsupport.ide.inspections.gofix

import com.intellij.lang.annotation.HighlightSeverity
import io.github.golangsupport.ide.inspections.gofix.GoSyntaxUpdateSeverity.SEVERITY
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import java.io.File

/**
 * Every Go fix inspection over the GoLand probe files (`tools/ui-robot/goland/probe`): GoLand marks exactly one place, the counted loop at
 * line 118 of `analysis.go` (`docs/goland-analysis/dumps/highlight-internal-probe-analysis.txt`, GO_SYNTAX_UPDATE), and nothing in the others.
 */
class GoFixProbeTest : GoSemanticIdeTestBase() {

    private fun probe(relative: String): String = File(File(testDataRoot()).parentFile, "tools/ui-robot/goland/probe/$relative").readText().replace("\r\n", "\n")

    /** "line: text «highlighted»" of every Go fix problem in [text]. */
    private fun problems(name: String, text: String): List<String> {
        myFixture.enableInspections(*GoFixInspections.ALL.map { it.getDeclaredConstructor().newInstance() }.toTypedArray())
        myFixture.configureByText(name, text)
        val document = myFixture.editor.document
        return myFixture.doHighlighting(HighlightSeverity.INFORMATION).filter { it.severity == SEVERITY && it.description != null }
            .map { "${document.getLineNumber(it.startOffset) + 1}: ${it.description} «${document.charsSequence.subSequence(it.startOffset, it.endOffset)}»" }
    }

    fun testAnalysisHasOnlyTheCountedLoop() {
        assertEquals(listOf("118: for loop can be modernized using range over int «i := 0; i < n; i++»"), problems("analysis.go", probe("analysis.go")))
    }

    fun testAnalysisTestHasNothing() {
        assertEquals(emptyList<String>(), problems("analysis_test.go", probe("analysis_test.go")))
    }

    fun testBrokenFileHasNothing() {
        assertEquals(emptyList<String>(), problems("broken.go", probe("probeerr/broken.go")))
    }
}
