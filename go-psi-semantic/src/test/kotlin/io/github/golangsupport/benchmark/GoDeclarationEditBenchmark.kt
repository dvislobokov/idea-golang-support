package io.github.golangsupport.benchmark

import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.benchmark.EditingBenchmarkSupport.ToggleEdit
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.types.GoUnknownType

/**
 * Cost of a top-level declaration edit for the other files of the package. A synthetic package of
 * three files ([a] declares `Config`, `Item*`, `Helper*`; [c] declares `Server`; [b] uses both
 * plus `fmt`, `strings`, `sort`, `bytes`, `net/http`) is checked warm, then a field is toggled in
 * `Config` of `a.go` (an out-of-block change):
 *
 * - `warmCheckOtherFile` / `declEditCheckOtherFile`: `check(b.go)` without / after the edit.
 * - `warmLibraryExprs` / `declEditLibraryExprs`: `typeOf` of every expression of the `Lib*`
 *   functions of `b.go`, which only use GOROOT packages (`strings.Split(...)`, ...), without /
 *   after the edit. The expressions themselves are in the edited package (recomputed), but the
 *   GOROOT caches they use stay warm.
 * - `unrelatedDeclEditCheckOtherFile`: `check(b.go)` after a field toggle in a project package
 *   that `app` does not import (`other/o.go`): per-package trackers keep it close to warm.
 */
class GoDeclarationEditBenchmark : GoSemanticBenchmarkBase() {

    private lateinit var a: GoFile
    private lateinit var b: GoFile
    private val semantic get() = GoSemanticService.getInstance(project)

    override fun setUp() {
        super.setUp()
        a = myFixture.addFileToProject("app/a.go", fileA()) as GoFile
        b = myFixture.addFileToProject("app/b.go", fileB()) as GoFile
        myFixture.addFileToProject("app/c.go", fileC())
    }

    private fun fieldEdit() = ToggleEdit(project, a, "\tPort int\n", "\tPort int\n".length, "\tExtra bool\n")

    private fun libraryExpressions(): List<GoExpression> =
        PsiTreeUtil.getChildrenOfTypeAsList(b, GoFunctionOrMethodDeclaration::class.java).filter { it.name!!.startsWith("Lib") }
            .flatMap { PsiTreeUtil.findChildrenOfType(it.block, GoExpression::class.java) }

    fun testWarmCheckOtherFile() {
        val diagnostics = semantic.check(b)
        assertTrue("the synthetic package must be clean: ${diagnostics.take(5)}", diagnostics.isEmpty())
        BenchmarkSupport.run("GoDeclarationEditBenchmark.warmCheckOtherFile", "ms", 1.0) { semantic.check(b) }
    }

    fun testDeclEditCheckOtherFile() {
        val edit = fieldEdit()
        semantic.check(b)
        BenchmarkSupport.run("GoDeclarationEditBenchmark.declEditCheckOtherFile", "ms", 1.0, prepare = { edit.toggle() }) { semantic.check(b) }
        edit.reset()
    }

    fun testUnrelatedDeclEditCheckOtherFile() {
        val other = myFixture.addFileToProject("other/o.go", "package other\n\ntype O struct {\n\tA int\n}\n") as GoFile
        val edit = ToggleEdit(project, other, "\tA int\n", "\tA int\n".length, "\tB bool\n")
        semantic.check(b)
        BenchmarkSupport.run("GoDeclarationEditBenchmark.unrelatedDeclEditCheckOtherFile", "ms", 1.0, prepare = { edit.toggle() }) { semantic.check(b) }
        edit.reset()
    }

    fun testWarmLibraryExprs() {
        val expressions = libraryExpressions()
        semantic.check(b)
        val known = expressions.count { semantic.typeOf(it) !is GoUnknownType }
        assertTrue("only $known of ${expressions.size} library expressions have a type", known > expressions.size * 0.7) // `_` and similar have no type
        BenchmarkSupport.run("GoDeclarationEditBenchmark.warmLibraryExprs", "ms/pass", WARM_PASSES.toDouble()) {
            repeat(WARM_PASSES) { for (e in expressions) semantic.typeOf(e) }
        }
        println("  library expressions: ${expressions.size}")
    }

