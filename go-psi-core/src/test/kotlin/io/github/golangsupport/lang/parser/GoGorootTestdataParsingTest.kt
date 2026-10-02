package io.github.golangsupport.lang.parser

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.GoParsingTestCase
import io.github.golangsupport.GoTestUtil
import java.io.File

/**
 * Files copied from GOROOT under `testData/parser/goroot` (see SOURCES.txt there). Files with
 * `ERROR` annotations must produce error elements; all others must parse cleanly.
 */
class GoGorootTestdataParsingTest : GoParsingTestCase("parser/goroot") {

    private val errorMarker = Regex("""/\* ERROR|// ERROR""")

    /**
     * Files whose ERROR annotations are type-checker or language-version errors, not syntax
     * errors, so this parser accepts them. Paths relative to `testData/parser/goroot`.
     */
    private val semanticErrorsOnly: Set<String> = setOf(
        // mixed named and unnamed parameters: go/parser reports it in parseParameterList; left to the semantic layer
        "src/cmd/compile/internal/syntax/testdata/issue69506.go",
        "src/go/parser/testdata/issue69506.go2",
        // invalid break/continue labels: semantic
        "src/cmd/compile/internal/syntax/testdata/issue70974.go",
        // type parameter redeclared: semantic
        "test/typeparam/tparam1.go",
    )

    fun testGorootTestdata() {
        val root = File(GoTestUtil.testDataPath("parser/goroot"))
        val files = root.walkTopDown().filter { it.isFile && (it.extension == "go" || it.extension == "go2" || it.extension == "src") }
            // issue42951: a directory named `not_a_file.go` holding a file that is not Go source.
            .filter { !it.path.replace('\\', '/').contains("/not_a_file.go/") }
            .sortedBy { it.path }.toList()
        assertTrue("expected goroot test data under $root", files.size > 50)

        val unexpectedClean = mutableListOf<String>()
        val unexpectedErrors = mutableListOf<String>()
        for (file in files) {
            val rel = file.relativeTo(root).path.replace('\\', '/')
            val text = file.readText()
            val psi = createPsiFile(file.nameWithoutExtension, text)
            ensureParsed(psi)
            val error = PsiTreeUtil.findChildOfType(psi, PsiErrorElement::class.java)
            val expectsError = errorMarker.containsMatchIn(text) && rel !in semanticErrorsOnly
            if (expectsError && error == null) unexpectedClean += rel
            if (!expectsError && error != null) {
                unexpectedErrors += "$rel: '${error.errorDescription}' at ${error.textOffset}: ${lineAt(text, error.textOffset)}"
            }
        }
        println("goroot testdata: ${files.size} files, ${unexpectedClean.size} expected errors missing, ${unexpectedErrors.size} unexpected errors")
        unexpectedClean.forEach { println("  no error reported: $it") }
        unexpectedErrors.forEach { println("  unexpected: $it") }
        assertTrue(
            "unexpected errors:\n${unexpectedErrors.joinToString("\n")}\nno error reported:\n${unexpectedClean.joinToString("\n")}",
            unexpectedErrors.isEmpty() && unexpectedClean.isEmpty(),
        )
    }

    private fun lineAt(text: String, offset: Int): String {
        val start = text.lastIndexOf('\n', offset - 1) + 1
        val end = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        return text.substring(start, end).trim()
    }
}
