package io.github.golangsupport.ide.rules.staticcheck

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.replaceService
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.ide.rules.GoRuleLevel
import io.github.golangsupport.ide.rules.GoRuleSet
import io.github.golangsupport.ide.rules.GoRuleSettings
import io.github.golangsupport.ide.rules.GoRules
import io.github.golangsupport.project.api.GoModule
import io.github.golangsupport.project.api.GoModuleGraph
import io.github.golangsupport.project.api.GoModuleGraphProvider

/**
 * Batch B3 (staticcheck SA call contracts, part 2; govet `atomicalign` / `sortslice`). Fixtures mark the expected problem of a line with a
 * trailing `// want [ID] message`; every other line must stay quiet (only B3 ids are compared). Quick fixes are checked text before / after.
 */
class GoStaticcheckB3RulesTest : GoSemanticIdeTestBase() {

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(GoRules())
        Disposer.register(testRootDisposable) {
            GoRuleSettings.getInstance(project).loadState(GoRuleSettings.State())
            GoRuleSet.getInstance(project).invalidate()
        }
    }

    private val settings: GoRuleSettings get() = GoRuleSettings.getInstance(project)

    private fun check(text: String) {
        val source = text.trimIndent() + "\n"
        val expected = source.lines().mapIndexedNotNull { i, line ->
            line.substringAfter("// want ", "").takeIf { it.isNotEmpty() }?.let { "${i + 1}: $it" }
        }
        myFixture.configureByText("sc.go", source)
        val document = myFixture.editor.document
        val actual = myFixture.doHighlighting().filter { B3.containsMatchIn(it.description ?: "") }
            .map { "${document.getLineNumber(it.startOffset) + 1}: ${it.description}" }
        assertEquals(expected.sorted().joinToString("\n"), actual.sorted().joinToString("\n"))
    }

    private fun fix(before: String, fix: String, after: String) {
        myFixture.configureByText("fix.go", before.trimIndent() + "\n")
        val offered = myFixture.availableIntentions
        myFixture.launchAction(offered.firstOrNull { it.text == fix } ?: error("$fix not in ${offered.map { it.text }}"))
        myFixture.checkResult(after.trimIndent() + "\n")
    }

    private fun useGoVersion(version: String) {
        val module = GoModule("example.com/m", null, null, null, version, isMain = true)
        val graph = GoModuleGraph(listOf(module), listOf(module), null, false, null, emptyList(), GoModuleGraph.Source.PURE)
        project.replaceService(GoModuleGraphProvider::class.java, object : GoModuleGraphProvider {
            override fun graphFor(fileOrDirectory: VirtualFile): GoModuleGraph = graph
        }, testRootDisposable)
    }

    private fun use32Bit() {
        toolchain = toolchain.copy(goarch = "386")
    }

    fun testAllRegistered() {
        val rules = GoRuleSet.getInstance(project).allRules.associateBy { it.id }
        for (id in IDS) {
            val rule = rules[id] ?: error("$id is not registered")
            val vet = id.startsWith("govet:")
            assertEquals(id, if (vet) "govet" else "staticcheck", rule.linter)
            assertEquals(id, !vet, rule.enabledByDefault)
            assertEquals(id, !vet, rule.enabledWithLinter)
            assertEquals(id, GoRuleLevel.WARNING, rule.defaultLevel)
            assertTrue(id, rule.description.isNotBlank() && rule.title.isNotBlank())
        }
    }

    fun testSA1001Template() = check("""
        package sc

        import (
        	th "html/template"
        	"text/template"
        )

        const tmpl1 = `{{.Name}} {{.LastName}`
        const tmpl2 = `{{fn}}`

        func f(dynamic string) {
        	_, _ = template.New("").Parse(tmpl1) // want [SA1001] template: :1: bad character U+007D '}'
        	_, _ = template.New("").Parse("{{.A,.B}}") // want [SA1001] template: :1: unexpected "," in operand
        	_, _ = template.New("").Parse("{{1)}}") // want [SA1001] template: :1: unexpected right paren
        	_, _ = template.New("").Parse(tmpl2)
        	t1 := template.New("")
        	_, _ = t1.Parse(tmpl1)
        	_, _ = th.New("").Parse("{{if .X}}") // want [SA1001] template: :1: unexpected EOF
        	_, _ = template.New("x").Parse("{{.A.}}") // want [SA1001] template: :1: unexpected <.> in operand
        	_, _ = template.New("").Parse("{{.A${'$'}}}") // want [SA1001] template: :1: bad character U+0024 '${'$'}'
        	_, _ = template.New("").Parse("x\n{{end}}") // want [SA1001] template: :2: unexpected {{end}}
        	_, _ = template.New("").Parse("{{\"abc}}")
        	_, _ = template.New("").Parse(dynamic)
        	_, _ = template.New("").Parse("{{range ${'$'}i, ${'$'}v := .}}{{${'$'}v}}{{break}}{{end}}{{/* c */}}")
        	_, _ = template.New("").Parse("{{define \"a\"}}{{.}}{{end}}{{template \"a\" .}}{{block \"b\" .}}x{{end}}")
        	_, _ = template.New("").Delims("[[", "]]").Parse("{{abc-}}")
        }
    """)

    /** Messages of `template.New("").Parse` taken from Go 1.27 for the same inputs. */
    fun testTemplateSyntaxMatchesGo() {
        val cases = mapOf(
            "{{.Name}} {{.LastName}" to "template: :1: bad character U+007D '}'",
            "{{if .X}}" to "template: :1: unexpected EOF",
            "{{.A.}}" to "template: :1: unexpected <.> in operand",
            "x\n{{end}}" to "template: :2: unexpected {{end}}",
            "{{\"abc}}" to "template: :1: unterminated quoted string",
            "{{range ${'$'}i, ${'$'}v := .}}{{${'$'}v}}{{break}}{{end}}{{/* c */}}" to null,
            "{{define \"a\"}}{{.}}{{end}}{{template \"a\" .}}{{block \"b\" .}}x{{end}}" to null,
            "{{fn}}" to "template: :1: function \"fn\" not defined",
            "a {{.X | }}" to null,
            "{{(1}}" to "template: :1: unclosed left paren",
            "{{.X}\n\n}}" to "template: :1: bad character U+007D '}'",
            "{{with ${'$'}x := 1}}{{else with 2}}{{end}}{{${'$'}x}}" to "template: :1: undefined variable \"${'$'}x\"",
            "{{ .A -}}" to null,
            "{{-.A}}" to "template: :1: bad number syntax: \"-.A\"",
            "{{ 3. }}" to null,
            "{{'a}}" to "template: :1: unterminated character constant",
            "{{.A=}}" to "template: :1: bad character U+003D '='",
            "{{${'$'}x = 1}}" to null,
            "{{`abc}}" to "template: :1: unterminated raw quoted string",
        )
        for ((text, error) in cases) {
            assertEquals(text, error, io.github.golangsupport.ide.rules.builtin.staticcheck.GoTemplateSyntax.parseError(text))
        }
    }

    fun testSA1003BinaryWrite() = check("""
        package sc

        import (
        	"encoding/binary"
        	b2 "encoding/binary"
        	"io"
        )

        type Fixed struct{ A int32 }

        func f(w io.Writer, i int, i32 int32, s []int, s32 []int32, fx Fixed, bs *[]byte, v interface{}) {
        	_ = binary.Write(w, binary.LittleEndian, i) // want [SA1003] value of type int cannot be used with binary.Write
        	_ = b2.Write(w, binary.LittleEndian, s) // want [SA1003] value of type []int cannot be used with binary.Write
        	_ = binary.Write(w, binary.LittleEndian, &bs) // want [SA1003] value of type **[]byte cannot be used with binary.Write
        	_ = binary.Write(w, binary.LittleEndian, "s") // want [SA1003] value of type string cannot be used with binary.Write
        	_ = binary.Write(w, binary.LittleEndian, i32)
        	_ = binary.Write(w, binary.LittleEndian, s32)
        	_ = binary.Write(w, binary.LittleEndian, fx)
        	_ = binary.Write(w, binary.LittleEndian, &fx)
        	_ = binary.Write(w, binary.LittleEndian, v)
        	_ = binary.Write(w, binary.LittleEndian, true)
        }
    """)

    fun testSA1008HttpHeader() = check("""
        package sc

        import "net/http"

        func f(r *http.Request, m map[string][]string) {
        	const hdr = "foo"
        	h := http.Header{}
        	_ = h["foo"] // want [SA1008] keys in http.Header are canonicalized, "foo" is not canonical; fix the constant or use http.CanonicalHeaderKey
        	_ = h[hdr] // want [SA1008] keys in http.Header are canonicalized, "foo" is not canonical; fix the constant or use http.CanonicalHeaderKey
        	_ = r.Header["content-type"] // want [SA1008] keys in http.Header are canonicalized, "content-type" is not canonical; fix the constant or use http.CanonicalHeaderKey
        	h["foo"] = nil
        	r.Header["foo"] = nil
        	_ = h["Foo"]
        	_ = h["X-Request-Id"]
        	_ = m["foo"]
        	_ = h["foo bar"]
        }
    """)

    fun testSA1011Utf8() = check("""
        package sc

        import (
        	"strings"
        	s2 "strings"
        )

        const bad = "\xc3"

        func f(dyn string) {
        	_ = strings.Trim("\x80test\xff", "\xff") // want [SA1011] argument is not a valid UTF-8 encoded string
        	_ = s2.IndexAny("abc", "a\377b") // want [SA1011] argument is not a valid UTF-8 encoded string
        	_ = strings.ContainsAny("x", bad + "") // want [SA1011] argument is not a valid UTF-8 encoded string
        	_ = strings.TrimLeft("x", "éé")
        	_ = strings.Trim("foo", "bar")
        	_ = strings.Trim("foo", dyn)
        	_ = strings.TrimPrefix("foo", "\xff")
        	s := "\xff"
        	_ = strings.TrimRight("", s) // want [SA1011] argument is not a valid UTF-8 encoded string
        	t := "\xff"
        	if dyn == "" {
        		t = ""
        	}
        	_ = strings.Trim("", t)
        }
    """)

    private val ticks = """
        package sc

        import "time"

        func f1() {
        	for range time.Tick(0) {
        		println("")
        	}
        }

        func f2() {
        	for range time.Tick(0) { // want [SA1015] using time.Tick leaks the underlying ticker, consider using it only in endless functions, tests and the main package, and use time.NewTicker here
        		if true {
        			break
        		}
        	}
        }

        func f3() {
        	for range time.Tick(0) { // want [SA1015] using time.Tick leaks the underlying ticker, consider using it only in endless functions, tests and the main package, and use time.NewTicker here
        		if true {
        			return
        		}
        	}
        }

        func f4() {
        	go func() {
        		for range time.Tick(0) {
        			println("")
        		}
        	}()
        }

        func f5() {
        	if false {
        		panic("foo")
        	}
        	for range time.Tick(0) {
        		println("")
        	}
        }

        func f6() <-chan time.Time {
        	return time.Tick(time.Second) // want [SA1015] using time.Tick leaks the underlying ticker, consider using it only in endless functions, tests and the main package, and use time.NewTicker here
        }
    """

    fun testSA1015TimeTick() {
        useGoVersion("1.22")
        check(ticks)
    }

    fun testSA1015QuietFromGo123() {
        useGoVersion("1.23")
        check(ticks.lines().joinToString("\n") { it.substringBefore(" // want") })
    }

    fun testSA1015QuietInMain() {
        useGoVersion("1.22")
        check(ticks.replace("package sc", "package main").lines().joinToString("\n") { it.substringBefore(" // want") })
    }

    fun testSA1026Marshal() = check("""
        package sc

        import (
        	"encoding/json"
        	"encoding/xml"
        )

        type T1 struct {
        	A int
        	B func() `json:"-" xml:"-"`
        	c chan int
        }

        type T3 struct{ Ch chan int }

        type T5 struct {
        	B func() `xml:"-"`
        }

        type ValueMarshaler chan int

        func (ValueMarshaler) MarshalText() ([]byte, error) { return nil, nil }

        type T4 struct{ C ValueMarshaler }

        type PointerMarshaler chan int

        func (*PointerMarshaler) MarshalText() ([]byte, error) { return nil, nil }

        type T9 struct{ F PointerMarshaler }

        func f(e *json.Encoder) {
        	var t1 T1
        	var t3 T3
        	var t4 T4
        	var t5 T5
        	var t9 T9
        	_, _ = json.Marshal(t1)
        	_, _ = json.Marshal(t3) // want [SA1026] trying to marshal unsupported type chan int, via x.Ch
        	_, _ = json.Marshal(t4)
        	_, _ = json.Marshal(t5) // want [SA1026] trying to marshal unsupported type func(), via x.B
        	_ = e.Encode(t3) // want [SA1026] trying to marshal unsupported type chan int, via x.Ch
        	_, _ = xml.Marshal(t5)
        	_, _ = xml.Marshal(t3) // want [SA1026] trying to marshal unsupported type chan int, via x.Ch
        	_, _ = json.Marshal(t9) // want [SA1026] trying to marshal unsupported type PointerMarshaler, via x.F
        	_, _ = json.Marshal(&t9)
        	var m map[interface{}]string
        	_, _ = json.Marshal(m) // want [SA1026] trying to marshal unsupported type map[interface{}]string
        	var good map[int]string
        	_, _ = json.Marshal(good)
        	_, _ = xml.Marshal(good) // want [SA1026] trying to marshal unsupported type map[int]string
        	_, _ = json.Marshal(f) // want [SA1026] trying to marshal unsupported type func(e *encoding/json.Encoder)
        	var foo struct {
        		Field struct {
        			Field2 []struct {
        				Map map[string]chan int
        			}
        		}
        	}
        	_, _ = json.Marshal(foo) // want [SA1026] trying to marshal unsupported type chan int, via x.Field.Field2[0].Map[k]
        	_, _ = xml.Marshal(foo) // want [SA1026] trying to marshal unsupported type map[string]chan int, via x.Field.Field2[0].Map
        }
    """)

    private val atomics = """
        package sc

        import "sync/atomic"

        type T struct {
        	A int64
        	B int32
        	C int64
        }

        func f(t *T) {
        	var v T
        	atomic.AddInt64(&v.A, 0)
        	atomic.AddInt64(&v.C, 0) // want [SA1027] address of non 64-bit aligned field C passed to sync/atomic.AddInt64
        	_ = atomic.LoadInt64(&t.C) // want [SA1027] address of non 64-bit aligned field C passed to sync/atomic.LoadInt64
        	addr := &t.C
        	atomic.StoreInt64(addr, 1) // want [SA1027] address of non 64-bit aligned field C passed to sync/atomic.StoreInt64
        	_ = atomic.LoadInt32(&t.B)
        }
    """

    fun testSA1027AtomicAlignment() {
        use32Bit()
        check(atomics)
    }

    fun testSA1027QuietOn64Bit() = check(atomics.lines().joinToString("\n") { it.substringBefore(" // want") })

    fun testGovetAtomicAlignWhenSA1027IsOff() {
        use32Bit()
        settings.setEnabled("SA1027", false)
        settings.setEnabled("govet:atomicalign", true)
        check("""
            package sc

            import "sync/atomic"

            type T struct {
            	B int32
            	C uint64
            }

            func f(t *T) {
            	atomic.AddUint64(&t.C, 1) // want [govet:atomicalign] address of non 64-bit aligned field .C passed to atomic.AddUint64
            	addr := &t.C
            	atomic.StoreUint64(addr, 1)
            }
        """)
    }

    fun testSA1028SortSlice() = check("""
        package sc

        import "sort"

        type T1 []int
        type T3 [1]int
        type T4 string

        func f(arg1 interface{}, arg2 []int) {
        	var v1 T1
        	var v3 T3
        	var v4 T4
        	sort.Slice(arg1, nil)
        	sort.Slice(arg2, nil)
        	sort.Slice(v1, nil)
        	sort.Slice(v3, nil) // want [SA1028] sort.Slice must only be called on slices, was called on [1]int
        	sort.Slice(v4, nil) // want [SA1028] sort.Slice must only be called on slices, was called on string
        	sort.Slice(0, nil) // want [SA1028] sort.Slice must only be called on slices, was called on int
        	sort.Slice(nil, nil) // want [SA1028] cannot call sort.Slice on nil literal
        	sort.SliceStable(0, nil) // want [SA1028] sort.SliceStable must only be called on slices, was called on int
        	sort.SliceIsSorted([]int{}, nil)
        }
    """)

    fun testGovetSortSliceWhenSA1028IsOff() {
        settings.setEnabled("SA1028", false)
        settings.setEnabled("govet:sortslice", true)
        check("""
            package sc

            import "sort"

            func f(a [2]int, p *[]int, s []int, i interface{}) {
            	sort.Slice(a, nil) // want [govet:sortslice] sort.Slice's argument must be a slice; is called with [2]int
            	sort.SliceStable(p, nil) // want [govet:sortslice] sort.SliceStable's argument must be a slice; is called with *[]int
            	sort.Slice(s, nil)
            	sort.Slice(i, nil)
            }
        """)
    }

    fun testSA5005Finalizer() = check("""
        package sc

        import (
        	"fmt"
        	"runtime"
        )

        func f() {
        	var x *int
        	foo := func(y *int) { fmt.Println(x) }
        	runtime.SetFinalizer(x, foo) // want [SA5005] the finalizer closes over the object, preventing the finalizer from ever running (at sc.go:10:9)
        	runtime.SetFinalizer(x, nil)
        	runtime.SetFinalizer(x, func(_ *int) { // want [SA5005] the finalizer closes over the object, preventing the finalizer from ever running (at sc.go:13:26)
        		fmt.Println(x)
        	})
        	foo = func(y *int) { fmt.Println(y) }
        	runtime.SetFinalizer(x, foo)
        	runtime.SetFinalizer(x, func(y *int) {
        		fmt.Println(y)
        	})
        }
    """)

    fun testSA5012EvenSlice() = check("""
        package sc

        import "strings"

        func fnVariadic(s string, args ...interface{}) {
        	if len(args)%2 != 0 {
        		panic("odd")
        	}
        }

        func fnSlice(s string, args []interface{}) {
        	if len(args)%2 == 1 {
        		panic("odd")
        	}
        }

        func fnIndirect(s string, args ...interface{}) {
        	fnSlice(s, args)
        }

        func fnLate(args ...interface{}) {
        	if len(args) == 0 {
        		return
        	}
        	if len(args)%2 != 0 {
        		panic("odd")
        	}
        }

        func f(bleh []interface{}, arr [3]interface{}) {
        	fnVariadic("%s", 1, 2, 3) // want [SA5012] variadic argument "args" is expected to have even number of elements, but has 3 elements
        	args := []interface{}{1, 2, 3}
        	fnVariadic("", args...) // want [SA5012] variadic argument "args" is expected to have even number of elements, but has 3 elements
        	fnVariadic("", args[:1]...) // want [SA5012] variadic argument "args" is expected to have even number of elements, but has 1 elements
        	fnVariadic("", args[:2]...)
        	fnVariadic("", bleh...)
        	fnVariadic("", bleh[0:1]...) // want [SA5012] variadic argument "args" is expected to have even number of elements, but has 1 elements
        	fnVariadic("", bleh) // want [SA5012] variadic argument "args" is expected to have even number of elements, but has 1 elements
        	fnVariadic("", make([]interface{}, 3)...) // want [SA5012] variadic argument "args" is expected to have even number of elements, but has 3 elements
        	fnVariadic("", arr[:]...) // want [SA5012] variadic argument "args" is expected to have even number of elements, but has 3 elements
        	fnSlice("", []interface{}{1, 2, 3}) // want [SA5012] argument "args" is expected to have even number of elements, but has 3 elements
        	fnSlice("", []interface{}{1, 2})
        	fnIndirect("%s", 1, 2, 3) // want [SA5012] variadic argument "args" is expected to have even number of elements, but has 3 elements
        	fnIndirect("%s", 1, 2)
        	fnLate(1)
        	_ = strings.NewReplacer("one") // want [SA5012] variadic argument "oldnew" is expected to have even number of elements, but has 1 elements
        	_ = strings.NewReplacer("one", "two")
        }
    """)

    fun testSA6002Pool() = check("""
        package sc

        import (
        	"sync"
        	"unsafe"
        )

        type T1 struct{ x int }

        func f(s []int, i interface{}, up unsafe.Pointer, basic int) {
        	var v sync.Pool
        	p := &sync.Pool{}
        	v.Put(s) // want [SA6002] argument should be pointer-like to avoid allocations
        	v.Put(&s)
        	v.Put(T1{}) // want [SA6002] argument should be pointer-like to avoid allocations
        	p.Put(i)
        	p.Put(up)
        	p.Put(basic) // want [SA6002] argument should be pointer-like to avoid allocations
        	p.Put(nil)
        	defer p.Put([]byte{}) // want [SA6002] argument should be pointer-like to avoid allocations
        }
    """)

    fun testSA9002FileMode() = check("""
        package sc

        import "os"

        func takesInt(n int) {}

        func f() {
        	_, _ = os.OpenFile("", 0, 644) // want [SA9002] file mode '644' evaluates to 01204; did you mean '0644'?
        	_ = os.Chmod("x", 755) // want [SA9002] file mode '755' evaluates to 01363; did you mean '0755'?
        	_ = os.Chmod("x", 0644)
        	_ = os.Chmod("x", 800)
        	takesInt(644)
        }
    """)

    fun testSA9005NoopMarshal() = check("""
        package sc

        import (
        	"encoding/json"
        	"encoding/xml"
        )

        type T1 struct{}
        type T2 struct{ x int }
        type T3 struct{ X int }
        type T4 struct{ T3 }
        type T7 struct{ x int }

        func (T7) MarshalJSON() ([]byte, error) { return nil, nil }

        type T9 struct{ x int }

        func (*T9) UnmarshalText([]byte) error { return nil }

        type T10 struct{}
        type T11 struct{ T10 }
        type t19 string
        type T21 struct{ t19 }
        type T20 string
        type T22 struct{ T20 }

        func f() {
        	_, _ = json.Marshal(T1{})
        	_, _ = json.Marshal(T2{}) // want [SA9005] struct type 'PKG.T2' doesn't have any exported fields, nor custom marshaling
        	_, _ = json.Marshal(&T2{}) // want [SA9005] struct type 'PKG.T2' doesn't have any exported fields, nor custom marshaling
        	_, _ = json.Marshal(T3{})
        	_, _ = json.Marshal(T4{})
        	_, _ = json.Marshal(T7{})
        	_, _ = xml.Marshal(T7{}) // want [SA9005] struct type 'PKG.T7' doesn't have any exported fields, nor custom marshaling
        	_, _ = json.Marshal(T11{}) // want [SA9005] struct type 'PKG.T11' doesn't have any exported fields, nor custom marshaling
        	_, _ = json.Marshal(T21{}) // want [SA9005] struct type 'PKG.T21' doesn't have any exported fields, nor custom marshaling
        	_, _ = json.Marshal(T22{})
        	var t9 T9
        	_ = json.Unmarshal(nil, &t9)
        	var t2 T2
        	_ = json.Unmarshal(nil, &t2) // want [SA9005] struct type 'PKG.T2' doesn't have any exported fields, nor custom marshaling
        }
    """.replace("PKG", PKG))

    fun testSA9007RemoveAll() = check("""
        package sc

        import (
        	"os"
        	"path/filepath"
        )

        func f1() {
        	x := os.TempDir()
        	defer os.RemoveAll(x) // want [SA9007] this call to os.RemoveAll deletes the user's entire temporary directory, not a subdirectory therein
        	x = ""
        	_ = x
        }

        func f3(cond bool) {
        	x := os.TempDir()
        	if cond {
        		x = filepath.Join(x, "foo")
        	}
        	_ = os.RemoveAll(x)
        }

        func f4() {
        	x, _ := os.UserCacheDir()
        	_ = os.RemoveAll(x) // want [SA9007] this call to os.RemoveAll deletes the user's entire cache directory, not a subdirectory therein
        	x, _ = os.UserConfigDir()
        	_ = os.RemoveAll(x) // want [SA9007] this call to os.RemoveAll deletes the user's entire config directory, not a subdirectory therein
        	_ = os.RemoveAll(os.TempDir()) // want [SA9007] this call to os.RemoveAll deletes the user's entire temporary directory, not a subdirectory therein
        	_ = os.RemoveAll(filepath.Join(os.TempDir(), "x"))
        }
    """)

    fun testSA4027UrlQuery() = check("""
        package sc

        import "net/url"

        type T struct{}

        func (v T) Query() T { return v }
        func (v T) Add(a, b string) {}

        func f(u *url.URL, v url.URL) {
        	u.Query().Add("", "") // want [SA4027] (*net/url.URL).Query returns a copy, modifying it doesn't change the URL
        	u.Query().Set("", "") // want [SA4027] (*net/url.URL).Query returns a copy, modifying it doesn't change the URL
        	u.Query().Del("") // want [SA4027] (*net/url.URL).Query returns a copy, modifying it doesn't change the URL
        	_ = u.Query().Encode()
        	q := u.Query()
        	q.Set("a", "b")
        	v.Query().Set("", "")
        	var t T
        	t.Query().Add("", "")
        }
    """)

    fun testSA4030RandOne() = check("""
        package sc

        import (
        	"math/rand"
        	r2 "math/rand/v2"
        )

        func f(n int) {
        	type T struct{ rng rand.Rand }
        	_ = rand.Intn(1) // want [SA4030] math/rand.Intn(n) generates a random value 0 <= x < n; that is, the generated values don't include n; rand.Intn(1) therefore always returns 0
        	var t T
        	_ = t.rng.Int63n(1) // want [SA4030] (*math/rand.Rand).Int63n(n) generates a random value 0 <= x < n; that is, the generated values don't include n; t.rng.Int63n(1) therefore always returns 0
        	_ = r2.IntN(1) // want [SA4030] math/rand/v2.IntN(n) generates a random value 0 <= x < n; that is, the generated values don't include n; r2.IntN(1) therefore always returns 0
        	_ = rand.Intn(2)
        	_ = rand.Intn(n)
        }
    """)

    fun testSA4015IntegerMath() = check("""
        package sc

        import "math"

        func f(x int, y float64, z int8) {
        	_ = math.Ceil(float64(x)) // want [SA4015] calling math.Ceil on a converted integer is pointless
        	_ = math.Floor(float64(x * 2)) // want [SA4015] calling math.Floor on a converted integer is pointless
        	_ = math.IsNaN(float64(z)) // want [SA4015] calling math.IsNaN on a converted integer is pointless
        	_ = math.Ceil(y)
        	_ = math.Ceil(float64(x) / 2)
        	_ = math.Ceil(float64(y))
        	fx := float64(x)
        	_ = math.Trunc(fx) // want [SA4015] calling math.Trunc on a converted integer is pointless
        }

        func g[S int8 | int16](x S) {
        	_ = math.Ceil(float64(x)) // want [SA4015] calling math.Ceil on a converted integer is pointless
        }

        func h[S int8 | float32](x S) {
        	_ = math.Ceil(float64(x))
        }
    """)

    // ---- suppression

    fun testNolint() = check("""
        package sc

        import "math/rand"

        func f() {
        	_ = rand.Intn(1) //nolint:staticcheck
        	//lint:ignore SA4030 on purpose
        	_ = rand.Intn(1)
        	_ = rand.Intn(1) // want [SA4030] math/rand.Intn(n) generates a random value 0 <= x < n; that is, the generated values don't include n; rand.Intn(1) therefore always returns 0
        }
    """)

    // ---- quick fixes

    fun testFixCanonicalHeaderLiteral() = fix(
        "package sc\n\nimport \"net/http\"\n\nfunc f(h http.Header) {\n\t_ = h[<caret>\"content-type\"]\n}",
        "Canonicalize header key",
        "package sc\n\nimport \"net/http\"\n\nfunc f(h http.Header) {\n\t_ = h[\"Content-Type\"]\n}",
    )

    fun testFixCanonicalHeaderConstant() = fix(
        "package sc\n\nimport h2 \"net/http\"\n\nconst key = \"x-id\"\n\nfunc f(h h2.Header) {\n\t_ = h[<caret>key]\n}",
        "Wrap in http.CanonicalHeaderKey",
        "package sc\n\nimport h2 \"net/http\"\n\nconst key = \"x-id\"\n\nfunc f(h h2.Header) {\n\t_ = h[h2.CanonicalHeaderKey(key)]\n}",
    )

    fun testFixOctalFileMode() = fix(
        "package sc\n\nimport \"os\"\n\nfunc f() {\n\t_ = os.Chmod(\"x\", <caret>644)\n}",
        "Fix octal literal",
        "package sc\n\nimport \"os\"\n\nfunc f() {\n\t_ = os.Chmod(\"x\", 0644)\n}",
    )

    fun testFixIntegerMath() = fix(
        "package sc\n\nimport \"math\"\n\nfunc f(x int) float64 {\n\treturn math.Ce<caret>il(float64(x))\n}",
        "Remove the call to math.Ceil",
        "package sc\n\nimport \"math\"\n\nfunc f(x int) float64 {\n\treturn float64(x)\n}",
    )

    fun testFixSortSliceArray() {
        settings.setEnabled("SA1028", false)
        settings.setEnabled("govet:sortslice", true)
        fix(
            "package sc\n\nimport \"sort\"\n\nfunc f(a [2]int) {\n\tsort.Sl<caret>ice(a, nil)\n}",
            "Get a slice of the full array",
            "package sc\n\nimport \"sort\"\n\nfunc f(a [2]int) {\n\tsort.Slice(a[:], nil)\n}",
        )
    }

    private companion object {
        val IDS = listOf(
            "SA1001", "SA1003", "SA1008", "SA1011", "SA1015", "SA1026", "SA1027", "govet:atomicalign", "SA1028", "govet:sortslice", "SA5005", "SA5012",
            "SA6002", "SA9002", "SA9005", "SA9007", "SA4027", "SA4030", "SA4015",
        )
        val B3 = Regex("""^\[(SA1001|SA1003|SA1008|SA1011|SA1015|SA1026|SA1027|govet:atomicalign|SA1028|govet:sortslice|SA5005|SA5012|SA6002|SA9002|SA9005|SA9007|SA4027|SA4030|SA4015)]""")

        /** The import path the package model gives the light project's source root (a real module gives `example.com/m/pkg`). */
        const val PKG = "/src"
    }
}
