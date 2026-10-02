package io.github.golangsupport.benchmark

import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.benchmark.EditingBenchmarkSupport.ToggleEdit
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.semantic.api.GoSemanticService

/**
 * Cost of an edit inside a function body for the checker. The non-test files of `net/http` are
 * copied into the light project (one package), `check(server.go)` is warmed, then:
 *
 * - `warmCheck`: `check(server.go)` without an edit (the reference).
 * - `bodyEditCheck`: `_ = 0` toggled at the start of `(*conn).serve`, then `check(server.go)`.
 *   Only the body store of `serve` is dropped (per-function body caches, `GoBodyCache`).
 * - `bodyEditTypeOfOtherFunction`: the same edit, then `typeOf` of every expression of
 *   `(*response).WriteHeader` (another function of the same file); ten edits per iteration,
 *   unit ms per edit. Its body store survives the edit.
 * - `otherFileBodyEditCheck`: the edit toggled in `(*Request).Context` of `request.go`, then
 *   `check(server.go)`: the cost for an unchanged file of the same package (should stay warm).
 *
 * Body edits bump only the edited function's body tracker; package and library caches stay (`GoTrackers`).
 */
class GoBodyEditRehighlightBenchmark : GoSemanticBenchmarkBase() {

    private lateinit var server: GoFile
    private lateinit var request: GoFile
    private val semantic get() = GoSemanticService.getInstance(project)

    override fun setUp() {
        super.setUp()
        val files = EditingBenchmarkSupport.copyGorootPackage(myFixture, "net/http", "nethttp")
        server = files.getValue("server.go") as GoFile
        request = files.getValue("request.go") as GoFile
    }

    private fun serverBodyEdit() = ToggleEdit(project, server, SERVE, EditingBenchmarkSupport.bodyStart(server.text, SERVE), "\t_ = 0\n")

    fun testWarmCheck() {
        var diagnostics = 0
        BenchmarkSupport.run("GoBodyEditRehighlightBenchmark.warmCheck", "ms", 1.0) { diagnostics = semantic.check(server).size }
        println("  diagnostics in the copied server.go: $diagnostics")
    }

    fun testBodyEditCheck() {
        val edit = serverBodyEdit()
        semantic.check(server)
        BenchmarkSupport.run("GoBodyEditRehighlightBenchmark.bodyEditCheck", "ms", 1.0, prepare = { edit.toggle() }) { semantic.check(server) }
        edit.reset()
    }

    fun testBodyEditTypeOfOtherFunction() {
        val edit = serverBodyEdit()
        semantic.check(server)
        val expressions = {
            val f = PsiTreeUtil.getChildrenOfTypeAsList(server, GoFunctionOrMethodDeclaration::class.java)
                .single { it.text.contains(WRITE_HEADER) }
            PsiTreeUtil.findChildrenOfType(f.block, GoExpression::class.java).toList()
        }
        assertTrue("too few expressions", expressions().size > 50)
        BenchmarkSupport.runTimed("GoBodyEditRehighlightBenchmark.bodyEditTypeOfOtherFunction", "ms/edit", EDITS.toDouble()) {
            var total = 0.0
            repeat(EDITS) {
                edit.toggle()
                val exprs = expressions() // PSI of other functions survives the re-parse; looked up untimed
                total += BenchmarkSupport.timed { for (e in exprs) semantic.typeOf(e) }
            }
            total
        }
        edit.reset()
    }

    fun testOtherFileBodyEditCheck() {
        val edit = ToggleEdit(project, request, CONTEXT, EditingBenchmarkSupport.bodyStart(request.text, CONTEXT), "\t_ = 0\n")
        semantic.check(server)
        BenchmarkSupport.run("GoBodyEditRehighlightBenchmark.otherFileBodyEditCheck", "ms", 1.0, prepare = { edit.toggle() }) { semantic.check(server) }
        edit.reset()
    }

    private companion object {
        const val SERVE = "func (c *conn) serve(ctx context.Context) {"
        const val WRITE_HEADER = "func (w *response) WriteHeader(code int) {"
        const val CONTEXT = "func (r *Request) Context() context.Context {"
        const val EDITS = 10
    }
}
