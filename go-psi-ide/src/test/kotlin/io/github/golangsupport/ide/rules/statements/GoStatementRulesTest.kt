package io.github.golangsupport.ide.rules.statements

import com.intellij.openapi.util.Disposer
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.ide.rules.GoRuleLevel
import io.github.golangsupport.ide.rules.GoRuleNeed
import io.github.golangsupport.ide.rules.GoRuleScope
import io.github.golangsupport.ide.rules.GoRuleSet
import io.github.golangsupport.ide.rules.GoRuleSettings
import io.github.golangsupport.ide.rules.GoRules

/**
 * Batch B5 of docs/LINT-RULES.md (staticcheck SA2001...SA9010 and govet `appends`, `atomic`, `defers`: suspicious statements).
 * Fixtures mark the expected problems of a line with trailing `// want [ID] message` (several allowed); every other line must stay
 * quiet (only B5 ids are compared). Quick fixes are checked text before / after.
 */
class GoStatementRulesTest : GoSemanticIdeTestBase() {

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(GoRules())
        Disposer.register(testRootDisposable) {
            GoRuleSettings.getInstance(project).loadState(GoRuleSettings.State())
            GoRuleSet.getInstance(project).invalidate()
        }
    }

    private val settings: GoRuleSettings get() = GoRuleSettings.getInstance(project)

    /** Compares the problems of the ids the fixture expects (plus [also]; all B5 ids when it expects none). */
    private fun check(text: String, fileName: String = "st.go", also: Set<String> = emptySet()) {
        val source = text.trimIndent() + "\n"
        val expected = source.lines().flatMapIndexed { i, line ->
            line.split("// want ").drop(1).map { "${i + 1}: ${it.trim()}" }
        }
        val ids = expected.map { it.substringAfter('[').substringBefore(']') }.toSet() + also
        myFixture.configureByText(fileName, source)
        val document = myFixture.editor.document
        val actual = myFixture.doHighlighting().filter {
            val d = it.description ?: ""
            B5.containsMatchIn(d) && (ids.isEmpty() || d.substringAfter('[').substringBefore(']') in ids)
        }
            .map { "${document.getLineNumber(it.startOffset) + 1}: ${it.description}" }
        assertEquals(expected.sorted().joinToString("\n"), actual.sorted().joinToString("\n"))
    }

    private fun fix(before: String, fix: String, after: String) {
        myFixture.configureByText("fix.go", before.trimIndent() + "\n")
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

    fun testAllRegistered() {
        val rules = GoRuleSet.getInstance(project).allRules.associateBy { it.id }
        for (id in IDS) {
            val rule = rules[id] ?: error("$id is not registered")
            val linter = if (id.startsWith("govet:")) "govet" else "staticcheck"
            assertEquals(id, linter, rule.linter)
            assertEquals(id, id != "SA9003", rule.enabledByDefault)
            assertTrue(id, rule.enabledWithLinter)
            assertEquals(id, GoRuleLevel.WARNING, rule.defaultLevel)
            assertEquals(id, if (id in CALLS) GoRuleScope.CALL else GoRuleScope.STATEMENT, rule.scope)
            assertEquals(id, if (id in SYNTAX) setOf(GoRuleNeed.SYNTAX) else setOf(GoRuleNeed.TYPES), rule.needs)
            assertTrue(id, rule.description.isNotBlank() && rule.title.isNotBlank())
            assertTrue(id, rule.javaClass.packageName.endsWith(".builtin.statements"))
        }
    }

    fun testSA2001EmptyCriticalSection() = check("""
        package st

        import "sync"

        type S struct {
        	sync.Mutex
        	mu sync.RWMutex
        }

        type Locker interface {
        	Lock()
        	Unlock()
        }

        func f(mu *sync.Mutex, s *S, l Locker, other *sync.Mutex) {
        	mu.Lock()
        	mu.Unlock() // want [SA2001] empty critical section
        	s.mu.RLock()
        	s.mu.RUnlock() // want [SA2001] empty critical section
        	s.Lock()
        	s.Unlock() // want [SA2001] empty critical section
        	l.Lock()
        	l.Unlock() // want [SA2001] empty critical section
        	mu.Lock()
        	defer mu.Unlock()
        	other.Lock()
        	_ = 1
        	other.Unlock()
        	s.mu.Lock()
        	s.mu.RUnlock()
        	mu.Lock()
        	other.Unlock()
        	switch {
        	case true:
        		mu.Lock()
        		mu.Unlock()
        	}
        }
    """)

    fun testSA2003DeferLock() = check("""
        package st

        import "sync"

        type T struct{ mu sync.RWMutex }

        func f(mu *sync.Mutex, t *T) {
        	mu.Lock()
        	defer mu.Lock() // want [SA2003] deferring Lock right after having locked already; did you mean to defer Unlock?
        	t.mu.RLock()
        	defer t.mu.RLock() // want [SA2003] deferring RLock right after having locked already; did you mean to defer RUnlock?
        	t.mu.Lock()
        	defer t.mu.Lock()
        	mu.Lock()
        	defer mu.Unlock()
        	mu.Lock()
        	_ = 1
        	defer mu.Lock()
        }
    """)

    fun testSA3001BenchmarkN() = check("""
        package st

        import "testing"

        type fake struct{ N int }

        func BenchmarkX(b *testing.B) {
        	b.N = 1000 // want [SA3001] should not assign to b.N
        	n := b.N
        	_ = n
        	var f fake
        	f.N = 1
        	var t testing.T
        	_ = t
        }
    """, "x_test.go")

    fun testSA4011IneffectiveBreak() = check("""
        package st

        func f(xs []int, c chan int) {
        	for _, x := range xs {
        		switch x {
        		case 1:
        			break // want [SA4011] ineffective break statement. Did you mean to break out of the outer loop?
        		case 2:
        			if x > 0 {
        				break // want [SA4011] ineffective break statement. Did you mean to break out of the outer loop?
        			} else {
        				break // want [SA4011] ineffective break statement. Did you mean to break out of the outer loop?
        			}
        		case 3:
        			println()
        		}
        		select {
        		case <-c:
        			break // want [SA4011] ineffective break statement. Did you mean to break out of the outer loop?
        		default:
        		}
        	}
        outer:
        	for {
        		switch {
        		case true:
        			break outer
        		}
        		switch y := 1; y {
        		case 1:
        			break // want [SA4011] ineffective break statement. Did you mean to break out of the outer loop?
        		}
        		var v any
        		switch v.(type) {
        		case int:
        			break
        		}
        	}
        	switch {
        	case true:
        		break
        	}
        }
    """)

    fun testSA4014RepeatedCondition() = check("""
        package st

        func g() bool { return true }

        func f(a, b bool, x int) {
        	if a {
        	} else if b {
        	} else if a { // want [SA4014] this condition occurs multiple times in this if/else if chain
        	}
        	if x == 1 {
        	} else if x == 2 {
        	} else if x ==  1 { // want [SA4014] this condition occurs multiple times in this if/else if chain
        	} else if x == 1 {
        	}
        	if g() {
        	} else if g() {
        	}
        	if a {
        	}
        	if a {
        	}
        	if y := x; y > 0 {
        	} else if b {
        	} else if b { // want [SA4014] this condition occurs multiple times in this if/else if chain
        	}
        	if a {
        	} else if y := 1; y > 0 {
        	} else if a {
        	}
        }
    """)

    /** Types print with their full package path, like go/types; the light fixture's package path is `/src`. */
    fun testSA4020UnreachableTypeCase() = check("""
        package st

        import "io"

        type T struct{}

        func (T) Read([]byte) (int, error) { return 0, nil }

        type U interface{ M() }
        type V interface{ M() }

        func f(x any, u U) {
        	switch x.(type) {
        	case io.Reader:
        	case io.ReadCloser: // want [SA4020] unreachable case clause: io.Reader will always match before io.ReadCloser
        	case T: // want [SA4020] unreachable case clause: io.Reader will always match before /src.T
        	case int:
        	}
        	switch x.(type) {
        	case nil, T:
        	case io.Reader:
        	}
        	switch u.(type) {
        	case V:
        	case U: // want [SA4020] unreachable case clause: /src.V will always match before /src.U
        	}
        	switch x.(type) {
        	case io.ReadCloser:
        	case io.Reader:
        	default:
        	}
        }
    """)

    fun testSA4029SortConversion() = check("""
        package st

        import "sort"

        func f(a []string, b []float64, c []int, d sort.StringSlice) {
        	a = sort.StringSlice(a) // want [SA4029] sort.StringSlice is a type, not a function, and sort.StringSlice(a) doesn't sort your values; consider using sort.Strings instead
        	b = sort.Float64Slice(b) // want [SA4029] sort.Float64Slice is a type, not a function, and sort.Float64Slice(b) doesn't sort your values; consider using sort.Float64s instead
        	c = sort.IntSlice(c) // want [SA4029] sort.IntSlice is a type, not a function, and sort.IntSlice(c) doesn't sort your values; consider using sort.Ints instead
        	d = sort.StringSlice(d)
        	sort.Sort(sort.StringSlice(a))
        	e := sort.IntSlice(c)
        	_ = e
        	a = sort.StringSlice(a[1:])
        }
    """)

    fun testSA4021AndVetAppends() = check("""
        package st

        func f(arg []int) {
        	x := append(arg) // want [SA4021] x = append(y) is equivalent to x = y
        	_ = x
        	arg = append(arg) // want [SA4021] x = append(y) is equivalent to x = y
        	arg = append(arg, 1)
        	var nilly []int
        	arg = append(arg, nilly...)
        	{
        		append := func([]int) []int { return nil }
        		arg = append(arg)
        	}
        }
    """, also = setOf("govet:appends"))

    fun testVetAppendsWhenSA4021Off() {
        settings.setEnabled("SA4021", false)
        check("""
            package st

            func f(arg []int) {
            	arg = append(arg) // want [govet:appends] append with no values
            	arg = append(arg, 1)
            }
        """)
    }

    fun testSA5002SpinningLoop() = check("""
        package st

        func g() bool { return true }

        const debug = false

        func f(c chan bool) {
        	for { // want [SA5002] this loop will spin, using 100% CPU
        	}
        	for g() {
        	}
        	for {
        		break
        	}
        	for true { // want [SA5002] loop condition never changes or has a race condition // want [SA5002] this loop will spin, using 100% CPU
        	}
        	x := true
        	for x { // want [SA5002] loop condition never changes or has a race condition // want [SA5002] this loop will spin, using 100% CPU
        	}
        	for false {
        	}
        	for debug {
        	}
        	for <-c {
        	}
        	for i := 0; i < 1; {
        	}
        	for ; ; { // want [SA5002] this loop will spin, using 100% CPU
        	}
        }
    """)

    fun testSA5003DeferInInfiniteLoop() = check("""
        package st

        func f(xs []int) {
        	for {
        		defer println() // want [SA5003] defers in this infinite loop will never run
        		func() {
        			defer println()
        			return
        		}()
        		for {
        			defer println() // want [SA5003] defers in this infinite loop will never run
        		}
        	}
        	for {
        		defer println()
        		if len(xs) > 0 {
        			break
        		}
        	}
        	for {
        		defer println()
        		return
        	}
        	for i := 0; ; i++ {
        		defer println() // want [SA5003] defers in this infinite loop will never run
        	}
        	for range xs {
        		defer println()
        	}
        	for len(xs) > 0 {
        		defer println()
        	}
        }
    """)

    fun testSA5004BusySelect() = check("""
        package st

        func f(ch chan int) {
        	select {
        	case <-ch:
        	default:
        	}
        	for {
        		select {
        		case <-ch:
        		default: // want [SA5004] should not have an empty default case in a for+select loop; the loop will spin
        		}
        	}
        	for {
        		select {
        		case <-ch:
        		default:
        			println("foo")
        		}
        	}
        	for {
        		select {
        		case <-ch:
        		}
        	}
        	for len(ch) > 0 {
        		select {
        		default:
        		}
        	}
        }
    """)

    fun testSA6000RegexpInLoop() = check("""
        package st

        import "regexp"

        func f(xs []string, p string) {
        	for _, x := range xs {
        		_, _ = regexp.MatchString("a+", x) // want [SA6000] calling regexp.MatchString in a loop has poor performance, consider using regexp.Compile
        		_, _ = regexp.Match(`b`, []byte(x)) // want [SA6000] calling regexp.Match in a loop has poor performance, consider using regexp.Compile
        		_, _ = regexp.MatchString(p, x)
        		func() {
        			_, _ = regexp.MatchString("a+", x)
        		}()
        	}
        	_, _ = regexp.MatchString("a+", p)
        	for ok, _ := regexp.MatchString("x", p); ok; {
        		break
        	}
        	for i := 0; i < 3; i++ {
        		_, _ = regexp.MatchString("c", p) // want [SA6000] calling regexp.MatchString in a loop has poor performance, consider using regexp.Compile
        	}
        }
    """)

    fun testSA6001MapByteKey() = check("""
        package st

        func f(m map[string]int, b []byte, s string) {
        	k := string(b) // want [SA6001] m[string(key)] would be more efficient than k := string(key); m[k]
        	_ = m[k]
        	v, ok := m[k]
        	_, _ = v, ok
        	k2 := string(b)
        	m[k2] = 1
        	k3 := string(b)
        	_ = m[k3]
        	println(k3)
        	k4 := string(b)
        	func() { _ = m[k4] }()
        	k5 := string(s)
        	_ = m[k5]
        	_ = m[string(b)]
        	var k6 = string(b) // want [SA6001] m[string(key)] would be more efficient than k := string(key); m[k]
        	_ = m[k6]
        	k7 := string(b)
        	delete(m, k7)
        }
    """)

    fun testSA6003RangeRunes() {
        settings.setEnabled("S1029", false)
        check("""
            package st

            func f(s string) {
            	for _, r := range []rune(s) { // want [SA6003] should range over string, not []rune(string)
            		_ = r
            	}
            	rs := []rune(s)
            	for _, r := range rs { // want [SA6003] should range over string, not []rune(string)
            		_ = r
            	}
            	rs2 := []rune(s)
            	for _, r := range rs2 {
            		_ = r
            	}
            	_ = len(rs2)
            	for i, r := range []rune(s) {
            		_, _ = i, r
            	}
            	for range []rune(s) {
            	}
            }
        """)
    }

    fun testSA6003StandsDownForS1029() = check("""
        package st

        func f(s string) {
        	for _, r := range []rune(s) {
        		_ = r
        	}
        	rs := []rune(s)
        	for _, r := range rs { // want [SA6003] should range over string, not []rune(string)
        		_ = r
        	}
        }
    """)

    fun testSA9003EmptyBranch() {
        settings.setEnabled("SA9003", true)
        check("""
        package st

        func f(a, b bool) {
        	if a { // want [SA9003] empty branch
        	}
        	if a { // want [SA9003] empty branch
        	} else { // want [SA9003] empty branch
        	}
        	if a {
        	} else {
        		println()
        	}
        	if a {
        		println()
        	} else { // want [SA9003] empty branch
        	}
        	if a {
        		println()
        	} else if b { // want [SA9003] empty branch
        	}
        	if a {
        		// nothing yet
        	} else if b {
        		println()
        	}
        }
    """)
    }

    fun testSA9003SkipsExamples() {
        settings.setEnabled("SA9003", true)
        check("""
        package st

        func ExampleF() {
        	if true {
        	}
        }

        func TestF() {
        	if true { // want [SA9003] empty branch
        	}
        }
    """, "x_test.go")
    }

    fun testSA9008ShadowedAssertion() = check("""
        package st

        type T struct{ f int }

        func (t *T) set() {}

        func f(x any, y any, z any, w any) {
        	if x, ok := x.(int); ok {
        		_ = x
        	} else {
        		_ = x // want [SA9008] x refers to the result of a failed type assertion and is a zero value, not the value that was being type-asserted
        	}
        	if y, ok := y.(string); ok {
        	} else if y == "" { // want [SA9008] y refers to the result of a failed type assertion and is a zero value, not the value that was being type-asserted
        	}
        	if z, ok := z.(int); ok {
        		_ = z
        	}
        	if v, ok := z.(int); ok {
        		_ = v
        	} else {
        		_ = v
        	}
        	if z, ok := z.(int); !ok {
        		_ = z
        	} else {
        		_ = z
        	}
        	if w, ok := w.(int); ok {
        	} else {
        		w = 1
        		_ = w
        	}
        	if w, ok := w.(T); ok {
        	} else {
        		w.set()
        	}
        	if w, ok := w.(int); ok {
        	} else {
        		func() { _ = w }()
        	}
        }
    """)

    fun testSA9010DeferredFuncNotCalled() = check("""
        package st

        type H func()

        func setup() func() { return func() {} }
        func setupH() H { return nil }
        func plain() {}
        func withArg() func(int) { return nil }

        func f() {
        	defer setup() // want [SA9010] deferred return function not called
        	defer setupH() // want [SA9010] deferred return function not called
        	defer withArg() // want [SA9010] deferred return function not called
        	defer setup()()
        	defer plain()
        }
    """)

    fun testVetAtomic() = check("""
        package st

        import "sync/atomic"

        type S struct{ n int64 }

        func f(x int64, p *int64, s *S, y int64) {
        	x = atomic.AddInt64(&x, 1) // want [govet:atomic] direct assignment to atomic value
        	*p = atomic.AddInt64(p, 1) // want [govet:atomic] direct assignment to atomic value
        	s.n = atomic.AddInt64(&s.n, 1) // want [govet:atomic] direct assignment to atomic value
        	x, y = atomic.AddInt64(&x, 1), 2 // want [govet:atomic] direct assignment to atomic value
        	y = atomic.AddInt64(&x, 1)
        	atomic.AddInt64(&x, 1)
        	z := atomic.AddInt64(&x, 1)
        	_, _ = y, z
        }
    """)

    fun testVetDefers() = check("""
        package st

        import (
        	"log"
        	"time"
        )

        func f() {
        	start := time.Now()
        	defer log.Println(time.Since(start)) // want [govet:defers] call to time.Since is not deferred
        	defer func() { log.Println(time.Since(start)) }()
        	defer log.Println(time.Now())
        	log.Println(time.Since(start))
        }
    """)

    fun testDisabledAndNolint() {
        settings.setEnabled("SA5002", false)
        check("""
            package st

            func g() func() { return nil }

            func f(mu interface{ Lock(); Unlock() }) {
            	for {
            	}
            	mu.Lock()
            	mu.Unlock() //nolint:staticcheck
            	defer g() //lint:ignore SA9010 test
            }
        """)
    }

    fun testNolintGovet() {
        settings.setEnabled("SA4021", false)
        check("""
            package st

            func f(arg []int) {
            	arg = append(arg) //nolint:govet
            	arg = append(arg) //nolint:staticcheck // want [govet:appends] append with no values
            }
        """)
    }

    // ---- fixes

    fun testFixDeferUnlock() = fix(
        "package st\n\nimport \"sync\"\n\nfunc f(mu *sync.Mutex) {\n\tmu.Lock()\n\t<caret>defer mu.Lock()\n}",
        "Defer Unlock",
        "package st\n\nimport \"sync\"\n\nfunc f(mu *sync.Mutex) {\n\tmu.Lock()\n\tdefer mu.Unlock()\n}",
    )

    fun testFixSortConversion() = fix(
        "package st\n\nimport \"sort\"\n\nfunc f(a []int) {\n\t<caret>a = sort.IntSlice(a)\n}",
        "Replace with call to sort.Ints",
        "package st\n\nimport \"sort\"\n\nfunc f(a []int) {\n\tsort.Ints(a)\n}",
    )

    fun testFixSingleArgAppend() = fix(
        "package st\n\nfunc f(a []int) []int {\n\treturn <caret>append(a)\n}",
        "Replace with the appended slice",
        "package st\n\nfunc f(a []int) []int {\n\treturn a\n}",
    )

    fun testFixBusySelect() = fix(
        "package st\n\nfunc f(ch chan int) {\n\tfor {\n\t\tselect {\n\t\tcase <-ch:\n\t\t<caret>default:\n\t\t}\n\t}\n}",
        "Remove empty default branch",
        "package st\n\nfunc f(ch chan int) {\n\tfor {\n\t\tselect {\n\t\tcase <-ch:\n\t\t}\n\t}\n}",
    )

    fun testNoFixBusySelectWithComment() = noFix(
        "package st\n\nfunc f(ch chan int) {\n\tfor {\n\t\tselect {\n\t\tcase <-ch:\n\t\t<caret>default:\n\t\t\t// spin\n\t\t}\n\t}\n}",
        "Remove empty default branch",
    )

    fun testFixEmptyElse() {
        settings.setEnabled("SA9003", true)
        fix(
            "package st\n\nfunc f(a bool) {\n\tif a {\n\t\tprintln()\n\t} <caret>else {\n\t}\n}",
            "Remove empty else branch",
            "package st\n\nfunc f(a bool) {\n\tif a {\n\t\tprintln()\n\t}\n}",
        )
    }

    fun testFixRangeRunes() {
        settings.setEnabled("S1029", false)
        fix(
            "package st\n\nfunc f(s string) {\n\t<caret>for _, r := range []rune(s) {\n\t\t_ = r\n\t}\n}",
            "Range over the string",
            "package st\n\nfunc f(s string) {\n\tfor _, r := range s {\n\t\t_ = r\n\t}\n}",
        )
    }

    fun testFixDeferredFuncCalled() = fix(
        "package st\n\nfunc setup() func() { return nil }\n\nfunc f() {\n\t<caret>defer setup()\n}",
        "Call the returned function",
        "package st\n\nfunc setup() func() { return nil }\n\nfunc f() {\n\tdefer setup()()\n}",
    )

    fun testNoFixDeferredFuncWithParams() = noFix(
        "package st\n\nfunc setup() func(int) { return nil }\n\nfunc f() {\n\t<caret>defer setup()\n}",
        "Call the returned function",
    )

    fun testFixVetAtomic() = fix(
        "package st\n\nimport \"sync/atomic\"\n\nfunc f(x int64) {\n\t<caret>x = atomic.AddInt64(&x, 1)\n}",
        "Remove the assignment",
        "package st\n\nimport \"sync/atomic\"\n\nfunc f(x int64) {\n\tatomic.AddInt64(&x, 1)\n}",
    )

    fun testFixVetDefers() = fix(
        "package st\n\nimport (\n\t\"log\"\n\t\"time\"\n)\n\nfunc f(start time.Time) {\n\tdefer log.Println(<caret>time.Since(start))\n}",
        "Wrap the deferred call in a function literal",
        "package st\n\nimport (\n\t\"log\"\n\t\"time\"\n)\n\nfunc f(start time.Time) {\n\tdefer func() { log.Println(time.Since(start)) }()\n}",
    )

    private companion object {
        val IDS = listOf(
            "SA2001", "SA2003", "SA3001", "SA4011", "SA4014", "SA4020", "SA4029", "SA4021", "govet:appends", "SA5002", "SA5003", "SA5004",
            "SA6000", "SA6003", "SA9003", "SA9008", "SA9010", "govet:atomic", "govet:defers", "SA6001",
        )
        val CALLS = setOf("SA4021", "govet:appends", "SA6000")
        val SYNTAX = setOf("SA4011", "SA4014", "SA5003", "SA5004", "SA9003")
        val B5 = Regex("""^\[(SA200[13]|SA3001|SA40(11|14|20|21|29)|SA500[234]|SA600[013]|SA90(03|08|10)|govet:(appends|atomic|defers))]""")
    }
}
