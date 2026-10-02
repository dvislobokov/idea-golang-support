package io.github.golangsupport.semantic

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.project.GoProjectModelTestBase
import io.github.golangsupport.project.ProjectTestUtil
import io.github.golangsupport.project.api.GoBuildConstraintEvaluator
import io.github.golangsupport.semantic.api.GoSemanticService
import java.nio.file.Files

/**
 * False-positive gate: GOROOT compiles, so every diagnostic `check(file)` reports on a buildable
 * non-test file of `$GOROOT/src` is a false positive. Counts by diagnostic class are recorded in
 * `testData/metrics/goroot-src-check.json` and may only decrease.
 */
class GorootCheckCorpusTest : GoProjectModelTestBase() {

    private companion object {
        const val BUDGET_MS = 20_000L
    }

    override fun setUp() {
        super.setUp()
        com.intellij.openapi.util.RecursionManager.disableAssertOnRecursionPrevention(testRootDisposable)
        com.intellij.openapi.util.RecursionManager.disableMissedCacheAssertions(testRootDisposable)
    }

    /**
     * Spot check on real third-party code: 200 files sampled deterministically from
     * `$GOMODCACHE/golang.org/x`. Modules missing from the cache make many classes noisy, so only
     * `cannot-infer` is gated (it guards turning that class on by default in the IDE).
     */
    fun testCheckSampledGolangOrgX() {
        val root = ProjectTestUtil.gomodcache().resolve("golang.org").resolve("x")
        if (!Files.isDirectory(root)) { println("skipped: no $root"); return }
        val semantic = GoSemanticService.getInstance(project)
        val context = toolchain.buildContext
        val candidates = Files.walk(root).use { s ->
            s.filter { Files.isRegularFile(it) && it.toString().endsWith(".go") && !it.toString().endsWith("_test.go") }
                .filter { p -> root.relativize(p).toString().replace('\\', '/').split('/').none { it == "testdata" || it == "vendor" || it.startsWith(".") || it.startsWith("_") } }
                .sorted().toList()
        }
        val sample = candidates.shuffled(java.util.Random(20261001)).take(200).sorted()
        var files = 0L; var cannotInfer = 0L; var crashed = 0L
        val examples = ArrayList<String>()
        for (path in sample) {
            val vf = LocalFileSystem.getInstance().findFileByNioFile(path) ?: continue
            val text = String(vf.contentsToByteArray(), vf.charset)
            if (!GoBuildConstraintEvaluator.matchFile(vf.name, text, context)) continue
            val psi = PsiManager.getInstance(project).findFile(vf) as? GoFile ?: continue
            if (psi.isCgo) continue
            files++
            val diagnostics = try { semantic.check(psi) } catch (e: Throwable) { if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e; crashed++; emptyList() }
            for (d in diagnostics.filter { it.code == "cannot-infer" }) {
                cannotInfer++
                if (examples.size < 20) examples += "${root.relativize(path).toString().replace('\\', '/')}: ${d.message}"
            }
        }
        println("golang.org/x sampled check: files=$files cannot-infer=$cannotInfer crashed=$crashed")
        examples.forEach { println("  $it") }
        val metrics = linkedMapOf("files" to files, "cannotInfer" to cannotInfer, "crashed" to crashed)
        ProjectTestUtil.checkMetrics(ProjectTestUtil.testDataPath().resolve("metrics/gomodcache-x-sample-check.json"), metrics, informational = setOf("files"))
    }

