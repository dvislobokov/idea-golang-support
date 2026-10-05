package io.github.golangsupport.benchmark

import com.intellij.codeInsight.hints.declarative.InlayProviderPassInfo
import com.intellij.codeInsight.hints.declarative.impl.DeclarativeInlayHintsPass
import com.intellij.codeInsight.multiverse.codeInsightContext
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.SimpleModificationTracker
import io.github.golangsupport.benchmark.EditingBenchmarkSupport.ToggleEdit
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.ide.hints.GoConstantValueHintsProvider
import io.github.golangsupport.ide.hints.GoLiteralFieldHintsProvider
import io.github.golangsupport.ide.hints.GoParameterNameHintsProvider
import io.github.golangsupport.ide.hints.GoStructSizeHintsProvider
import io.github.golangsupport.ide.hints.GoTypeHintsProvider
import io.github.golangsupport.semantic.cache.GoTrackers

/**
 * The inlay hints of a large file as the editor collects them: the declarative hints pass of the platform with every Go provider and
 * every option on, over a copy of `net/http/server.go` inside a copy of its package (non-test files of `net/http`, linux/amd64). Only the
 * collection is timed (the pass's `doCollectInformation`, background work), not the editor update.
 *
 * - `cold`: the Go trackers bumped before every iteration: every type of every `:=` inferred again, the cost of the first pass on a file.
 * - `warm`: nothing changed: the hints read the per-body inference caches (what a pass costs after an edit in another file).
 * - `afterBodyEdit`: `_ = 0` toggled at the start of `(*conn).serve`: one body inferred again, the others read from the cache.
 *
 * Unit: ms per pass. The hint count is printed to show the work is real.
 */
class GoInlayHintsBenchmark : GoSemanticIdeTestBase() {

    override fun setUp() {
        super.setUp()
        EditingBenchmarkSupport.copyGorootPackage(myFixture, "net/http", "nethttp")
        myFixture.configureFromTempProjectFile("nethttp/server.go")
    }

    private val options = mapOf(GoTypeHintsProvider.ASSIGN to true, GoTypeHintsProvider.RANGE to true, GoTypeHintsProvider.LITERAL to true, GoTypeHintsProvider.INSTANTIATION to true, GoParameterNameHintsProvider.RETURN to true)

    private val providers = listOf(GoParameterNameHintsProvider(), GoLiteralFieldHintsProvider(), GoTypeHintsProvider(), GoConstantValueHintsProvider(), GoStructSizeHintsProvider())

    fun testCold() {
        val trackers = GoTrackers.getInstance(project)
        measure("cold") {
            trackers.invalidateAll()
            trackers.anyGoChange.incModificationCount()
            (trackers.forFile(myFixture.file) as SimpleModificationTracker).incModificationCount()
        }
    }

    fun testWarm() = measure("warm") {}

    fun testAfterBodyEdit() {
        val edit = ToggleEdit(project, myFixture.file, SERVE, EditingBenchmarkSupport.bodyStart(myFixture.file.text, SERVE), "\t_ = 0\n")
        measure("afterBodyEdit") { edit.toggle() }
        edit.reset()
    }

    private fun measure(case: String, prepare: () -> Unit) {
        var hints = 0
        BenchmarkSupport.runTimed("GoInlayHintsBenchmark.$case", "ms", 1.0, prepare = { prepare() }) {
            val pass = ActionUtil.underModalProgress(project, "") {
                DeclarativeInlayHintsPass(myFixture.file, myFixture.editor, providers.mapIndexed { i, p -> InlayProviderPassInfo(p, "go.bench.$i", options) }, isPreview = false)
            }
            pass.setContext(myFixture.file.codeInsightContext)
            val ms = BenchmarkSupport.timed { ActionUtil.underModalProgress(project, "") { pass.doCollectInformation(EmptyProgressIndicator()) } }
            pass.applyInformationToEditor()
            hints = myFixture.editor.inlayModel.getInlineElementsInRange(0, myFixture.editor.document.textLength).size +
                myFixture.editor.inlayModel.getAfterLineEndElementsInRange(0, myFixture.editor.document.textLength).size
            ms
        }
        println("  $case: $hints hints in the copied server.go")
    }

    private companion object {
        const val SERVE = "func (c *conn) serve(ctx context.Context) {"
    }
}
