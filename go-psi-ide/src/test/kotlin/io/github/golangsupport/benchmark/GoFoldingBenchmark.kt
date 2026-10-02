package io.github.golangsupport.benchmark

import com.intellij.lang.folding.LanguageFolding
import io.github.golangsupport.ide.GoIdeTestBase
import io.github.golangsupport.ide.folding.GoFoldingBuilder

/** Folding region computation for `net/http/server.go` (the file stays parsed), ten passes per iteration. Unit: ms per MB. */
class GoFoldingBenchmark : GoIdeTestBase() {

    fun testServer() {
        val text = BenchmarkSupport.readGoroot("net/http/server.go")
        myFixture.configureByText("server.go", text)
        val file = myFixture.file
        val document = myFixture.editor.document
        val builder = GoFoldingBuilder()
        var regions = 0
        BenchmarkSupport.run("GoFoldingBenchmark.server", "ms/MB", text.length * PASSES / 1_000_000.0) {
            // One pass is a few milliseconds: repeat it to get a stable time.
            repeat(PASSES) { regions = LanguageFolding.buildFoldingDescriptors(builder, file, document, false).size }
        }
        assertTrue("no folding regions", regions > 100)
    }

    private companion object {
        const val PASSES = 10
    }
}
