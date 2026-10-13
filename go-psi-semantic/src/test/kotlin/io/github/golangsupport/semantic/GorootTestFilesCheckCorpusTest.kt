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
 * False-positive gate on test files: `go vet std` passes, so every diagnostic on a buildable `_test.go` file of `$GOROOT/src` is a false
 * positive. [GorootCheckCorpusTest] skips test files, and so missed that methods declared in them were not in the method set of the
 * package's types (8 false errors on prometheus). 400 files sampled deterministically; internal (`package x`) and external
 * (`package x_test`) test files are counted apart. Counts by class are in `testData/metrics/goroot-src-test-check.json` and may only decrease.
 */
class GorootTestFilesCheckCorpusTest : GoProjectModelTestBase() {

    override fun setUp() {
        super.setUp()
        com.intellij.openapi.util.RecursionManager.disableAssertOnRecursionPrevention(testRootDisposable)
        com.intellij.openapi.util.RecursionManager.disableMissedCacheAssertions(testRootDisposable)
    }

    fun testCheckGorootTestFiles() {
        val root = ProjectTestUtil.goroot().resolve("src")
        val semantic = GoSemanticService.getInstance(project)
        val context = toolchain.buildContext
        val candidates = Files.walk(root).use { s ->
            s.filter { Files.isRegularFile(it) && it.toString().endsWith("_test.go") }
                .filter { p -> root.relativize(p).toString().replace('\\', '/').split('/').none { it == "testdata" || it == "vendor" || it.startsWith(".") || it.startsWith("_") } }
                .sorted().toList()
        }
        val sample = candidates.shuffled(java.util.Random(20261013)).take(400).sorted()
        val byClass = sortedMapOf<String, Long>()
        val all = ArrayList<String>()
        var internal = 0L; var external = 0L; var internalDiagnostics = 0L; var externalDiagnostics = 0L; var crashed = 0L
        for (path in sample) {
            val rel = root.relativize(path).toString().replace('\\', '/')
            val vf = LocalFileSystem.getInstance().findFileByNioFile(path) ?: continue
            val text = String(vf.contentsToByteArray(), vf.charset)
            if (!GoBuildConstraintEvaluator.matchFile(vf.name, text, context)) continue
            val psi = PsiManager.getInstance(project).findFile(vf) as? GoFile ?: continue
            if (psi.isCgo) continue
            val isExternal = psi.packageName?.endsWith("_test") == true
            if (isExternal) external++ else internal++
            val diagnostics = try { semantic.check(psi) } catch (e: Throwable) { if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e; crashed++; emptyList() }
            for (d in diagnostics) {
                if (isExternal) externalDiagnostics++ else internalDiagnostics++
                byClass.merge((if (isExternal) "xtest." else "test.") + d.code, 1, Long::plus)
                all += "$rel:${psi.viewProvider.document?.getLineNumber(d.range.startOffset)?.plus(1)} [${d.code}] ${d.message.lineSequence().first()}"
            }
        }
        println("GOROOT/src test files check: internal=$internal ($internalDiagnostics diagnostics), external=$external ($externalDiagnostics), crashed=$crashed")
        println("  by class: $byClass")
        all.take(60).forEach { println("  $it") }
        java.io.File("build/reports").mkdirs()
        java.io.File("build/reports/goroot-test-check-diagnostics.txt").writeText(all.joinToString("\n", postfix = "\n"))
        val metrics = linkedMapOf("internalFiles" to internal, "externalFiles" to external, "internalDiagnostics" to internalDiagnostics,
            "externalDiagnostics" to externalDiagnostics, "crashed" to crashed)
        // checkMetrics reads keys of [A-Za-z0-9_] only
        byClass.forEach { (k, v) -> metrics["class_" + k.replace(Regex("[^A-Za-z0-9]"), "_")] = v }
        ProjectTestUtil.checkMetrics(ProjectTestUtil.testDataPath().resolve("metrics/goroot-src-test-check.json"), metrics, informational = setOf("internalFiles", "externalFiles"))
    }
}
