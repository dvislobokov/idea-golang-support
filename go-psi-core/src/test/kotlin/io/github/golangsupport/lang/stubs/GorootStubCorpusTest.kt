package io.github.golangsupport.lang.stubs

import com.intellij.psi.PsiFileFactory
import io.github.golangsupport.GoStubTestCase
import io.github.golangsupport.GoTestUtil
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.lang.lexer.CorpusMetrics
import io.github.golangsupport.lang.psi.GoFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Builds stubs for every `.go` file under `$GOROOT/src` (skipping `testdata`), serializes and
 * deserializes them and compares the dumps. Metrics: `testData/metrics/goroot-src-stubs.json`
 * (`files`, `stubs` and `stubMillis` are informational; `failures` and `roundTripMismatches` gate).
 */
class GorootStubCorpusTest : GoStubTestCase() {

    fun testGorootStubs() {
        val root = GoTestUtil.goroot().resolve("src")
        assertTrue("corpus root not found: $root", Files.isDirectory(root))
        val files = Files.walk(root).use { stream ->
            stream.filter { it.isRegularFile() && it.extension == "go" && root.relativize(it).none { p -> p.name == "testdata" } }
                .sorted().toList()
        }
        assertTrue("expected > 100 .go files under $root, found ${files.size}", files.size > 100)

        var stubs = 0L
        var buildNanos = 0L
        var failures = 0L
        var mismatches = 0L
        var withoutPsi = 0L
        val reported = mutableListOf<String>()
        for (path in files) {
            val relative = root.relativize(path).toString()
            try {
                val psi = PsiFileFactory.getInstance(project).createFileFromText(path.name, GoLanguage, path.readText())
                if (psi !is GoFile) {
                    // Files above the platform size limit get plain-text PSI (as in the parser corpus gate).
                    withoutPsi++
                    continue
                }
                val file: GoFile = psi
                file.node // parse outside the timed section
                val started = System.nanoTime()
                val stub = buildFromPsi(file)
                buildNanos += System.nanoTime() - started
                stubs += countStubs(stub)
                if (dump(stub) != dump(roundTrip(stub))) {
                    mismatches++
                    if (reported.size < 20) reported += "round trip mismatch: $relative"
                }
            } catch (e: Exception) {
                failures++
                if (reported.size < 20) reported += "$relative: $e"
            }
        }
        val millis = buildNanos / 1_000_000
        println("GOROOT/src stub corpus:")
        println("  files:               ${files.size}")
        println("  stubs:               $stubs")
        println("  stubMillis:          $millis")
        println("  failures:            $failures")
        println("  roundTripMismatches: $mismatches")
        println("  filesWithoutPsi:     $withoutPsi")
        reported.forEach { println("  $it") }

        CorpusMetrics.check(
            Path.of(GoTestUtil.testDataPath("metrics/goroot-src-stubs.json")),
            linkedMapOf(
                "files" to files.size.toLong(),
                "stubs" to stubs,
                "stubMillis" to millis,
                "failures" to failures,
                "roundTripMismatches" to mismatches,
                "filesWithoutPsi" to withoutPsi,
            ),
            informational = setOf("files", "stubs", "stubMillis"),
        )
        assertEquals("stub build failures", 0L, failures)
        assertEquals("serialization round trip mismatches", 0L, mismatches)
    }
}
