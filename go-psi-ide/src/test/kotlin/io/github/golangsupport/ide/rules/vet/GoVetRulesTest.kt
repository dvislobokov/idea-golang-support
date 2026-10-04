package io.github.golangsupport.ide.rules.vet

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.replaceService
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.ide.rules.GoRuleLevel
import io.github.golangsupport.ide.rules.GoRuleNeed
import io.github.golangsupport.ide.rules.GoRuleScope
import io.github.golangsupport.ide.rules.GoRuleSet
import io.github.golangsupport.ide.rules.GoRuleSettings
import io.github.golangsupport.ide.rules.GoRules
import io.github.golangsupport.project.api.GoModule
import io.github.golangsupport.project.api.GoModuleGraph
import io.github.golangsupport.project.api.GoModuleGraphProvider
import java.io.File

/**
 * Batch B6 of docs/LINT-RULES.md (govet remainder and deprecation: SA1019, govet stdversion, stdmethods, tests, directive, hostport,
 * httpmux, slog, composites, deepequalerrors, reflectvaluecompare; SA4019, SA9009, SA9004). Fixtures mark the expected problems of a
 * line with trailing `// want [ID] message` (several allowed); every other line must stay quiet (only the ids the fixture expects are
 * compared, all B6 ids when it expects none). Quick fixes are checked text before / after.
 */
