package io.github.golangsupport.benchmark

import com.intellij.openapi.application.WriteAction
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import io.github.golangsupport.GoCodeInsightTestBase
import io.github.golangsupport.lang.stubs.index.GoAllPublicNamesIndex

/**
 * Index update and query on a light project holding copies of `$GOROOT/src/strings` and `bytes`:
 * every iteration replaces the copy of both packages (untimed), then the timed block forces the
 * indexing of the new files by querying all keys of [GoAllPublicNamesIndex] and looking each up.
 */
class GoIndexBenchmark : GoCodeInsightTestBase() {

    fun testStringsAndBytes() {
        val sources = BenchmarkSupport.goFilesIn("strings").map { "strings" to it } + BenchmarkSupport.goFilesIn("bytes").map { "bytes" to it }
        val index = GoAllPublicNamesIndex()
        val scope = GlobalSearchScope.allScope(project)
        var hits = 0
        BenchmarkSupport.run(
            "GoIndexBenchmark.stringsBytes",
            "ms",
            1.0,
            prepare = { i ->
                // Keep the index size constant: drop the previous copy.
                myFixture.findFileInTempDir("copy${i - 1}")?.let { dir -> WriteAction.runAndWait<RuntimeException> { dir.delete(this) } }
                for ((dir, file) in sources) myFixture.addFileToProject("copy$i/$dir/${file.first}", file.second)
            },
        ) {
            val stubIndex = StubIndex.getInstance()
            hits = 0
            for (key in stubIndex.getAllKeys(index.key, project)) hits += index.find(key, project, scope).size
        }
        assertTrue("index returned nothing", hits > 0)
    }
}
