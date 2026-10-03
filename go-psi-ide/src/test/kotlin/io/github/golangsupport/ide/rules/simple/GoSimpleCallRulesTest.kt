package io.github.golangsupport.ide.rules.simple

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.replaceService
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.ide.rules.GoRule
import io.github.golangsupport.ide.rules.GoRuleLevel
import io.github.golangsupport.ide.rules.GoRuleScope
import io.github.golangsupport.ide.rules.GoRuleSet
import io.github.golangsupport.ide.rules.GoRuleSettings
import io.github.golangsupport.ide.rules.GoRules
import io.github.golangsupport.project.api.GoModule
import io.github.golangsupport.project.api.GoModuleGraph
import io.github.golangsupport.project.api.GoModuleGraphProvider

/** Batch B9 of docs/LINT-RULES.md: staticcheck S-checks (and SA6005 / SA6006) over calls and expressions, each with its fix. */
class GoSimpleCallRulesTest : GoSemanticIdeTestBase() {

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(GoRules())
        Disposer.register(testRootDisposable) {
            GoRuleSettings.getInstance(project).loadState(GoRuleSettings.State())
            GoRuleSet.getInstance(project).invalidate()
        }
    }

    /** `line: message` of the problems of rule [id] in [text]. */
    private fun problems(id: String, text: String, fileName: String = "s_${getTestName(true)}.go"): List<String> {
        myFixture.configureByText(fileName, text.trimIndent() + "\n")
        val document = myFixture.editor.document
        return myFixture.doHighlighting().filter { it.description?.startsWith("[$id]") == true }
            .map { (document.getLineNumber(it.startOffset) + 1) to it.description!!.removePrefix("[$id] ") }
            .sortedWith(compareBy({ it.first }, { it.second })).map { "${it.first}: ${it.second}" }
    }

    private fun fix(before: String, name: String, after: String) {
        myFixture.configureByText("f_${getTestName(true)}.go", before.trimIndent() + "\n")
        myFixture.doHighlighting()
        val offered = myFixture.availableIntentions
        myFixture.launchAction(offered.firstOrNull { it.text == name } ?: error("'$name' not in ${offered.map { it.text }}"))
        myFixture.checkResult(after.trimIndent() + "\n")
    }

    private fun noFix(id: String, before: String, name: String) {
        myFixture.configureByText("n_${getTestName(true)}.go", before.trimIndent() + "\n")
        val caret = myFixture.caretOffset
        assertTrue("$id not reported at the caret", myFixture.doHighlighting().any { it.description?.startsWith("[$id]") == true && caret in it.startOffset..it.endOffset })
        assertFalse(myFixture.availableIntentions.map { it.text }.toString(), myFixture.availableIntentions.any { it.text == name })
    }

    private fun useGoVersion(version: String?) {
        val module = GoModule("example.com/m", null, null, null, version, isMain = true)
        val graph = GoModuleGraph(listOf(module), listOf(module), null, false, null, emptyList(), GoModuleGraph.Source.PURE)
        project.replaceService(GoModuleGraphProvider::class.java, object : GoModuleGraphProvider {
            override fun graphFor(fileOrDirectory: VirtualFile): GoModuleGraph = graph
        }, testRootDisposable)
    }

    // ---- registration

    fun testAllB9RulesRegistered() {
        val rules = GoRule.EP_NAME.extensionList.associateBy { it.id }
        for ((id, scope) in B9) {
            val rule = rules[id] ?: error("$id not registered")
            assertEquals(id, "staticcheck", rule.linter)
            assertEquals(id, if (id.startsWith("SA")) emptySet() else setOf("gosimple"), rule.linterAliases)
            assertEquals(id, GoRuleLevel.WEAK_WARNING, rule.defaultLevel)
            assertTrue(id, rule.enabledByDefault)
            assertEquals(id, scope, rule.scope)
        }
    }

    fun testNolintOldLinterName() {
        assertEquals(emptyList<String>(), problems("S1039", """
            package p

            import "fmt"

            var s = fmt.Sprint("x") //nolint:gosimple
        """))
    }

    // ---- S1002

    fun testBoolComparisonEverywhere() {
        assertEquals(listOf(
            "8: should omit comparison to bool constant, can be simplified to b",
            "9: should omit comparison to bool constant, can be simplified to b",
            "10: should omit comparison to bool constant, can be simplified to b",
            "11: should omit comparison to bool constant, can be simplified to !b",
            "11: should omit comparison to bool constant, can be simplified to fl",
        ), problems("S1002", """
            package p

            const on, yes = true, Flag(true)

            type Flag bool

            func f(b bool, fl Flag) []bool {
            	x := b == true
            	y := b == on
            	z := !b == false
            	return []bool{x, y, z, b != on, fl == true, fl == Flag(true), fl == yes}
            }
        """))
    }

    fun testBoolComparisonNotInTests() {
        assertEquals(emptyList<String>(), problems("S1002", """
            package p

            func f(b bool) bool { return b == true }
        """, "s_bool_test.go"))
    }

    fun testBoolComparisonDoubleNegationFix() = fix("""
        package p

        func f(b bool) bool {
        	return !b <caret>== false
        }
    """, "Replace with 'b'", """
        package p

        func f(b bool) bool {
        	return b
        }
    """)

    // ---- S1003

    fun testStringsIndex() {
        assertEquals(listOf(
            "6: should use strings.Contains(s, \"x\") instead",
            "7: should use !strings.ContainsAny(s, \"xy\") instead",
            "8: should use bytes.ContainsRune(b, 'x') instead",
            "9: should use !strings.Contains(s, \"x\") instead",
            "10: should use strings.Contains(s, \"x\") instead",
        ), problems("S1003", """
            package p

            import ("bytes"; "strings")

            func f(s string, b []byte) []bool {
            	return []bool{strings.Index(s, "x") != -1,
            		strings.IndexAny(s, "xy") == -1,
            		bytes.IndexRune(b, 'x') > -1,
            		strings.Index(s, "x") < 0,
            		strings.Index(s, "x") >= 0,
            		strings.Index(s, "x") > 0,
            		strings.Index(s, "x") == 0,
            		strings.IndexByte(s, 'x') != -1,
            		-1 != strings.Index(s, "x"),
            	}
            }
        """))
    }

    fun testStringsIndexFix() = fix("""
        package p

        import str "strings"

        func f(s string) bool {
        	return str.Index(s, "x") <caret>== -1
        }
    """, "Simplify use of str.Index", """
        package p

        import str "strings"

        func f(s string) bool {
        	return !str.Contains(s, "x")
        }
    """)

    // ---- S1004

    fun testBytesCompare() {
        assertEquals(listOf("6: should use bytes.Equal(a, b) instead", "7: should use !bytes.Equal(a, b) instead"), problems("S1004", """
            package p

            import "bytes"

            func f(a, b []byte) []bool {
            	return []bool{bytes.Compare(a, b) == 0,
            		bytes.Compare(a, b) != 0,
            		bytes.Compare(a, b) < 0,
            		bytes.Compare(a, b) == 1,
            		0 == bytes.Compare(a, b),
            	}
            }
        """))
    }

    fun testBytesCompareFix() = fix("""
        package p

        import "bytes"

        func f(a, b []byte) bool {
        	return bytes.Compare(a, b) <caret>!= 0
        }
    """, "Simplify use of bytes.Compare", """
        package p

        import "bytes"

        func f(a, b []byte) bool {
        	return !bytes.Equal(a, b)
        }
    """)

    fun testBytesCompareNoFixWithComment() = noFix("S1004", """
        package p

        import "bytes"

        func f(a, b []byte) bool {
        	return bytes.Compare(a, b) /* zero */ <caret>== 0
        }
    """, "Simplify use of bytes.Compare")

    // ---- S1007

    fun testRegexpRaw() {
        assertEquals(listOf(
            "7: should use raw string (`...`) with regexp.MustCompile to avoid having to escape twice",
            "8: should use raw string (`...`) with regexp.Compile to avoid having to escape twice",
        ), problems("S1007", """
            package p

            import "regexp"

            func f(x string) {
            	regexp.MustCompile(`\\.`)
            	regexp.MustCompile("\\.")
            	regexp.Compile("\\.")
            	regexp.Compile("\\.`")
            	regexp.MustCompile("(?m:^lease (.+?) {\n((?s).+?)\\n}\n)")
            	regexp.MustCompile("\\*/[ \t\n\r\f\v]*;")
            	regexp.MustCompile(x)
            	regexp.MustCompile("plain")
            }
        """))
    }

    fun testRegexpRawFix() = fix("""
        package p

        import "regexp"

        var re = regexp.MustCompile(<caret>"\\d+\\.\\d+")
    """, "Convert to raw string literal", """
        package p

        import "regexp"

        var re = regexp.MustCompile(`\d+\.\d+`)
    """)

    // ---- S1009

    fun testNilLenCheck() {
        assertEquals(listOf(
            "6: should omit nil check; len() for nil slices is defined as zero",
            "7: should omit nil check; len() for nil maps is defined as zero",
            "8: should omit nil check; len() for nil channels is defined as zero",
            "9: should omit nil check; len() for nil slices is defined as zero",
        ), problems("S1009", """
            package p

            const zero = 0

            func f(s []int, m map[int]int, c chan int, p *[3]int) []bool {
            	return []bool{s != nil && len(s) != 0,
            		m != nil && len(m) > 0,
            		c == nil || len(c) == zero,
            		s == nil || len(s) <= 2,
            		s != nil && len(s) == 0,
            		s != nil && len(s) >= 0,
            		s == nil || len(s) == 1,
            		p != nil && len(p) != 0,
            		s != nil || len(s) != 0,
            		nil != s && len(s) != 0,
            	}
            }
        """))
    }

    fun testNilLenCheckFix() = fix("""
        package p

        func f(s []int) bool {
        	return s != nil <caret>&& len(s) > 0
        }
    """, "Remove nil check", """
        package p

        func f(s []int) bool {
        	return len(s) > 0
        }
    """)

    // ---- S1010

    fun testSliceLen() {
        assertEquals(listOf("4: should omit second index in slice, s[a:len(s)] is identical to s[a:]"), problems("S1010", """
            package p

            func f(s, t []int, a int) ([]int, []int, []int, []int) {
            	return s[a:len(s)], s[a:len(t)], s[:len(s):len(s)], s[a:]
            }
        """))
    }

    fun testSliceLenFix() = fix("""
        package p

        func f(s string) string {
        	return s[1:<caret>len(s)]
        }
    """, "Simplify slice expression", """
        package p

        func f(s string) string {
        	return s[1:]
        }
    """)

    // ---- S1012 / S1024

    fun testTimeSinceAndUntil() {
        val text = """
            package p

            import "time"

            func f(t time.Time) (time.Duration, time.Duration, time.Duration) {
            	return time.Now().Sub(t), t.Sub(time.Now()), t.Sub(t)
            }
        """
        assertEquals(listOf("6: should use time.Since instead of time.Now().Sub"), problems("S1012", text))
        assertEquals(listOf("6: should use time.Until instead of t.Sub(time.Now())"), problems("S1024", text))
    }

    fun testTimeUntilVersionGate() {
        useGoVersion("1.7")
        assertEquals(emptyList<String>(), problems("S1024", """
            package p

            import "time"

            func f(t time.Time) time.Duration { return t.Sub(time.Now()) }
        """))
    }

    fun testTimeSinceFix() = fix("""
        package p

        import "time"

        func f(t time.Time) time.Duration {
        	return time.Now().<caret>Sub(t)
        }
    """, "Replace with call to time.Since", """
        package p

        import "time"

        func f(t time.Time) time.Duration {
        	return time.Since(t)
        }
    """)

    fun testTimeUntilFix() = fix("""
        package p

        import "time"

        func f(t time.Time) time.Duration {
        	return t.<caret>Sub(time.Now())
        }
    """, "Replace with call to time.Until", """
        package p

        import "time"

        func f(t time.Time) time.Duration {
        	return time.Until(t)
        }
    """)

    fun testTimeUntilNoFixForPointer() = noFix("S1024", """
        package p

        import "time"

        func f(t *time.Time) time.Duration {
        	return t.<caret>Sub(time.Now())
        }
    """, "Replace with call to time.Until")

    // ---- S1019

    fun testMakeLenCap() {
        assertEquals(listOf("4: should use make(chan int) instead", "5: should use make([]int, n) instead"), problems("S1019", """
            package p

            func f(n int) {
            	_ = make(chan int, 0)
            	_ = make([]int, n, n)
            	_ = make([]int, 0)
            	_ = make(map[int]int, 0)
            	_ = make([]int, n, n+1)
            	_ = make(chan int, 1)
            }
        """))
    }

    fun testMakeChanFix() = fix("""
        package p

        var c = make(chan int, <caret>0)
    """, "Remove redundant size argument", """
        package p

        var c = make(chan int)
    """)

    fun testMakeCapFix() = fix("""
        package p

        func f(n int) []int {
        	return make([]int, <caret>n, n)
        }
    """, "Remove redundant capacity argument", """
        package p

        func f(n int) []int {
        	return make([]int, n)
        }
    """)

    fun testMakeCapNoFixWithEffects() = noFix("S1019", """
        package p

        func g() int { return 1 }

        func f() []int {
        	return make([]int, <caret>g(), g())
        }
    """, "Remove redundant capacity argument")

    // ---- S1020

    fun testAssertNotNil() {
        assertEquals(listOf(
            "4: when ok is true, i can't be nil",
            "6: when ok is true, i can't be nil",
            "15: when ok is true, i can't be nil",
        ), problems("S1020", """
            package p

            func f(i interface{}, x interface{}) {
            	if _, ok := i.(string); ok && i != nil {
            	}
            	if _, ok := i.(string); i != nil && ok {
            	}
            	if _, ok := i.(string); i != nil || ok {
            	}
            	if _, ok := i.(string); i != nil && !ok {
            	}
            	if _, ok := i.(string); i == nil && ok {
            	}
            	if i != nil {
            		if _, ok := i.(string); ok {
            		}
            	}
            	if i != nil {
            		if _, ok := i.(string); ok {
            		} else {
            			println()
            		}
            	}
            	if i != nil {
            		if _, ok := i.(string); ok {
            		}
            		println(i)
            	}
            	if x != nil {
            		if _, ok := i.(string); ok {
            		}
            	}
            }
        """))
    }

    fun testAssertNotNilConditionFix() = fix("""
        package p

        func f(i interface{}) {
        	<caret>if _, ok := i.(string); ok && i != nil {
        		println(i)
        	}
        }
    """, "Remove nil check", """
        package p

        func f(i interface{}) {
        	if _, ok := i.(string); ok {
        		println(i)
        	}
        }
    """)

    fun testAssertNotNilNestedFix() = fix("""
        package p

        func f(i interface{}) {
        	if i != nil {
        		<caret>if _, ok := i.(string); ok {
        			println(i)
        		}
        	}
        }
    """, "Remove nil check", """
        package p

        func f(i interface{}) {
        	if _, ok := i.(string); ok {
        		println(i)
        	}
        }
    """)

    fun testAssertNotNilNestedNoFixWithComment() = noFix("S1020", """
        package p

        func f(i interface{}) {
        	if i != nil {
        		// only strings
        		<caret>if _, ok := i.(string); ok {
        			println(i)
        		}
        	}
        }
    """, "Remove nil check")

    // ---- S1025

    fun testRedundantSprintf() {
        assertEquals(listOf(
            "25: the argument is already a string, there's no need to use fmt.Sprintf",
            "26: the argument's underlying type is a string, should use a simple conversion instead of fmt.Sprintf",
            "27: the argument's underlying type is a string, should use a simple conversion instead of fmt.Sprintf",
            "28: should use String() instead of fmt.Sprintf",
            "29: the argument is already a string, there's no need to use fmt.Sprintf",
            "32: should use String() instead of fmt.Sprintf",
            "33: the argument's underlying type is a slice of bytes, should use a simple conversion instead of fmt.Sprintf",
        ), problems("S1025", """
            package p

            import "fmt"

            type T1 string
            type T2 T1
            type T3 int
            type T4 int
            type T6 string
            type T7 []byte
            type T9 string
            type T11 int

            func (T3) String() string { return "" }
            func (T6) String() string { return "" }
            func (T4) String(arg int) string { return "" }
            func (T9) Format(f fmt.State, c rune) {}
            func (T11) Format(f fmt.State, c rune) {}
            func (T11) String() string { return "" }

            func f(t1 T1, t2 T2, t3 T3, t4 T4, t6 T6, t7 T7, t9 T9, t11 T11) []string {
            	return []string{
            		fmt.Sprintf("%v", t1),
            		fmt.Sprintf("%s %s", t1, t2),
            		fmt.Sprintf("%s", "test"),
            		fmt.Sprintf("%s", t1),
            		fmt.Sprintf("%s", t2),
            		fmt.Sprintf("%s", t3),
            		fmt.Sprintf("%s", t3.String()),
            		fmt.Sprintf("%s", t4),
            		fmt.Sprintf("%s", t9),
            		fmt.Sprintf("%s", t6),
            		fmt.Sprintf("%s", t7),
            		fmt.Sprintf("%s", t11),
            	}
            }
        """))
    }

    fun testRedundantSprintfNamedByteVersionGate() {
        val text = """
            package p

            import "fmt"

            type MyByte byte

            func f(b []MyByte) string { return fmt.Sprintf("%s", b) }
        """
        useGoVersion("1.17")
        assertEquals(emptyList<String>(), problems("S1025", text))
        useGoVersion("1.18")
        assertEquals(listOf("7: the argument's underlying type is a slice of bytes, should use a simple conversion instead of fmt.Sprintf"), problems("S1025", text))
    }

    fun testRedundantSprintfStringFix() = fix("""
        package p

        import "fmt"

        func f(a, b string) string {
        	return fmt.<caret>Sprintf("%s", a+b) + "!"
        }
    """, "Remove unnecessary call to fmt.Sprintf", """
        package p

        import "fmt"

        func f(a, b string) string {
        	return (a+b) + "!"
        }
    """)

    fun testRedundantSprintfConversionFix() = fix("""
        package p

        import "fmt"

        func f(b []byte) string {
        	return fmt.<caret>Sprintf("%s", b)
        }
    """, "Replace with conversion to string", """
        package p

        import "fmt"

        func f(b []byte) string {
        	return string(b)
        }
    """)

    fun testRedundantSprintfStringerFix() = fix("""
        package p

        import "fmt"

        type T int

        func (T) String() string { return "t" }

        func f(t T) string {
        	return fmt.<caret>Sprintf("%s", t)
        }
    """, "Replace with call to String method", """
        package p

        import "fmt"

        type T int

        func (T) String() string { return "t" }

        func f(t T) string {
        	return t.String()
        }
    """)

    fun testRedundantSprintfNoStringerFixForErrors() = noFix("S1025", """
        package p

        import "fmt"

        type T int

        func (T) String() string { return "t" }
        func (T) Error() string  { return "e" }

        func f(t T) string {
        	return fmt.<caret>Sprintf("%s", t)
        }
    """, "Replace with call to String method")

    // ---- S1028

    fun testErrorsNewSprintf() {
        assertEquals(listOf("8: should use fmt.Errorf(...) instead of errors.New(fmt.Sprintf(...))"), problems("S1028", """
            package p

            import (
            	"errors"
            	"fmt"
            )

            var e1 = errors.New(fmt.Sprintf("bad %d", 1))
            var e2 = errors.New(fmt.Sprint("bad"))
            var e3 = fmt.Errorf("bad %d", 1)
        """))
    }

    fun testErrorsNewSprintfFix() = fix("""
        package p

        import (
        	"errors"
        	"fmt"
        )

        var e = errors.<caret>New(fmt.Sprintf("bad %d", 1))
    """, "Use fmt.Errorf", """
        package p

        import (
        	"errors"
        	"fmt"
        )

        var e = fmt.Errorf("bad %d", 1)
    """)

    fun testErrorsNewSprintfNoFixForWrapVerb() = noFix("S1028", """
        package p

        import (
        	"errors"
        	"fmt"
        )

        func f(err error) error { return errors.<caret>New(fmt.Sprintf("bad: %w", err)) }
    """, "Use fmt.Errorf")

    // ---- S1030

    fun testBufferConversion() {
        assertEquals(listOf(
            "8: should use buf.String() instead of string(buf.Bytes())",
            "9: should use buf.Bytes() instead of []byte(buf.String())",
        ), problems("S1030", """
            package p

            import "bytes"

            type S string

            func f(buf *bytes.Buffer, m map[string]int) {
            	_ = string(buf.Bytes())
            	_ = []byte(buf.String())
            	_ = m[string(buf.Bytes())]
            	_ = S(buf.Bytes())
            	_ = []uint8(buf.String())
            	_ = string(buf.String())
            }
        """))
    }

    fun testBufferConversionFix() = fix("""
        package p

        import "bytes"

        func f(buf bytes.Buffer) string {
        	return <caret>string(buf.Bytes())
        }
    """, "Simplify conversion", """
        package p

        import "bytes"

        func f(buf bytes.Buffer) string {
        	return buf.String()
        }
    """)

    // ---- S1032

    fun testSortHelpers() {
        assertEquals(listOf(
            "13: should use sort.Ints(...) instead of sort.Sort(sort.IntSlice(...))",
            "28: should use sort.Float64s(...) instead of sort.Sort(sort.Float64Slice(...))",
        ), problems("S1032", """
            package p

            import "sort"

            type MyIntSlice []int

            func (s MyIntSlice) Len() int           { return 0 }
            func (s MyIntSlice) Less(i, j int) bool { return true }
            func (s MyIntSlice) Swap(i, j int)      {}

            func f1() {
            	var a []int
            	sort.Sort(sort.IntSlice(a))
            }

            func f2(c []string) {
            	sort.Sort(sort.StringSlice(c))
            	sort.Sort(MyIntSlice(nil))
            }

            func f3(a []int, e sort.Interface) {
            	sort.Sort(e)
            	sort.Sort(sort.IntSlice(a))
            }

            func f4(b []float64, e sort.Interface) {
            	func() {
            		sort.Sort(sort.Float64Slice(b))
            	}()
            	sort.Sort(e)
            }
        """))
    }

    fun testSortHelpersFix() = fix("""
        package p

        import "sort"

        func f(a []string) {
        	sort.<caret>Sort(sort.StringSlice(a))
        }
    """, "Use sort.Strings", """
        package p

        import "sort"

        func f(a []string) {
        	sort.Strings(a)
        }
    """)

    // ---- S1035

    fun testCanonicalHeaderKey() {
        assertEquals(listOf(
            "6: calling net/http.CanonicalHeaderKey on the 'key' argument of (net/http.Header).Add is redundant",
            "7: calling net/http.CanonicalHeaderKey on the 'key' argument of (net/http.Header).Get is redundant",
        ), problems("S1035", """
            package p

            import "net/http"

            func f(h http.Header, k string) {
            	h.Add(http.CanonicalHeaderKey(k), "v")
            	_ = h.Get(http.CanonicalHeaderKey("x-y"))
            	h.Add(k, http.CanonicalHeaderKey(k))
            	_ = h.Values(http.CanonicalHeaderKey(k))
            }
        """))
    }

    fun testCanonicalHeaderKeyFix() = fix("""
        package p

        import "net/http"

        func f(h http.Header, k string) {
        	h.Set(http.<caret>CanonicalHeaderKey(k), "v")
        }
    """, "Remove call to CanonicalHeaderKey", """
        package p

        import "net/http"

        func f(h http.Header, k string) {
        	h.Set(k, "v")
        }
    """)

    // ---- S1038

    fun testPrintSprintf() {
        assertEquals(listOf(
            "21: should use fmt.Printf instead of fmt.Print(fmt.Sprintf(...))",
            "22: should use fmt.Printf instead of fmt.Println(fmt.Sprintf(...)) (but don't forget the newline)",
            "23: should use fmt.Fprintf instead of fmt.Fprint(fmt.Sprintf(...))",
            "24: should use fmt.Sprintf instead of fmt.Sprint(fmt.Sprintf(...))",
            "26: should use t.Errorf(...) instead of t.Error(fmt.Sprintf(...))",
            "27: should use tb.Logf(...) instead of tb.Log(fmt.Sprintf(...))",
            "28: should use e1.Fatalf(...) instead of e1.Fatal(fmt.Sprintf(...))",
            "30: should use log.Printf(...) instead of log.Println(fmt.Sprintf(...))",
            "31: should use l.Panicf(...) instead of l.Panicln(fmt.Sprintf(...))",
        ), problems("S1038", """
            package p

            import (
            	"fmt"
            	"log"
            	"os"
            	"testing"
            )

            type Embedding1 struct {
            	*testing.T
            }

            type Embedding2 struct {
            	*testing.T
            }

            func (e Embedding2) Errorf() {}

            func f(t *testing.T, tb testing.TB, e1 Embedding1, e2 Embedding2, l *log.Logger, format string) {
            	fmt.Print(fmt.Sprintf("%d", 1))
            	fmt.Println(fmt.Sprintf("%d", 1))
            	fmt.Fprint(os.Stdout, fmt.Sprintf("%d", 1))
            	_ = fmt.Sprint(fmt.Sprintf("%d", 1))
            	fmt.Println(fmt.Sprintf(format, 1))
            	t.Error(fmt.Sprintf(""))
            	tb.Log(fmt.Sprintf(""))
            	e1.Fatal(fmt.Sprintf(""))
            	e2.Error(fmt.Sprintf(""))
            	log.Println(fmt.Sprintf("%d", 1))
            	l.Panicln(fmt.Sprintf("%d", 1))
            	fmt.Print(fmt.Sprintf("%d", 1), 2)
            }
        """))
    }

    fun testPrintSprintfFix() = fix("""
        package p

        import "fmt"

        func f(n int) {
        	fmt.<caret>Print(fmt.Sprintf("n=%d", n))
        }
    """, "Use Printf", """
        package p

        import "fmt"

        func f(n int) {
        	fmt.Printf("n=%d", n)
        }
    """)

    fun testPrintlnSprintfFixAddsNewline() = fix("""
        package p

        import "fmt"

        func f(n int) {
        	fmt.<caret>Println(fmt.Sprintf("n=%d", n))
        }
    """, "Use Printf", """
        package p

        import "fmt"

        func f(n int) {
        	fmt.Printf("n=%d\n", n)
        }
    """)

    fun testPrintlnSprintfNoFixForRawFormat() = noFix("S1038", """
        package p

        import "fmt"

        func f(n int) {
        	fmt.<caret>Println(fmt.Sprintf(`n=%d`, n))
        }
    """, "Use Printf")

    fun testMethodSprintfFix() = fix("""
        package p

        import (
        	"fmt"
        	"testing"
        )

        func f(t *testing.T, n int) {
        	t.<caret>Error(fmt.Sprintf("n=%d", n))
        }
    """, "Use Errorf", """
        package p

        import (
        	"fmt"
        	"testing"
        )

        func f(t *testing.T, n int) {
        	t.Errorf("n=%d", n)
        }
    """)

    // ---- S1039

    fun testSprintLiteral() {
        assertEquals(listOf("6: unnecessary use of fmt.Sprint", "7: unnecessary use of fmt.Sprintf", "8: unnecessary use of fmt.Sprint"), problems("S1039", """
            package p

            import "fmt"

            func f(s string) []string {
            	return []string{fmt.Sprint("x"),
            		fmt.Sprintf("y"),
            		fmt.Sprint(`z`),
            		fmt.Sprintf("%d%%"),
            		fmt.Sprint(s),
            		fmt.Sprint("a", "b"),
            	}
            }
        """))
    }

    fun testSprintLiteralFix() = fix("""
        package p

        import "fmt"

        var s = fmt.<caret>Sprint("x")
    """, "Replace with string literal", """
        package p

        import "fmt"

        var s = "x"
    """)

    fun testSprintfLiteralNoFixForEscapedPercent() = noFix("S1039", """
        package p

        import "fmt"

        var s = fmt.<caret>Sprintf("\x25")
    """, "Replace with string literal")

    // ---- S1040

    fun testSameTypeAssertion() {
        assertEquals(listOf("8: type assertion to the same type: r already has type io.Reader"), problems("S1040", """
            package p

            import "io"

            type R interface{ Read([]byte) (int, error) }

            func f(r io.Reader, x interface{}, y R) {
            	_ = r.(io.Reader)
            	_ = x.(io.Reader)
            	_ = r.(io.ReadCloser)
            	_ = y.(io.Reader)
            }
        """))
    }

    fun testSameTypeAssertionFix() = fix("""
        package p

        import "io"

        func f(r io.Reader) io.Reader {
        	return r.<caret>(io.Reader)
        }
    """, "Remove type assertion", """
        package p

        import "io"

        func f(r io.Reader) io.Reader {
        	return r
        }
    """)

    fun testSameTypeAssertionNoFixCommaOk() = noFix("S1040", """
        package p

        import "io"

        func f(r io.Reader) bool {
        	_, ok := r.<caret>(io.Reader)
        	return ok
        }
    """, "Remove type assertion")

    // ---- SA6005

    fun testToLowerComparison() {
        assertEquals(listOf("6: should use strings.EqualFold instead", "7: should use !strings.EqualFold instead"), problems("SA6005", """
            package p

            import "strings"

            func f(a, b string) []bool {
            	return []bool{strings.ToLower(a) == strings.ToLower(b),
            		strings.ToUpper(a) != strings.ToUpper(b),
            		strings.ToLower(a) == strings.ToUpper(b),
            		strings.ToLower(a) == b,
            	}
            }
        """))
    }

    fun testToLowerComparisonFix() = fix("""
        package p

        import "strings"

        func f(a, b string) bool {
        	return strings.ToLower(a) <caret>!= strings.ToLower(b)
        }
    """, "Replace with !strings.EqualFold", """
        package p

        import "strings"

        func f(a, b string) bool {
        	return !strings.EqualFold(a, b)
        }
    """)

    // ---- SA6006

    fun testWriteStringBytes() {
        assertEquals(listOf("8: use io.Writer.Write instead of converting from []byte to string to use io.WriteString"), problems("SA6006", """
            package p

            import "io"

            type S string

            func f(w io.Writer, b []byte, s string) {
            	io.WriteString(w, string(b))
            	io.WriteString(w, s)
            	io.WriteString(w, string(S(s)))
            }
        """))
    }

    fun testWriteStringBytesFix() = fix("""
        package p

        import "io"

        func f(w io.Writer, b []byte) (int, error) {
        	return io.<caret>WriteString(w, string(b))
        }
    """, "Use Write", """
        package p

        import "io"

        func f(w io.Writer, b []byte) (int, error) {
        	return w.Write(b)
        }
    """)

    fun testWriteStringNamedBytesNoFix() = noFix("SA6006", """
        package p

        import "io"

        type MyByte byte

        func f(w io.Writer, b []MyByte) {
        	io.<caret>WriteString(w, string(b))
        }
    """, "Use Write")

    private companion object {
        val B9 = listOf(
            "S1002" to GoRuleScope.EXPRESSION, "S1003" to GoRuleScope.EXPRESSION, "S1004" to GoRuleScope.EXPRESSION, "S1007" to GoRuleScope.CALL,
            "S1009" to GoRuleScope.EXPRESSION, "S1010" to GoRuleScope.EXPRESSION, "S1012" to GoRuleScope.CALL, "S1019" to GoRuleScope.CALL,
            "S1020" to GoRuleScope.EXPRESSION, "S1024" to GoRuleScope.CALL, "S1025" to GoRuleScope.CALL, "S1028" to GoRuleScope.CALL,
            "S1030" to GoRuleScope.EXPRESSION, "S1032" to GoRuleScope.CALL, "S1035" to GoRuleScope.CALL, "S1038" to GoRuleScope.CALL,
            "S1039" to GoRuleScope.CALL, "S1040" to GoRuleScope.EXPRESSION, "SA6005" to GoRuleScope.EXPRESSION, "SA6006" to GoRuleScope.CALL,
        )
    }
}
