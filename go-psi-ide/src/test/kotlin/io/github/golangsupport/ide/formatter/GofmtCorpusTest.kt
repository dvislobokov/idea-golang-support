package io.github.golangsupport.ide.formatter

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.codeStyle.CodeStyleManager
import io.github.golangsupport.ide.GoIdeTestBase
import io.github.golangsupport.ide.formatter.printer.GoLayout
import io.github.golangsupport.ide.formatter.printer.GoPrinterMismatch
import io.github.golangsupport.lang.GoFileType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * gofmt parity gate: every `.go` file under `$GOROOT/src` is gofmt-clean, so reformatting it must
 * leave it byte-for-byte unchanged. Skips `testdata` directories, files over 200 KB and, unless
 * `-Dgopsi.formatter.corpus.includeCmd=true`, `src/cmd`. `-Dgopsi.formatter.corpus.mode=layout`
 * checks the printer layout only (faster, no formatting engine); the default `reformat` runs
 * Reformat Code through `CodeStyleManager`. Records `testData/metrics/goroot-src-gofmt.json`.
 */
class GofmtCorpusTest : GoIdeTestBase() {

    fun testGorootSrcIsGofmtStable() {
        // write commands log at FINE in the test environment; thousands of them flood the output
        java.util.logging.Logger.getLogger("").let { root ->
            root.level = java.util.logging.Level.INFO
            root.handlers.forEach { it.level = java.util.logging.Level.INFO }
        }
        val goroot = Path.of(System.getProperty("gopsi.goroot") ?: "C:\\Program Files\\Go")
        val root = goroot.resolve("src")
        assertTrue("GOROOT/src not found: $root", Files.isDirectory(root))
        val includeCmd = System.getProperty("gopsi.formatter.corpus.includeCmd").toBoolean()
        val mode = System.getProperty("gopsi.formatter.corpus.mode") ?: "reformat"
        val filter = System.getProperty("gopsi.formatter.corpus.filter")?.takeIf { it.isNotBlank() }
        val maxReported = System.getProperty("gopsi.formatter.corpus.report")?.toIntOrNull() ?: 30
        val files = Files.walk(root).use { stream ->
            stream.filter { p ->
                p.isRegularFile() && p.extension == "go" &&
                    root.relativize(p).none { it.name == "testdata" } &&
                    (includeCmd || !root.relativize(p).startsWith("cmd")) &&
                    Files.size(p) <= 200 * 1024 &&
                    (filter == null || root.relativize(p).toString().replace('\\', '/').contains(filter))
            }.sorted().toList()
        }
        assertTrue("expected .go files under $root", files.isNotEmpty())

        // GOROOT is almost, not entirely, gofmt-clean: for the files `gofmt -l` lists, the
        // expected text is gofmt's output instead of the original
        val gofmt = GofmtRunner.find(goroot)
        val unclean = gofmt?.listUnformatted(root) ?: emptySet()
        println("  gofmt: ${gofmt?.executable ?: "not found"}, ${unclean.size} files not gofmt-clean")

        var identical = 0L
        var different = 0L
        val reported = ArrayList<String>()
        val started = System.nanoTime()
        for (file in files) {
            val text = file.readText().replace("\r\n", "\n")
            val rel = root.relativize(file).toString().replace('\\', '/')
            val expected = if (file.toAbsolutePath().normalize() in unclean) gofmt!!.format(text) else text
            val result = try {
                format(file.name, text, mode)
            } catch (e: GoPrinterMismatch) {
                "<printer: ${e.message}>"
            }
            if (result == expected) {
                identical++
            } else {
                different++
                if (reported.size < maxReported) reported += "$rel: ${firstDifference(expected, result)}"
            }
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        println("GOROOT/src gofmt corpus ($mode):")
        println("  files:     ${files.size}")
        println("  identical: $identical")
        println("  different: $different")
        println("  time:      $millis ms")
        reported.forEach { println("  $it") }
        if (filter == null && mode == "reformat") {
            val metrics = linkedMapOf("files" to files.size.toLong(), "identical" to identical, "different" to different, "millis" to millis)
            FormatterCorpusMetrics.check(Path.of(testDataRoot(), "metrics", "goroot-src-gofmt.json"), metrics, setOf("files", "identical", "millis"))
        }
    }

    private fun format(name: String, text: String, mode: String): String {
        val psi = PsiFileFactory.getInstance(project).createFileFromText(name, GoFileType, text, System.currentTimeMillis(), true)
        if (mode == "layout") return GoLayout.computeOrThrow(psi.node).text
        WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformat(psi) }
        return psi.text
    }

    private fun firstDifference(expected: String, actual: String): String {
        if (actual.startsWith("<printer:")) return actual
        val e = expected.split('\n')
        val a = actual.split('\n')
        for (i in 0 until maxOf(e.size, a.size)) {
            val el = e.getOrNull(i)
            val al = a.getOrNull(i)
            if (el != al) return "line ${i + 1}: expected ${show(el)} got ${show(al)}"
        }
        return "?"
    }

    private fun show(s: String?): String = s?.replace("\t", "\\t")?.let { "\"${it.take(100)}\"" } ?: "<eof>"
}

/** Lower-is-better metrics file (a copy of core's CorpusMetrics, whose test classes are not visible here). */
internal object FormatterCorpusMetrics {
    private val entry = Regex("\"([A-Za-z0-9_]+)\"\\s*:\\s*(-?\\d+)")

    fun check(file: Path, actual: LinkedHashMap<String, Long>, informational: Set<String>) {
        if (!Files.exists(file)) {
            write(file, actual)
            println("  metrics: created $file")
            return
        }
        val accepted = entry.findAll(Files.readString(file)).associate { it.groupValues[1] to it.groupValues[2].toLong() }
        val regressions = actual.filter { (k, v) -> k !in informational && accepted[k] != null && v > accepted.getValue(k) }
        if (regressions.isNotEmpty()) {
            junit.framework.TestCase.fail("Corpus metrics regressed in $file: " + regressions.entries.joinToString { (k, v) -> "$k ${accepted[k]} -> $v" })
        }
        if (actual != accepted) {
            write(file, actual)
            println("  metrics: updated $file (was $accepted)")
        } else {
            println("  metrics: unchanged $file")
        }
    }

    private fun write(file: Path, values: Map<String, Long>) {
        Files.createDirectories(file.parent)
        Files.writeString(file, values.entries.joinToString(",", "{", "}\n") { (k, v) -> "\"$k\":$v" })
    }
}
