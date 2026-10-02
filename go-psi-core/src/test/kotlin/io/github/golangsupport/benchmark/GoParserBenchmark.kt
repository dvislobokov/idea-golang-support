package io.github.golangsupport.benchmark

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFileFactory
import io.github.golangsupport.GoCodeInsightTestBase
import io.github.golangsupport.lang.GoLanguage

/**
 * Full parse of `net/http/server.go` and every `.go` file of go/types (ms per MB), and the
 * incremental re-parse after a one-character edit inside a function body (ms).
 */
class GoParserBenchmark : GoCodeInsightTestBase() {

    fun testServer() = parse("GoParserBenchmark.server", listOf("server.go" to BenchmarkSupport.readGoroot("net/http/server.go")))

    fun testGoTypes() = parse("GoParserBenchmark.gotypes", BenchmarkSupport.goFilesIn("go/types"))

    private fun parse(name: String, files: List<Pair<String, String>>) {
        val megabytes = files.sumOf { it.second.length } / 1_000_000.0
        BenchmarkSupport.run(name, "ms/MB", megabytes) {
            for ((fileName, text) in files) {
                // `node` forces the full parse (the platform parses lazily on first access).
                PsiFileFactory.getInstance(project).createFileFromText(fileName, GoLanguage, text).node
            }
        }
    }

    fun testServerIncrementalReparse() {
        val text = BenchmarkSupport.readGoroot("net/http/server.go")
        // A position inside the body of `func (c *conn) serve`: right after the first `{` on the
        // line that follows its signature.
        val signature = text.indexOf("func (c *conn) serve(ctx context.Context) {")
        assertTrue("anchor not found", signature >= 0)
        val offset = text.indexOf('{', signature) + 1
        myFixture.configureByText("server.go", text)
        val document = myFixture.editor.document
        val manager = PsiDocumentManager.getInstance(project)
        manager.commitAllDocuments()
        myFixture.file.node // initial full parse
        var inserted = false
        BenchmarkSupport.run("GoParserBenchmark.serverIncrementalReparse", "ms", 1.0) {
            // Toggle the edit so that the document stays the same size across iterations. The edit
            // itself is a few microseconds; the commit performs the incremental re-parse.
            WriteCommandAction.runWriteCommandAction(project) {
                if (inserted) document.deleteString(offset, offset + 1) else document.insertString(offset, " ")
                inserted = !inserted
                manager.commitDocument(document)
            }
            myFixture.file.node
        }
    }
}