class GoVetRulesTest : GoSemanticIdeTestBase() {

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(GoRules())
        Disposer.register(testRootDisposable) {
            GoRuleSettings.getInstance(project).loadState(GoRuleSettings.State())
            GoRuleSet.getInstance(project).invalidate()
        }
    }

    private fun highlights(also: Set<String>, expected: List<String>): List<HighlightInfo> {
        val ids = expected.map { it.substringAfter('[').substringBefore(']') }.toSet() + also
        return myFixture.doHighlighting().filter {
            val d = it.description ?: ""
            B6.containsMatchIn(d) && (ids.isEmpty() || d.substringAfter('[').substringBefore(']') in ids)
        }
    }

    /** Compares the problems of the ids the fixture expects (plus [also]; all B6 ids when it expects none). */
    private fun check(text: String, fileName: String = "vt.go", also: Set<String> = emptySet()) {
        val source = text.trimIndent() + "\n"
        val expected = expectations(source)
        myFixture.configureByText(fileName, source)
        compare(expected, also)
    }

    private fun expectations(source: String): List<String> = source.lines().flatMapIndexed { i, line ->
        line.split("// want ").drop(1).map { "${i + 1}: ${it.trim()}" }
    }

    private fun compare(expected: List<String>, also: Set<String> = emptySet()) {
        val document = myFixture.editor.document
        val actual = highlights(also, expected).map { "${document.getLineNumber(it.startOffset) + 1}: ${it.description}" }
        assertEquals(expected.sorted().joinToString("\n"), actual.sorted().joinToString("\n"))
    }

    private fun fix(before: String, fix: String, after: String, fileName: String = "fix.go") {
        myFixture.configureByText(fileName, before.trimIndent() + "\n")
        myFixture.doHighlighting()
        val offered = myFixture.availableIntentions
        myFixture.launchAction(offered.firstOrNull { it.text == fix } ?: error("$fix not in ${offered.map { it.text }}"))
        myFixture.checkResult(after.trimIndent() + "\n")
    }

    private fun noFix(before: String, fix: String) {
        myFixture.configureByText("nofix.go", before.trimIndent() + "\n")
        myFixture.doHighlighting()
        assertFalse(myFixture.availableIntentions.map { it.text }.toString(), myFixture.availableIntentions.any { it.text == fix })
    }

    private fun useGoVersion(version: String) {
        val module = GoModule("example.com/m", null, null, null, version, isMain = true)
        val graph = GoModuleGraph(listOf(module), listOf(module), null, false, null, emptyList(), GoModuleGraph.Source.PURE)
        project.replaceService(GoModuleGraphProvider::class.java, object : GoModuleGraphProvider {
            override fun graphFor(fileOrDirectory: VirtualFile): GoModuleGraph = graph
        }, testRootDisposable)
    }

    /** Writes [files] (paths relative to a temp module root) as a content root and runs [body] with the root. */
    private fun inModule(files: Map<String, String>, body: (VirtualFile) -> Unit) {
        val tmp = FileUtil.createTempDirectory("gopsi-b6", null, true)
        for ((path, text) in files) File(tmp, path).also { it.parentFile.mkdirs() }.writeText(text.trimIndent() + "\n")
        VfsRootAccess.allowRootAccess(testRootDisposable, tmp.path)
        val top = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tmp)!!
        VfsUtil.markDirtyAndRefresh(false, true, true, top)
        PsiTestUtil.addContentRoot(myFixture.module, top)
        try {
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            body(top)
        } finally {
            FileDocumentManager.getInstance().saveAllDocuments()
            PsiTestUtil.removeContentEntry(myFixture.module, top)
        }
    }

    fun testAllRegistered() {
        val rules = GoRuleSet.getInstance(project).allRules.associateBy { it.id }
        for (id in IDS) {
            val rule = rules[id] ?: error("$id is not registered")
            val vet = id.startsWith("govet:")
            assertEquals(id, if (vet) "govet" else "staticcheck", rule.linter)
            assertTrue(id, rule.enabledByDefault)
            assertEquals(id, id !in VET_EXTRA, rule.enabledWithLinter)
            assertEquals(id, GoRuleLevel.WARNING, rule.defaultLevel)
            assertEquals(id, SCOPES.getValue(id), rule.scope)
            assertEquals(id, if (id in SYNTAX) setOf(GoRuleNeed.SYNTAX) else if (id in INDEX) setOf(GoRuleNeed.TYPES, GoRuleNeed.PROJECT_INDEX) else setOf(GoRuleNeed.TYPES), rule.needs)
            assertTrue(id, rule.description.isNotBlank() && rule.title.isNotBlank())
            assertTrue(id, rule.javaClass.packageName.endsWith(".builtin.vet"))
        }
    }

    // ---- govet deepequalerrors / reflectvaluecompare

    fun testDeepEqualErrors() = check("""
        package vt

        import (
        	"errors"
        	"reflect"
        )

        type myErr error

        type wrap struct{ err error }

        type plain struct{ n int }

        func f(e1, e2 error, m myErr, w wrap, p plain, s []error, a, b int, mp map[string]error) {
        	_ = reflect.DeepEqual(e1, e2) // want [govet:deepequalerrors] avoid using reflect.DeepEqual with errors
        	_ = reflect.DeepEqual(m, e2) // want [govet:deepequalerrors] avoid using reflect.DeepEqual with errors
        	_ = reflect.DeepEqual(w, &w) // want [govet:deepequalerrors] avoid using reflect.DeepEqual with errors
        	_ = reflect.DeepEqual(s, []error{errors.New("x")}) // want [govet:deepequalerrors] avoid using reflect.DeepEqual with errors
        	_ = reflect.DeepEqual(mp, mp) // want [govet:deepequalerrors] avoid using reflect.DeepEqual with errors
        	_ = reflect.DeepEqual(e1, a)
        	_ = reflect.DeepEqual(a, b)
        	_ = reflect.DeepEqual(p, p)
        }
    """)

    fun testReflectValueCompare() = check("""
        package vt

        import "reflect"

        func f() {
        	var x, y reflect.Value
        	var a, b interface{}
        	_ = x == y // want [govet:reflectvaluecompare] avoid using == with reflect.Value
        	_ = x == a // want [govet:reflectvaluecompare] avoid using == with reflect.Value
        	_ = a != x // want [govet:reflectvaluecompare] avoid using != with reflect.Value
        	_ = a == b
        	_ = a == reflect.Value{}
        	_ = reflect.Value{} != reflect.Value{}
        	reflect.DeepEqual(x, a) // want [govet:reflectvaluecompare] avoid using reflect.DeepEqual with reflect.Value
        	reflect.DeepEqual(a, b)
        	reflect.DeepEqual(reflect.Value{}, a)
        }
    """)

    // ---- govet hostport

    fun testHostPort() = check("""
        package vt

        import (
        	"fmt"
        	"net"
        )

        var addr4 = fmt.Sprintf("%s:%d", "localhost", 123) // want [govet:hostport] address format "%s:%d" does not work with IPv6 (passed to net.Dial at L23)

        func direct(host string, port int, portStr string) {
        	net.Dial("tcp", fmt.Sprintf("%s:%d", host, port)) // want [govet:hostport] address format "%s:%d" does not work with IPv6
        	net.Dial("tcp", fmt.Sprintf("%s:%s", host, portStr)) // want [govet:hostport] address format "%s:%s" does not work with IPv6
        	net.Dial("tcp", fmt.Sprintf("%s/%d", host, port))
        	net.DialTimeout("tcp", fmt.Sprintf("%s:%d", host, port), 0)
        }

        func indirect(host string, port int) {
        	addr1 := fmt.Sprintf("%s:%d", host, port) // want [govet:hostport] address format "%s:%d" does not work with IPv6 (passed to net.Dial at L19)
        	net.Dial("tcp", addr1)
        	var dialer net.Dialer
        	var addr3 = fmt.Sprintf("%s:%d", host, port) // want [govet:hostport] address format "%s:%d" does not work with IPv6 (passed to net.Dial at L22)
        	dialer.Dial("tcp", addr3)
        	dialer.Dial("tcp", addr4)
        	_, _ = net.Dial("tcp", fmt.Sprintf("%s:%d", "host"))
        }
    """)

    fun testFixHostPortLiteral() = fix(
        "package vt\n\nimport (\n\t\"fmt\"\n\t\"net\"\n)\n\nfunc f(host string) {\n\tnet.Dial(\"tcp\", fmt.Sprintf(<caret>\"%s:%d\", host, 0x7B))\n}",
        "Replace fmt.Sprintf with net.JoinHostPort",
        "package vt\n\nimport (\n\t\"fmt\"\n\t\"net\"\n)\n\nfunc f(host string) {\n\tnet.Dial(\"tcp\", net.JoinHostPort(host, \"123\"))\n}",
    )

    fun testFixHostPortVariable() = fix(
        "package vt\n\nimport (\n\t\"fmt\"\n\t\"net\"\n)\n\nfunc f(host string, port int) {\n\tnet.Dial(\"tcp\", fmt.Sprintf(<caret>\"%s:%d\", host, port))\n}",
        "Replace fmt.Sprintf with net.JoinHostPort",
        "package vt\n\nimport (\n\t\"fmt\"\n\t\"net\"\n)\n\nfunc f(host string, port int) {\n\tnet.Dial(\"tcp\", net.JoinHostPort(host, fmt.Sprintf(\"%d\", port)))\n}",
    )

    // ---- govet httpmux

    private val mux = """
        package vt

        import "net/http"

        func f(mux *http.ServeMux, h http.Handler) {
        	http.Handle("GET /x", h) // want [govet:httpmux] possible enhanced ServeMux pattern used with Go version before 1.22 (update go.mod file?)
        	mux.HandleFunc("/items/{id}", nil) // want [govet:httpmux] possible enhanced ServeMux pattern used with Go version before 1.22 (update go.mod file?)
        	mux.Handle("/static/{path...}", h) // want [govet:httpmux] possible enhanced ServeMux pattern used with Go version before 1.22 (update go.mod file?)
        	mux.Handle("/plain/", h)
        	mux.Handle("/{1x}", h)
        }
    """

    fun testHttpMuxGo121() {
        useGoVersion("1.21")
        check(mux)
    }

    fun testHttpMuxQuietFromGo122() {
        useGoVersion("1.22")
        check(mux.lines().joinToString("\n") { it.substringBefore(" // want") }, also = setOf("govet:httpmux"))
    }

    fun testHttpMuxQuietOnPatchRelease() {
        useGoVersion("1.21.3")
        check(mux.lines().joinToString("\n") { it.substringBefore(" // want") }, also = setOf("govet:httpmux"))
    }

    // ---- govet slog

    fun testSlog() = check("""
        package vt

        import (
        	"context"
        	"fmt"
        	"log/slog"
        )

        type myKey string

        func f(ctx context.Context, l *slog.Logger, r slog.Record, err error, s fmt.Stringer, k myKey) {
        	slog.Info("msg", "a", 1, slog.Int("b", 2), "c", "d")
        	slog.Info("msg", 1) // want [govet:slog] slog.Info arg "1" should be a string or a slog.Attr (possible missing key or value)
        	l.Info("msg", 2) // want [govet:slog] slog.Logger.Info arg "2" should be a string or a slog.Attr (possible missing key or value)
        	slog.Debug("msg", "a") // want [govet:slog] call to slog.Debug missing a final value
        	slog.Warn("msg", slog.Int("a", 1), "k") // want [govet:slog] call to slog.Warn missing a final value
        	slog.ErrorContext(ctx, "msg", "a", 1, "b") // want [govet:slog] call to slog.ErrorContext missing a final value
        	r.Add("K", "v", "k") // want [govet:slog] call to slog.Record.Add missing a final value
        	l.With("a", "b", 2) // want [govet:slog] slog.Logger.With arg "2" should be a string or a slog.Attr (possible missing key or value)
        	slog.Log(ctx, slog.LevelWarn, "msg", "a", "b", 2) // want [govet:slog] slog.Log arg "2" should be a string or a slog.Attr (possible missing key or value)
        	slog.Info("", k, 1) // want [govet:slog] slog.Info arg "k" should be a string or a slog.Attr (possible missing key or value)
        	slog.Debug("msg", any(nil), "a", 2, "b") // want [govet:slog] call to slog.Debug has a missing or misplaced value
        	slog.Debug("msg", any(nil), 2, 3, 4) // want [govet:slog] slog.Debug arg "3" should probably be a string or a slog.Attr (previous arg "2" cannot be a key)
        	slog.Error("msg", err) // want [govet:slog] slog.Error arg "err" should be a string or a slog.Attr (possible missing key or value)
        	slog.Info("msg", s, 1) // want [govet:slog] slog.Info arg "1" should be a string or a slog.Attr (possible missing key or value)
        	_ = slog.Group("key", "a", 1, 2, 3) // want [govet:slog] slog.Group arg "2" should be a string or a slog.Attr (possible missing key or value)
        	args := []any{"a", 1}
        	slog.Info("msg", args...)
        	fmt.Println("msg", 1)
        }
    """)

    // ---- govet composites

    fun testComposites() = check("""
        package vt

        import (
        	"go/scanner"
        	"go/token"
        	"image"
        	"sync"
        	"unicode"
        )

        type ownPair struct{ X, Y int }

        var okLocal = ownPair{1, 2}
        var okAnon = struct{ A, B int }{1, 2}
        var okKeyed = scanner.Error{Msg: "x"}
        var okWhitelisted = image.Point{1, 2}
        var okEmpty = scanner.Error{}
        var bad = &scanner.Error{token.Position{}, "foobar"} // want [govet:composites] go/scanner.Error struct literal uses unkeyed fields
        var mu = sync.Mutex{0, 0} // want [govet:composites] sync.Mutex struct literal uses unkeyed fields

        var delta [3]rune

        var badNamedSlice = unicode.SpecialCase{
        	{1, 2, delta}, // want [govet:composites] unicode.CaseRange struct literal uses unkeyed fields
        	unicode.CaseRange{1, 2, delta}, // want [govet:composites] unicode.CaseRange struct literal uses unkeyed fields
        }
        var badPointers = []*unicode.CaseRange{
        	{1, 2, delta}, // want [govet:composites] *unicode.CaseRange struct literal uses unkeyed fields
        }
        var okNamedSlice = unicode.SpecialCase{
        	{Lo: 1, Hi: 2, Delta: delta},
        }
    """)

    fun testFixComposites() = fix(
        "package vt\n\nimport \"unicode\"\n\nvar r = <caret>unicode.CaseRange{1, 2, [3]rune{}}",
        "Add field names to struct literal",
        "package vt\n\nimport \"unicode\"\n\nvar r = unicode.CaseRange{Lo: 1, Hi: 2, Delta: [3]rune{}}",
    )

    fun testCompositesWhitelistOff() {
        GoRuleSettings.getInstance(project).setOption("govet:composites", "whitelist", "false")
        check("""
            package vt

            import "image"

            var p = image.Point{1, 2} // want [govet:composites] image.Point struct literal uses unkeyed fields
        """)
    }

    fun testNoFixCompositesUnexported() = noFix("package vt\n\nimport \"sync\"\n\nvar mu = <caret>sync.Mutex{0, 0}", "Add field names to struct literal")

    // ---- govet stdmethods

    fun testStdMethods() = check("""
        package vt

        import (
        	"encoding/xml"
        	"fmt"
        	"io"
        )

        type T int

        func (T) Scan(x fmt.ScanState, c byte) {} // want [govet:stdmethods] method Scan(x fmt.ScanState, c byte) should have signature Scan(fmt.ScanState, rune) error

        func (T) Format(fmt.State, byte) {} // want [govet:stdmethods] method Format(fmt.State, byte) should have signature Format(fmt.State, rune)

        type U int

        func (U) Format(byte) {}

        func (U) GobDecode() {} // want [govet:stdmethods] method GobDecode() should have signature GobDecode([]byte) error

        func (U) MarshalXML(*xml.Encoder) {} // want [govet:stdmethods] method MarshalXML(*xml.Encoder) should have signature MarshalXML(*xml.Encoder, xml.StartElement) error

        func (U) UnmarshalXML(*xml.Decoder, xml.StartElement) error { return nil }

        func (U) WriteTo(w io.Writer) {} // want [govet:stdmethods] method WriteTo(w io.Writer) should have signature WriteTo(io.Writer) (int64, error)

        func (T) WriteTo(w io.Writer, more, args int) {}

        type I interface {
        	ReadByte() byte // want [govet:stdmethods] method ReadByte() byte should have signature ReadByte() (byte, error)
        }

        type V int

        func (V) As() T       { return 0 }
        func (V) Is() bool    { return false }
        func (V) Unwrap() int { return 0 }

        type E int

        func (E) Error() string { return "" }

        func (E) As()     {} // want [govet:stdmethods] method As() should have signature As(any) bool
        func (E) Is()     {} // want [govet:stdmethods] method Is() should have signature Is(error) bool
        func (E) Unwrap() {} // want [govet:stdmethods] method Unwrap() should have signature Unwrap() error or Unwrap() []error

        type F int

        func (F) Error() string { return "" }

        func (*F) Is() {} // want [govet:stdmethods] method Is() should have signature Is(error) bool

        type W int

        func (W) Error() string   { return "" }
        func (W) Unwrap() error   { return nil }
        func (W) As(any) bool     { return false }

        type M int

        func (M) Error() string   { return "" }
        func (M) Unwrap() []error { return nil }
    """)

    // ---- govet tests

    fun testTestNames() = check("""
        package vt

        import "testing"

        func Testfoo(t *testing.T) {} // want [govet:tests] Testfoo has malformed name: first letter after 'Test' must not be lowercase

        func TestFoo(t *testing.T) {}

        func Test(t *testing.T) {}

        func Test_foo(t *testing.T) {}

        func Benchmarkfoo(b *testing.B) {} // want [govet:tests] Benchmarkfoo has malformed name: first letter after 'Benchmark' must not be lowercase

        func Fuzzfoo(f *testing.F) {} // want [govet:tests] Fuzzfoo has malformed name: first letter after 'Fuzz' must not be lowercase

        func Testbar(x int) {}

        func Testbaz(t *testing.T) int { return 0 }

        func TestGeneric[T any](t *testing.T) {} // want [govet:tests] TestGeneric has type parameters: it will not be run by go test as a TestXXX function
    """, fileName = "names_test.go")

    fun testTestsOnlyInTestFiles() = check("""
        package vt

        import "testing"

        func Testfoo(t *testing.T) {}
    """, fileName = "plain.go", also = setOf("govet:tests"))

    fun testExamples() = check("""
        package vt

        import "strings"

        type Buf struct{ Len int }

        func (Buf) Write() {}

        func Fn() {}

        func Example() {}

        func ExampleFn() {}

        func ExampleBuf_Write() {}

        func ExampleBuf_Len() {}

        func ExampleBuf_Write_suffix() {}

        func ExampleBuf_suffix() {}

        func ExampleBuilder() {}

        func ExampleNoSuch() {} // want [govet:tests] ExampleNoSuch refers to unknown identifier: NoSuch

        func ExampleBuf_Missing() {} // want [govet:tests] ExampleBuf_Missing refers to unknown field or method: Buf.Missing

        func ExampleFn_Bad() {} // want [govet:tests] ExampleFn_Bad refers to unknown field or method: Fn.Bad

        func ExampleBuf_Write_Bad() {} // want [govet:tests] ExampleBuf_Write_Bad has malformed example suffix: Bad

        func Example_Bad() {} // want [govet:tests] Example_Bad has malformed example suffix: Bad

        func Example_good() {}

        func ExampleFn_args(x int) {} // want [govet:tests] ExampleFn_args should be niladic

        func ExampleFn_res() int { return 0 } // want [govet:tests] ExampleFn_res should return nothing

        var _ = strings.Builder{}
    """, fileName = "example_test.go")

    fun testExampleOutput() = check("""
        package vt

        import "fmt"

        func Example_one() {
        	// Output: // want [govet:tests] output comment block must be the last comment block
        	// 1
        	fmt.Println(1)
        	// trailing
        }

        func Example_two() {
        	fmt.Println(1)
        	// Output: // want [govet:tests] there can only be one output comment block per example
        	// 1

        	// Unordered output:
        	// 1
        }

        func Example_three() {
        	fmt.Println(1)
        	// Output:
        	// 1
        }
    """, fileName = "output_test.go")

    fun testFuzzTargets() = check("""
        package vt

        import "testing"

        type myInt int

        func FuzzOk(f *testing.F) {
        	f.Add(1, "s")
        	f.Fuzz(func(t *testing.T, a int, b string) {
        		_ = f.Name()
        	})
        }

        func FuzzBad(f *testing.F) {
        	f.Add(1) // want [govet:tests] wrong number of values in call to (*testing.F).Add: 1, fuzz target expects 2
        	f.Add("x", "s") // want [govet:tests] mismatched type in call to (*testing.F).Add: string, fuzz target expects int
        	f.Add("x", 1) // want [govet:tests] mismatched types in call to (*testing.F).Add: [string int], fuzz target expects [int string]
        	f.Fuzz(func(t *testing.T, a int, b string) {
        		f.Skip() // want [govet:tests] fuzz target must not call any *F methods
        	})
        }

        func FuzzShape(f *testing.F) {
        	f.Fuzz(1) // want [govet:tests] argument to Fuzz must be a function
        	f.Fuzz(func() {}) // want [govet:tests] fuzz target must have 1 or more argument
        	f.Fuzz(func(t *testing.T) int { return 0 }) // want [govet:tests] fuzz target must not return any value
        	f.Fuzz(func(x int, m myInt) {}) // want [govet:tests] the first parameter of a fuzz target must be *testing.T // want [govet:tests] fuzzing arguments can only have the following types: string, bool, float32, float64, int, int8, int16, int32, int64, uint, uint8, uint16, uint32, uint64, []byte
        }
    """, fileName = "fuzz_test.go")

    // ---- govet directive, SA9009, SA4019

    /** [expected] are `line: [ID] message` entries; for checks that read comment text, where a `// want` marker would change it. */
    private fun checkExactly(text: String, expected: List<String>, fileName: String = "vt.go", also: Set<String> = emptySet()) {
        myFixture.configureByText(fileName, text.trimIndent() + "\n")
        compare(expected, also)
    }

    fun testDirectiveNotMain() = checkExactly("""
        //go:debug panicnil=1

        package vt

        //go:debug panicnil=1
    """, listOf(
        "1: [govet:directive] //go:debug directive only valid in package main or test",
        "5: [govet:directive] //go:debug directive only valid in package main or test",
    ))

    fun testDirectiveMisplacedInMain() = checkExactly("""
        //go:debug panicnil=1

        package main

        //go:debug panicnil=1

        func main() {}
    """, listOf("5: [govet:directive] //go:debug directive only valid before package declaration"))

    fun testDirectiveInTest() = checkExactly("""
        //go:debug panicnil=1

        package vt_test
    """, emptyList(), fileName = "x_test.go", also = setOf("govet:directive"))

    fun testDirectiveBadSpace() = checkExactly(
        "//go:debug\u00a000a0\n\npackage main\n",
        listOf("1: [govet:directive] invalid space '\\u00a0' in //go:debug directive"),
    )

    fun testSA9009() = checkExactly("""
        package vt

        import _ "unsafe" // go:linkname

        // go:linkname bad1 foo.Bad1
        func bad1() {}

        //${"\t"}go:generate echo
        func bad2() {}

        /* go:foobar 1234*/
        func bad3() {}

         //go:linkname good1 good.One
        func good1() {}

        // go: probably just talking about Go
        func good5() {}

        // go:Upper is not a directive
        func good6() {}
    """, listOf(
        "5: [SA9009] ineffectual compiler directive due to extraneous space: \"// go:linkname bad1 foo.Bad1\"",
        "8: [SA9009] ineffectual compiler directive due to extraneous space: \"//\\tgo:generate echo\"",
    ))

    fun testFixSA9009() = fix(
        "package vt\n\n<caret>// go:generate echo hi\nfunc f() {}",
        "Remove the space before the directive",
        "package vt\n\n//go:generate echo hi\nfunc f() {}",
    )

    fun testSA4019() = check("""
        //go:build (one || two || three || go1.1) && (three || one || two || go1.1)
        // +build one two three go1.1
        // +build three one two go1.1

        package vt // want [SA4019] identical build constraints "one two three go1.1" and "three one two go1.1"
    """)

    fun testSA4019Distinct() = check("""
        // +build one two
        // +build one

        // Package vt is documented.
        // +build one
        package vt
    """, also = setOf("SA4019"))

    // ---- SA9004

    fun testSA9004() = check("""
        package vt

        type Enum int

        const (
        	EnumFirst Enum = 1 // want [SA9004] only the first constant in this group has an explicit type
        	EnumSecond = 2
        	EnumThird = -3
        )

        const (
        	A byte = iota
        	B
        )

        const (
        	C Enum = 1
        	D Enum = 2
        )

        const (
        	E Enum = 1
        	F = E + 1
        )

        const (
        	G Enum = 1

        	H = 2
        )

        const (
        	I uint8 = 1
        	J = "s"
        )

        const K Enum = 1
    """)

    fun testFixSA9004() = fix(
        "package vt\n\nconst (\n\t<caret>A uint8 = 1\n\tB = 2\n\tC = 3\n)",
        "Add type to all constants in group",
        "package vt\n\nconst (\n\tA uint8 = 1\n\tB uint8 = 2\n\tC uint8 = 3\n)",
    )

    // ---- govet stdversion

    fun testStdVersion() {
        useGoVersion("1.21")
        check("""
            package vt

            import (
            	"go/types"
            	"slices"
            )

            func f() {
            	var _ types.Info
            	_ = new(types.Info).FileVersions // want [govet:stdversion] types.Info.FileVersions requires go1.22 or later (module is go1.21)
            	_ = new(types.Info).PkgNameOf // want [govet:stdversion] types.(*Info).PkgNameOf requires go1.22 or later (module is go1.21)
            	var a types.Alias // want [govet:stdversion] types.Alias requires go1.22 or later (module is go1.21)
            	_ = a.Underlying()
            	_ = slices.Concat[[]int] // want [govet:stdversion] slices.Concat requires go1.22 or later (module is go1.21)
            	_ = slices.Sort[[]int]
            }
        """)
    }

    fun testStdVersionFileTag() {
        useGoVersion("1.21")
        check("""
            //go:build go1.22

            package vt

            import (
            	"go/types"
            	"iter"
            )

            var _ types.Alias
            var _ iter.Seq[int] // want [govet:stdversion] iter.Seq requires go1.23 or later (file is go1.22)
        """)
    }

    fun testStdVersionQuietBefore121() {
        useGoVersion("1.20")
        check("package vt\n\nimport \"go/types\"\n\nvar _ types.Alias\n", also = setOf("govet:stdversion"))
    }

    // ---- SA1019

    fun testSA1019Stdlib() {
        useGoVersion("1.22")
        check("""
            package vt

            import (
            	"math/rand"
            	"os"
            	"runtime"
            )

            func f(b []byte) {
            	_ = os.SEEK_SET // want [SA1019] os.SEEK_SET has been deprecated since Go 1.7: Use io.SeekStart, io.SeekCurrent, and io.SeekEnd.
            	_, _ = rand.Read(b) // want [SA1019] math/rand.Read has been deprecated since Go 1.20 because it shouldn't be used: For almost all use cases, [crypto/rand.Read] is more appropriate. If a deterministic source is required, use [math/rand/v2.ChaCha8.Read].
            	_ = runtime.GOROOT()
            }
        """)
    }

    fun testSA1019StdlibNeedsVersion() {
        useGoVersion("1.6")
        check("package vt\n\nimport \"os\"\n\nvar _ = os.SEEK_SET\n", also = setOf("SA1019"))
    }

    fun testSA1019StruckThrough() {
        useGoVersion("1.22")
        myFixture.configureByText("vt.go", "package vt\n\nimport \"os\"\n\nvar _ = os.SEEK_SET\n")
        val info = myFixture.doHighlighting().single { it.description?.startsWith("[SA1019]") == true }
        assertEquals("os.SEEK_SET", info.text)
        assertEquals(CodeInsightColors.DEPRECATED_ATTRIBUTES, info.type.attributesKey)
    }

    fun testSA1019Module() = inModule(mapOf(
        "go.mod" to "module example.com/dep\n\ngo 1.22\n",
        "old/old.go" to """
            // Package old is old.
            //
            // Deprecated: use example.com/dep/lib.
            package old

            func F() {}
        """,
        "lib/lib.go" to """
            package lib

            // Old does things.
            //
            // Deprecated: use New
            // instead.
            func Old() {}

            func New() {}

            // T is a type.
            type T struct {
            	// Deprecated: don't use me.
            	D string
            	N string
            }

            // Deprecated: no methods.
            func (T) M() {}

            const (
            	// Deprecated: gone.
            	K = 1
            	L = 2
            )

            // Deprecated: whole group.
            var (
            	V1 = 1
            	V2 = 2
            )

            // Deprecated: use T.
            type Dep int

            // own uses its deprecated symbols freely
            func own() { Old() }
        """,
        "app/app.go" to """
            package app

            import (
            	"example.com/dep/lib"
            	"example.com/dep/old" // want [SA1019] example.com/dep/old is deprecated: use example.com/dep/lib.
            )

            func f() {
            	lib.Old() // want [SA1019] example.com/dep/lib.Old is deprecated: use New instead.
            	lib.New()
            	t := lib.T{D: "x", N: "y"} // want [SA1019] (example.com/dep/lib.T).D is deprecated: don't use me.
            	_ = t.D // want [SA1019] (example.com/dep/lib.T).D is deprecated: don't use me.
            	t.M() // want [SA1019] (example.com/dep/lib.T).M is deprecated: no methods.
            	_ = lib.K // want [SA1019] example.com/dep/lib.K is deprecated: gone.
            	_ = lib.L
            	_ = lib.V2 // want [SA1019] example.com/dep/lib.V2 is deprecated: whole group.
            	old.F()
            	_ = lib.T.M // want [SA1019] (example.com/dep/lib.T).M is deprecated: no methods.
            }

            var _ lib.Dep // want [SA1019] example.com/dep/lib.Dep is deprecated: use T.

            // Deprecated: a deprecated function may use deprecated things.
            func g() { lib.Old() }
        """,
    )) { root ->
        val app = root.findFileByRelativePath("app/app.go")!!
        myFixture.configureFromExistingVirtualFile(app)
        compare(expectations(myFixture.editor.document.text))
    }

    // ---- suppression

    fun testNolintGovet() = check("""
        package vt

        import "reflect"

        func f(a, b error) {
        	_ = reflect.DeepEqual(a, b) //nolint:govet
        	_ = reflect.DeepEqual(a, b) //nolint:staticcheck // want [govet:deepequalerrors] avoid using reflect.DeepEqual with errors
        }
    """)

    private companion object {
        val IDS = listOf(
            "SA1019", "govet:stdversion", "govet:stdmethods", "govet:tests", "govet:directive", "govet:hostport", "govet:httpmux", "govet:slog",
            "govet:composites", "govet:deepequalerrors", "govet:reflectvaluecompare", "SA4019", "SA9009", "SA9004",
        )
        val VET_EXTRA = setOf("govet:deepequalerrors", "govet:reflectvaluecompare", "govet:httpmux")
        val SYNTAX = setOf("govet:directive", "SA4019", "SA9009")
        val INDEX = setOf("SA1019", "govet:stdversion")
        val SCOPES = mapOf(
            "SA1019" to GoRuleScope.FILE, "govet:stdversion" to GoRuleScope.FILE, "govet:stdmethods" to GoRuleScope.FILE, "govet:tests" to GoRuleScope.FILE,
            "govet:directive" to GoRuleScope.FILE, "govet:hostport" to GoRuleScope.CALL, "govet:httpmux" to GoRuleScope.CALL, "govet:slog" to GoRuleScope.CALL,
            "govet:composites" to GoRuleScope.EXPRESSION, "govet:deepequalerrors" to GoRuleScope.CALL, "govet:reflectvaluecompare" to GoRuleScope.EXPRESSION,
            "SA4019" to GoRuleScope.FILE, "SA9009" to GoRuleScope.FILE, "SA9004" to GoRuleScope.FILE,
        )
        val B6 = Regex("""^\[(SA1019|SA4019|SA9009|SA9004|govet:(stdversion|stdmethods|tests|directive|hostport|httpmux|slog|composites|deepequalerrors|reflectvaluecompare))]""")
    }
}