    fun testDeclEditLibraryExprs() {
        val edit = fieldEdit()
        semantic.check(b)
        BenchmarkSupport.runTimed("GoDeclarationEditBenchmark.declEditLibraryExprs", "ms/edit", EDITS.toDouble()) {
            var total = 0.0
            repeat(EDITS) {
                edit.toggle()
                val expressions = libraryExpressions()
                total += BenchmarkSupport.timed { for (e in expressions) semantic.typeOf(e) }
            }
            total
        }
        edit.reset()
    }

    private companion object {
        const val WARM_PASSES = 50
        const val EDITS = 3
        const val TYPES = 40
        const val USES = 60

        fun fileA() = buildString {
            append(
                """
                |package app
                |
                |import (
                |	"fmt"
                |	"strings"
                |)
                |
                |// Config is edited by the benchmark.
                |type Config struct {
                |	Name string
                |	Port int
                |}
                |
                |func NewConfig(name string, port int) *Config {
                |	return &Config{Name: strings.TrimSpace(name), Port: port}
                |}
                |
                |func (c *Config) Addr() string {
                |	return fmt.Sprintf("%s:%d", c.Name, c.Port)
                |}
                |
                """.trimMargin(),
            )
            for (i in 0 until TYPES) {
                append(
                    """
                    |
                    |type Item$i struct {
                    |	ID   int
                    |	Name string
                    |	Cfg  *Config
                    |}
                    |
                    |func (it *Item$i) Label() string {
                    |	return fmt.Sprint(it.ID, it.Name, it.Cfg.Addr())
                    |}
                    |
                    |func Helper$i(c *Config, s string) string {
                    |	return c.Addr() + strings.Repeat(s, $i)
                    |}
                    |
                    """.trimMargin(),
                )
            }
        }

        fun fileC() = """
            |package app
            |
            |import (
            |	"bytes"
            |	"net/http"
            |	"sort"
            |)
            |
            |type Server struct {
            |	cfg   *Config
            |	mux   *http.ServeMux
            |	items []string
            |}
            |
            |func NewServer(c *Config) *Server {
            |	return &Server{cfg: c, mux: http.NewServeMux()}
            |}
            |
            |func (s *Server) Handle(pattern string, h http.HandlerFunc) {
            |	s.mux.HandleFunc(pattern, h)
            |	s.items = append(s.items, pattern)
            |}
            |
            |func (s *Server) Sorted() []string {
            |	out := append([]string(nil), s.items...)
            |	sort.Strings(out)
            |	return out
            |}
            |
            |func (s *Server) Dump() string {
            |	var b bytes.Buffer
            |	for _, it := range s.Sorted() {
            |		b.WriteString(it)
            |	}
            |	return b.String() + s.cfg.Addr()
            |}
            |
        """.trimMargin()

        fun fileB() = buildString {
            append(
                """
                |package app
                |
                |import (
                |	"bytes"
                |	"fmt"
                |	"net/http"
                |	"sort"
                |	"strings"
                |)
                |
                """.trimMargin(),
            )
            for (i in 0 until USES) {
                append(
                    """
                    |
                    |func Use$i(name string) string {
                    |	c := NewConfig(name, $i)
                    |	s := NewServer(c)
                    |	s.Handle("/p$i", func(w http.ResponseWriter, r *http.Request) {
                    |		w.WriteHeader(http.StatusOK)
                    |		fmt.Fprintf(w, "%s %s", r.Method, c.Addr())
                    |	})
                    |	it := &Item${i % TYPES}{ID: $i, Name: name, Cfg: c}
                    |	return it.Label() + Helper${i % TYPES}(c, s.Dump())
                    |}
                    |
                    |func Lib$i(s string) []string {
                    |	parts := strings.Split(s, ",")
                    |	for j := range parts {
                    |		parts[j] = strings.ToUpper(strings.TrimSpace(parts[j]))
                    |	}
                    |	sort.Strings(parts)
                    |	var b bytes.Buffer
                    |	b.WriteString(strings.Join(parts, ";"))
                    |	_ = http.StatusText(http.StatusNotFound)
                    |	_ = fmt.Sprint(len(parts), b.Len())
                    |	return strings.Fields(b.String())
                    |}
                    |
                    """.trimMargin(),
                )
            }
        }
    }
}