    fun testCheckGorootSources() {
        val root = ProjectTestUtil.goroot().resolve("src")
        val semantic = GoSemanticService.getInstance(project)
        val context = toolchain.buildContext
        val byClass = sortedMapOf<String, Long>()
        val samples = ArrayList<String>()
        val all = ArrayList<String>()
        val slowest = ArrayList<Pair<Long, String>>()
        var files = 0L
        var total = 0L
        var crashed = 0L
        var timedOut = 0L
        val crashSamples = ArrayList<String>()
        val timeoutSamples = ArrayList<String>()
        val start = System.currentTimeMillis()
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".go") && !it.toString().endsWith("_test.go") }.sorted().forEach { path ->
                val rel = root.relativize(path).toString().replace('\\', '/')
                if (rel.split('/').any { it == "testdata" || it.startsWith(".") || it.startsWith("_") }) return@forEach
                val vf = LocalFileSystem.getInstance().findFileByNioFile(path) ?: return@forEach
                val text = String(vf.contentsToByteArray(), vf.charset)
                if (!GoBuildConstraintEvaluator.matchFile(vf.name, text, context)) return@forEach
                val psi = PsiManager.getInstance(project).findFile(vf) as? GoFile ?: return@forEach
                if (psi.isCgo) return@forEach
                files++
                if (files % 500 == 0L) { println("progress: $files files"); System.out.flush() }
                val t0 = System.currentTimeMillis()
                // Per-file time budget: the platform checks cancellation inside PSI/cache operations.
                val indicator = com.intellij.openapi.progress.util.ProgressIndicatorBase()
                val watchdog = Thread { try { Thread.sleep(BUDGET_MS); indicator.cancel() } catch (_: InterruptedException) {} }.also { it.isDaemon = true; it.start() }
                val diagnostics = try {
                    com.intellij.openapi.progress.ProgressManager.getInstance().runProcess<List<io.github.golangsupport.semantic.api.GoDiagnostic>>({ semantic.check(psi) }, indicator)
                } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
                    timedOut++
                    if (timeoutSamples.size < 20) timeoutSamples += rel
                    emptyList()
                } catch (e: Throwable) {
                    crashed++
                    if (crashSamples.size < 10) crashSamples += "$rel: ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull()}"
                    emptyList()
                }
                watchdog.interrupt()
                val dt = System.currentTimeMillis() - t0
                if (dt > 5000) { println("SLOW check: $rel ${dt} ms"); System.out.flush() }
                if (slowest.size < 10 || dt > slowest.minOf { it.first }) { slowest += dt to rel; if (slowest.size > 10) slowest.remove(slowest.minBy { it.first }) }
                for (d in diagnostics) {
                    all += "$rel:${psi.viewProvider.document?.getLineNumber(d.range.startOffset)?.plus(1)} [${d.code}] ${d.message.lineSequence().first()}"
                    total++
                    byClass.merge(d.code, 1, Long::plus)
                    if (samples.count { it.contains(" [${d.code}] ") } < 6) samples += "$rel:${d.range.startOffset} [${d.code}] ${d.message.lineSequence().first()}"
                }
            }
        }
        val millis = System.currentTimeMillis() - start
        println("""
            |GOROOT/src check corpus
            |  files:        $files
            |  crashed:      $crashed ${crashSamples.joinToString(" | ") { it.take(160) }}
            |  timed out:    $timedOut (budget ${BUDGET_MS / 1000} s) ${timeoutSamples.joinToString(", ")}
            |  diagnostics:  $total (all false positives)
            |  by class:     $byClass
            |  time:         $millis ms (${if (files == 0L) 0 else millis / files} ms per file)
            |  slowest:      ${slowest.sortedByDescending { it.first }.joinToString { "${it.second}=${it.first}ms" }}
            |  samples:
            |${samples.sorted().joinToString("\n") { "    $it" }}
        """.trimMargin())
        // Every diagnostic, for triage: build/reports/goroot-check-diagnostics.txt.
        java.io.File("build/reports").mkdirs()
        java.io.File("build/reports/goroot-check-diagnostics.txt").writeText(all.joinToString("\n", postfix = "\n"))
        val metrics = linkedMapOf("files" to files, "diagnostics" to total, "crashed" to crashed, "timedOut" to timedOut, "millis" to millis)
        byClass.forEach { (k, v) -> metrics["class.$k"] = v }
        ProjectTestUtil.checkMetrics(ProjectTestUtil.testDataPath().resolve("metrics/goroot-src-check.json"), metrics, informational = setOf("files", "millis"))
    }
}
