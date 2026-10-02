package io.github.golangsupport.benchmark

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.util.SimpleModificationTracker
import io.github.golangsupport.benchmark.EditingBenchmarkSupport.ToggleEdit
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.ide.inspections.GoInspectionClasses
import io.github.golangsupport.semantic.cache.GoTrackers

/**
 * The whole highlighting of a file as the editor runs it: `myFixture.doHighlighting()` (all
 * daemon passes: syntax, the semantic annotator, every go-psi inspection) on a copy of
 * `net/http/server.go` inside a copy of its package (non-test files of `net/http` in the light
 * project, linux/amd64):
 *
 * - `cold`: the Go trackers are bumped and the daemon restarted before every iteration (whole
 *   file re-highlighted, all semantic caches discarded).
 * - `warm`: the daemon restarted without a change (whole file re-highlighted on warm caches).
 * - `afterBodyEdit`: `_ = 0` toggled at the start of `(*conn).serve` before every iteration; the
 *   daemon re-runs what the edit made dirty, as in the editor.
 * - `afterTopLevelEdit`: a package-level `var` toggled before `(*conn).serve`.
 *
 * Edits and commits are untimed. Unit: ms per highlighting. The after-edit cases are dominated by
 * the semantic caches the edit invalidated: targets per-function-body inference and per-package
 * trackers.
 */
class GoHighlightingPassBenchmark : GoSemanticIdeTestBase() {

    override fun setUp() {
        super.setUp()
        EditingBenchmarkSupport.copyGorootPackage(myFixture, "net/http", "nethttp")
        myFixture.configureFromTempProjectFile("nethttp/server.go")
        myFixture.enableInspections(*GoInspectionClasses.ALL.map { it.getDeclaredConstructor().newInstance() }.toTypedArray())
    }

    fun testCold() {
        val trackers = GoTrackers.getInstance(project)
        measure("cold") {
            trackers.invalidateAll()
            trackers.anyGoChange.incModificationCount()
            (trackers.forFile(myFixture.file) as SimpleModificationTracker).incModificationCount()
            restartDaemon()
        }
    }

    fun testWarm() = measure("warm") { restartDaemon() }

    /** Marks the whole file dirty: without a change the daemon would keep the previous results. */
    private fun restartDaemon() = DaemonCodeAnalyzer.getInstance(project).restart(myFixture.file)

    fun testAfterBodyEdit() {
        val edit = ToggleEdit(project, myFixture.file, SERVE, EditingBenchmarkSupport.bodyStart(myFixture.file.text, SERVE), "\t_ = 0\n")
        measure("afterBodyEdit") { edit.toggle() }
        edit.reset()
    }

    fun testAfterTopLevelEdit() {
        val edit = ToggleEdit(project, myFixture.file, "// Serve a new connection.\n", 0, "var benchEditVar = 1\n\n")
        measure("afterTopLevelEdit") { edit.toggle() }
        edit.reset()
    }

    private fun measure(case: String, prepare: () -> Unit) {
        var problems = 0
        BenchmarkSupport.run("GoHighlightingPassBenchmark.$case", "ms", 1.0, prepare = { prepare() }) {
            problems = myFixture.doHighlighting().count { it.description != null && it.severity.myVal >= com.intellij.lang.annotation.HighlightSeverity.WARNING.myVal }
        }
        println("  $case: $problems warnings/errors in the copied server.go")
    }

    private companion object {
        const val SERVE = "func (c *conn) serve(ctx context.Context) {"
    }
}
