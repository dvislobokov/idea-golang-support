package io.github.golangsupport.semantic

import io.github.golangsupport.project.ProjectTestUtil
import java.io.File

/**
 * Every single-file test of `$GOROOT/src/internal/types/testdata` (check, fixedbugs, examples, spec), read in place: [GoTypesTestdataTest]
 * holds byte-identical copies of 51 of them as a strict gate, this one measures the whole suite of go/types with the same `/* ERROR */`
 * protocol. Package directories (several files of one package) are not checked. Sites, matches and false positives are in
 * `testData/metrics/goroot-types-testdata.json`: matches may only grow, false positives and misses only shrink. Every site:
 * build/reports/goroot-types-testdata.txt.
 */
class GorootTypesTestdataCorpusTest : GoErrorSiteTestBase() {

    fun testGorootTypesTestdata() {
        val root = ProjectTestUtil.goroot().resolve("src/internal/types/testdata").toFile()
        val files = root.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }
            .flatMap { dir -> dir.listFiles().orEmpty().filter { it.isFile && it.extension == "go" }.sortedBy { it.name } }
        val results = files.map { f ->
            val rel = f.relativeTo(root).path.replace('\\', '/')
            try { checkFile(f, rel, emptyMap(), "gt_" + rel.removeSuffix(".go").replace('/', '_')) }
            catch (e: Throwable) {
                if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
                FileResult(rel, 0, 0, 0, emptyList(), emptyList(), 0, skipped = "crashed: ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull()}")
            }
        }
        val text = report(results)
        File("build/reports").mkdirs()
        File("build/reports/goroot-types-testdata.txt").writeText(text)
        val checked = results.filter { it.skipped == null }
        val crashed = results.count { it.skipped?.startsWith("crashed") == true }
        println(text.lineSequence().first { it.startsWith("TOTAL") } + " files=${checked.size} skipped=${results.size - checked.size} crashed=$crashed")
        val metrics = linkedMapOf(
            "files" to checked.size.toLong(), "sites" to checked.sumOf { it.sites }.toLong(),
            "missed" to checked.sumOf { it.missed.size }.toLong(), "falsePositives" to checked.sumOf { it.falsePositives.size }.toLong(),
            "crashed" to crashed.toLong(),
        )
        ProjectTestUtil.checkMetrics(ProjectTestUtil.testDataPath().resolve("metrics/goroot-types-testdata.json"), metrics, informational = setOf("files", "sites"))
    }
}
