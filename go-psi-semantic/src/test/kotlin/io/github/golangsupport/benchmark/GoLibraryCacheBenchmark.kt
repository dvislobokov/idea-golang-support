package io.github.golangsupport.benchmark

import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.benchmark.EditingBenchmarkSupport.ToggleEdit
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.types.GoUnknownType

/**
 * Library caches versus project edits. `typeOf` of every expression of three GOROOT files opened
 * through the VFS (`net/http/client.go`, `fmt/print.go`, `go/types/call.go`) and of a project file
 * using `net/http`, `fmt` and `go/types` is warmed, then:
 *
 * - `warm`: re-typing without an edit (20 passes per iteration, unit ms per pass).
 * - `afterProjectTopLevelEdit`: a field toggled in a struct of an unrelated project package
 *   (`edit/edit.go`), then one pass. Library caches depend only on library edits and the
 *   project model, and `use` does not import `edit`, so this stays close to `warm`.
 * - `afterProjectBodyEdit`: a statement toggled inside a function body of the same project file,
 *   then one pass (ten edits per iteration, unit ms per edit).
 *
 * A report line prints the ratios to `warm` (target: both close to 1).
 */
class GoLibraryCacheBenchmark : GoSemanticBenchmarkBase() {

    private lateinit var expressions: List<GoExpression>
    private lateinit var edited: GoFile
    private val semantic get() = GoSemanticService.getInstance(project)

    override fun setUp() {
        super.setUp()
        val use = myFixture.addFileToProject("use/use.go", USE) as GoFile
        edited = myFixture.addFileToProject("edit/edit.go", EDIT) as GoFile
        val files = LIBRARY.map(::gorootFile) + use
        expressions = files.flatMap { PsiTreeUtil.findChildrenOfType(it, GoExpression::class.java) }
        val known = expressions.count { semantic.typeOf(it) !is GoUnknownType }
        assertTrue("only $known of ${expressions.size} expressions have a type", known > expressions.size * 0.5)
        assertTrue("project file must be clean: ${semantic.check(use).take(5)}", semantic.check(use).isEmpty())
    }

    private fun typeAll() {
        for (e in expressions) semantic.typeOf(e)
    }

    fun testAll() {
        val warm = BenchmarkSupport.run("GoLibraryCacheBenchmark.warm", "ms/pass", WARM_PASSES.toDouble()) { repeat(WARM_PASSES) { typeAll() } }

        val topLevel = ToggleEdit(project, edited, "\tA int\n", "\tA int\n".length, "\tB string\n")
        val afterTopLevel = BenchmarkSupport.run("GoLibraryCacheBenchmark.afterProjectTopLevelEdit", "ms/pass", 1.0, prepare = { topLevel.toggle() }) { typeAll() }
        topLevel.reset()

        val body = ToggleEdit(project, edited, "func touch() {\n", "func touch() {\n".length, "\t_ = 0\n")
        val afterBody = BenchmarkSupport.runTimed("GoLibraryCacheBenchmark.afterProjectBodyEdit", "ms/edit", EDITS.toDouble()) {
            var total = 0.0
            repeat(EDITS) {
                body.toggle()
                total += BenchmarkSupport.timed { typeAll() }
            }
            total
        }
        body.reset()

        BenchmarkSupport.report(
            "GoLibraryCacheBenchmark.ratio",
            "expressions=${expressions.size} afterProjectTopLevelEdit/warm=${BenchmarkSupport.format(afterTopLevel.value / warm.value)} " +
                "afterProjectBodyEdit/warm=${BenchmarkSupport.format(afterBody.value / warm.value)}",
        )
    }

    private companion object {
        const val WARM_PASSES = 20
        const val EDITS = 10
        val LIBRARY = listOf("net/http/client.go", "fmt/print.go", "go/types/call.go")

        val USE = """
            |package use
            |
            |import (
            |	"fmt"
            |	"go/token"
            |	"go/types"
            |	"net/http"
            |	"strings"
            |)
            |
            |func Fetch(c *http.Client, url string) (int, error) {
            |	req, err := http.NewRequest(http.MethodGet, url, nil)
            |	if err != nil {
            |		return 0, err
            |	}
            |	req.Header.Set("Accept", "text/plain")
            |	resp, err := c.Do(req)
            |	if err != nil {
            |		return 0, fmt.Errorf("fetch %s: %w", req.URL.Host, err)
            |	}
            |	defer resp.Body.Close()
            |	return resp.StatusCode, nil
            |}
            |
            |func Describe(pkg *types.Package, fset *token.FileSet) string {
            |	var b strings.Builder
            |	for _, name := range pkg.Scope().Names() {
            |		obj := pkg.Scope().Lookup(name)
            |		fmt.Fprintf(&b, "%s %s %s\n", fset.Position(obj.Pos()), obj.Name(), types.TypeString(obj.Type(), types.RelativeTo(pkg)))
            |	}
            |	return b.String()
            |}
            |
        """.trimMargin()

        val EDIT = """
            |package edit
            |
            |type T struct {
            |	A int
            |}
            |
            |func touch() {
            |	_ = T{}
            |}
            |
        """.trimMargin()
    }
}
