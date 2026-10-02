package io.github.golangsupport.lang.parser

import io.github.golangsupport.GoTestUtil
import io.github.golangsupport.lang.lexer.CorpusMetrics
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.random.Random

/**
 * Fuzz gate over a deterministic sample of [FILES] files of `$GOROOT/src` (fixed seed, `testdata`
 * skipped, files over [MAX_BYTES] skipped), [MUTATIONS] mutations each; see [GoParserFuzzTestBase]
 * for the mutations and assertions. Metrics: `testData/metrics/goroot-src-fuzz.json`.
 */
class GoParserFuzzCorpusTest : GoParserFuzzTestBase() {

    fun testGorootSample() {
        val root = GoTestUtil.goroot().resolve("src")
        assertTrue("GOROOT/src not found: $root", Files.isDirectory(root))
        val candidates = Files.walk(root).use { s ->
            s.filter {
                it.isRegularFile() && it.extension == "go" && root.relativize(it).none { p -> p.name == "testdata" } &&
                    Files.size(it) <= MAX_BYTES
            }.sorted().toList()
        }
        assertTrue("expected > 1000 files", candidates.size > 1000)
        val sample = candidates.shuffled(Random(SEED)).take(FILES).sorted()
        val sources = sample.map { root.relativize(it).toString().replace(File.separatorChar, '/') to it.readText() }

        val started = System.nanoTime()
        val result = fuzz(sources, MUTATIONS, SEED)
        println("fuzz time: ${(System.nanoTime() - started) / 1_000_000} ms")
        printSummary("GoParserFuzzCorpusTest", result)

        CorpusMetrics.check(
            Path.of(GoTestUtil.testDataPath("metrics/goroot-src-fuzz.json")),
            linkedMapOf(
                "files" to result.files.toLong(),
                "mutants" to result.mutants.toLong(),
                "hardFailures" to result.failures.size.toLong(),
                "localityViolations" to result.violations.size.toLong(),
                "maxParseMillis" to result.maxParseMillis,
            ),
            informational = setOf("files", "mutants", "maxParseMillis"),
        )
        assertNoHardFailures(result)
    }

    private companion object {
        const val SEED = 20260930L
        const val FILES = 300
        const val MUTATIONS = 20
        const val MAX_BYTES = 150_000L
    }
}
