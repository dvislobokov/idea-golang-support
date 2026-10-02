package io.github.golangsupport.lang.parser

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.GoParsingTestCase
import io.github.golangsupport.GoTestUtil
import io.github.golangsupport.lang.lexer.CorpusMetrics
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Parses every `.go` file under a corpus root (skipping `testdata` directories), prints the first
 * files with errors and compares the counts with the metrics file.
 */
abstract class GoParseCorpusTestBase : GoParsingTestCase("parser") {

    protected fun runCorpus(corpusName: String, root: Path, metricsFile: String, maxReported: Int = 30) {
        assertTrue("corpus root not found: $root", Files.isDirectory(root))
        val files = Files.walk(root).use { stream ->
            stream.filter { it.isRegularFile() && it.extension == "go" && !it.hasTestdataDir(root) }.sorted().toList()
        }
        assertTrue("expected > 100 .go files under $root, found ${files.size}", files.size > 100)

        var filesWithErrors = 0L
        var errorElements = 0L
        var chars = 0L
        val reported = mutableListOf<String>()
        val unparsed = mutableListOf<String>()
        val started = System.nanoTime()
        for (file in files) {
            val text = file.readText()
            chars += text.length
            val psi = createPsiFile(file.name, text)
            if (psi == null) {
                unparsed += root.relativize(file).toString()
                continue
            }
            ensureParsed(psi)
            assertEquals("PSI text differs for $file", text, psi.text)
            val errors = PsiTreeUtil.findChildrenOfType(psi, PsiErrorElement::class.java)
            if (errors.isNotEmpty()) {
                filesWithErrors++
                errorElements += errors.size
                if (reported.size < maxReported) {
                    val first = errors.first()
                    reported += "${root.relativize(file)}:${first.textOffset}: '${first.errorDescription}' | ${lineAt(text, first.textOffset)}"
                }
            }
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        println("$corpusName parse corpus:")
        println("  files:             ${files.size}")
        println("  chars:             $chars")
        println("  filesWithErrors:   $filesWithErrors")
        println("  errorElements:     $errorElements")
        println("  time:              $millis ms (${if (chars > 0) millis * 1_000_000 / chars else 0} ms/MB)")
        reported.forEach { println("  $it") }
        if (unparsed.isNotEmpty()) {
            println("  files without PSI (createPsiFile returned null): ${unparsed.size}")
            unparsed.take(10).forEach { println("    $it") }
        }

        val metrics = linkedMapOf(
            "files" to files.size.toLong(),
            "filesWithErrors" to filesWithErrors,
            "errorElements" to errorElements,
            "parseMillis" to millis,
            "filesWithoutPsi" to unparsed.size.toLong(),
        )
        CorpusMetrics.check(
            Path.of(GoTestUtil.testDataPath("metrics/$metricsFile")),
            metrics,
            informational = setOf("files", "parseMillis"),
        )
    }

    private fun Path.hasTestdataDir(root: Path): Boolean =
        root.relativize(this).any { it.name == "testdata" }

    private fun lineAt(text: String, offset: Int): String {
        val start = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)) + 1
        val end = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        return text.substring(start, end).trim().take(120)
    }
}

/** `$GOROOT/src` must parse without error elements. */
class GorootParseCorpusTest : GoParseCorpusTestBase() {
    fun testParseGorootSources() =
        runCorpus("GOROOT/src", GoTestUtil.goroot().resolve("src"), "goroot-src-parser.json")
}

/** `$GOMODCACHE/golang.org/x` (tools, net, text, exp, ...) must parse without error elements. */
class GomodcacheParseCorpusTest : GoParseCorpusTestBase() {
    fun testParseGolangOrgX() =
        runCorpus("GOMODCACHE/golang.org/x", GoTestUtil.gomodcache().resolve("golang.org/x"), "gomodcache-golang-org-x-parser.json")
}
