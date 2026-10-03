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

/** Batch B8 of docs/LINT-RULES.md: staticcheck S-checks over statements, each with its fix. */
class GoSimpleStatementRulesTest : GoSemanticIdeTestBase() {

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(GoRules())
        Disposer.register(testRootDisposable) {
            GoRuleSettings.getInstance(project).loadState(GoRuleSettings.State())
            GoRuleSet.getInstance(project).invalidate()
        }
    }

    /** `line: message` of the problems of rule [id] in [text]. */
    private fun problems(id: String, text: String): List<String> {
        myFixture.configureByText("s_${getTestName(true)}.go", text.trimIndent() + "\n")
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

    fun testAllB8RulesRegistered() {
        val rules = GoRule.EP_NAME.extensionList.associateBy { it.id }
        for (id in B8) {
            val rule = rules[id] ?: error("$id not registered")
            assertEquals(id, "staticcheck", rule.linter)
            assertEquals(id, setOf("gosimple"), rule.linterAliases)
            assertEquals(id, GoRuleLevel.WEAK_WARNING, rule.defaultLevel)
            assertTrue(id, rule.enabledByDefault)
            assertTrue(id, rule.scope == GoRuleScope.STATEMENT || id == "S1016" && rule.scope == GoRuleScope.EXPRESSION)
            assertTrue(id, rule.javaClass.packageName.endsWith(".builtin.simple"))
        }
    }

    fun testNolintOldLinterName() {
        assertEquals(emptyList<String>(), problems("S1006", """
            package p

            func f() {
            	for true { //nolint:gosimple
            		break
            	}
            }
        """))
    }

    // ---- S1000

    fun testSingleCaseSelect() {
        assertEquals(listOf(
            "4: should use a simple channel send/receive instead of select with a single case",
            "7: should use for range instead of for { select {} }",
            "14: should use a simple channel send/receive instead of select with a single case",
        ), problems("S1000", """
            package p

            func f(ch chan int) {
            	select {
            	case ch <- 1:
            	}
            	for {
            		select {
            		case v := <-ch:
            			println(v)
            		}
            	}
            	for {
            		select {
            		case ch <- 2:
            		}
            	}
            	select {
            	case <-ch:
            	default:
            	}
            }
        """))
    }

    fun testSingleCaseSelectFix() = fix("""
        package p

        func f(ch chan int) {
        	sel<caret>ect {
        	case v := <-ch:
        		println(v)
        		println(v + 1)
        	}
        	println("done")
        }
    """, "Replace with plain channel operation", """
        package p

        func f(ch chan int) {
        	v := <-ch
        	println(v)
        	println(v + 1)
        	println("done")
        }
    """)

    fun testSingleCaseSelectNoFixWhenTheNameIsUsedLater() = noFix("S1000", """
        package p

        func f(ch chan int, v int) {
        	if v > 0 {
        		sel<caret>ect {
        		case v := <-ch:
        			println(v)
        		}
        		println(v)
        	}
        }
    """, "Replace with plain channel operation")

    fun testSingleCaseSelectNoFixWithBreak() = noFix("S1000", """
        package p

        func f(ch chan int) {
        	sel<caret>ect {
        	case v := <-ch:
        		if v > 0 {
        			break
        		}
        		println(v)
        	}
        }
    """, "Replace with plain channel operation")

    fun testForSelectFix() = fix("""
        package p

        func f(ch chan int) {
        	f<caret>or {
        		select {
        		case v := <-ch:
        			println(v)
        		}
        	}
        }
    """, "Replace with for range", """
        package p

        func f(ch chan int) {
        	for v := range ch {
        		println(v)
        	}
        }
    """)

    // ---- S1001

    fun testLoopCopy() {
        assertEquals(listOf(
            "4: should use copy(to, from) instead of a loop",
            "7: should use copy(to, from) instead of a loop",
            "10: should use copy(to, from) instead of a loop",
            "13: should copy arrays using assignment instead of using a loop",
            "16: should use copy(to[:], from) instead of a loop",
        ), problems("S1001", """
            package p

            func f(dst, src []int, a, b [4]int, other []int64) {
            	for i, x := range src {
            		dst[i] = x
            	}
            	for i := range src {
            		dst[i] = src[i]
            	}
            	for i := 0; i < len(src); i++ {
            		dst[i] = src[i]
            	}
            	for i := range b {
            		a[i] = b[i]
            	}
            	for i, x := range src {
            		a[i] = x
            	}
            	for i := range src {
            		dst[i] = src[i] + 1
            	}
            	for i := range other {
            		dst[i] = int(other[i])
            	}
            	for i := range src {
            		dst[i] = src[i-1]
            	}
            	for i, x := range src {
            		dst[i+1] = x
            	}
            }
        """))
    }

    fun testLoopCopyFix() = fix("""
        package p

        func f(dst, src []int) {
        	f<caret>or i, x := range src {
        		dst[i] = x
        	}
        }
    """, "Replace loop with call to copy()", """
        package p

        func f(dst, src []int) {
        	copy(dst, src)
        }
    """)

    fun testLoopCopyArrayFix() = fix("""
        package p

        func f(a, b [4]int) {
        	f<caret>or i := range b {
        		a[i] = b[i]
        	}
        	_ = a
        }
    """, "Replace loop with assignment", """
        package p

        func f(a, b [4]int) {
        	a = b
        	_ = a
        }
    """)

    // ---- S1005

    fun testUnnecessaryBlank() {
        assertEquals(listOf(
            "4: unnecessary assignment to the blank identifier",
            "5: unnecessary assignment to the blank identifier",
            "7: unnecessary assignment to the blank identifier",
            "10: unnecessary assignment to the blank identifier",
            "12: unnecessary assignment to the blank identifier",
            "15: unnecessary assignment to the blank identifier",
        ), problems("S1005", """
            package p

            func f(ch chan int, xs []int, m map[string]int, seq func(func(int) bool)) {
            	x, _ := <-ch
            	_ = <-ch
            	println(x)
            	for i, _ := range xs {
            		println(i)
            	}
            	for _ = range xs {
            	}
            	for _, _ = range xs {
            	}
            	select {
            	case y, _ := <-ch:
            		println(y)
            	}
            	v, _ := m["a"]
            	for _ = range seq {
            	}
            	println(v)
            }
        """))
    }

    fun testUnnecessaryBlankFixes() {
        fix("""
            package p

            func f(ch chan int) {
            	x, <caret>_ := <-ch
            	println(x)
            }
        """, "Remove assignment to blank identifier", """
            package p

            func f(ch chan int) {
            	x := <-ch
            	println(x)
            }
        """)
    }

    fun testUnnecessaryBlankReceiveFix() = fix("""
        package p

        func f(ch chan int) {
        	_ = <-c<caret>h
        }
    """, "Simplify channel receive operation", """
        package p

        func f(ch chan int) {
        	<-ch
        }
    """)

    fun testUnnecessaryBlankRangeFix() = fix("""
        package p

        func f(xs []int) {
        	for i, <caret>_ := range xs {
        		println(i)
        	}
        	for _ = range xs {
        	}
        }
    """, "Remove assignment to blank identifier", """
        package p

        func f(xs []int) {
        	for i := range xs {
        		println(i)
        	}
        	for _ = range xs {
        	}
        }
    """)

    fun testUnnecessaryBlankRangeKeyOnlyFix() = fix("""
        package p

        func f(xs []int) {
        	for <caret>_ = range xs {
        	}
        }
    """, "Remove assignment to blank identifier", """
        package p

        func f(xs []int) {
        	for range xs {
        	}
        }
    """)

    fun testUnnecessaryBlankRangeVersionGate() {
        useGoVersion("1.3")
        assertEquals(listOf("4: unnecessary assignment to the blank identifier"), problems("S1005", """
            package p

            func f(ch chan int, xs []int) {
            	x, _ := <-ch
            	for _ = range xs {
            	}
            	println(x)
            }
        """))
    }

    // ---- S1006

    fun testForTrue() {
        assertEquals(listOf("6: should use for {} instead of for true {}", "9: should use for {} instead of for true {}"), problems("S1006", """
            package p

            const forever = true

            func f(b bool) {
            	for true {
            		break
            	}
            	for forever {
            		break
            	}
            	for b {
            	}
            	for {
            		break
            	}
            }
        """))
    }

    fun testForTrueFix() = fix("""
        package p

        func f() {
        	f<caret>or true {
        		break
        	}
        }
    """, "Remove the condition", """
        package p

        func f() {
        	for {
        		break
        	}
        }
    """)

    // ---- S1008

    fun testIfReturnBool() {
        assertEquals(listOf(
            "4: should use 'return x > 0' instead of 'if x > 0 { return true }; return false'",
            "11: should use 'return len(s) == 0' instead of 'if len(s) > 0 { return false }; return true'",
        ), problems("S1008", """
            package p

            func a(x int) bool {
            	if x > 0 {
            		return true
            	}
            	return false
            }

            func b(s string) bool {
            	if len(s) > 0 {
            		return false
            	}
            	return true
            }

            func c(x, y bool) bool {
            	if x && y {
            		return true
            	}
            	return false
            }

            func d(x int) bool {
            	if x > 1 {
            		return true
            	}
            	if x > 0 {
            		return true
            	}
            	return false
            }

            func e(x int) bool {
            	// keep: explains the choice
            	if x > 0 {
            		return true
            	}
            	return false
            }
        """))
    }

    fun testIfReturnBoolFix() = fix("""
        package p

        func f(ok bool) bool {
        	i<caret>f ok {
        		return false
        	}
        	return true
        }
    """, "Replace with 'return !ok'", """
        package p

        func f(ok bool) bool {
        	return !ok
        }
    """)

    // ---- S1011

    fun testLoopAppend() {
        assertEquals(listOf(
            "4: should replace loop with x = append(x, y...)",
            "7: should replace loop with x = append(x, y...)",
            "10: should replace loop with x = append(x, y...)",
        ), problems("S1011", """
            package p

            func f(x, y []int, z []int64) []int {
            	for _, e := range y {
            		x = append(x, e)
            	}
            	for i := range y {
            		x = append(x, y[i])
            	}
            	for i := range y {
            		v := y[i]
            		x = append(x, v)
            	}
            	for _, e := range z {
            		x = append(x, int(e))
            	}
            	for _, e := range y {
            		x = append(x, e+1)
            	}
            	return x
            }
        """))
    }

    fun testLoopAppendFix() = fix("""
        package p

        func f(x, y []int) []int {
        	f<caret>or _, e := range y {
        		x = append(x, e)
        	}
        	return x
        }
    """, "Replace loop with call to append", """
        package p

        func f(x, y []int) []int {
        	x = append(x, y...)
        	return x
        }
    """)

    // ---- S1016

    private val structs = """
        package p

        type A struct {
        	X int
        	Y string `json:"y"`
        }

        type B struct {
        	X int
        	Y string
        }

        type C struct {
        	X int
        	Z string
        }
    """

    fun testStructConversion() {
        assertEquals(listOf("19: should convert a (type A) to B instead of using struct literal"), problems("S1016", structs + """
        func f(a A, b B) (B, *B, C, B) {
        	return B{X: a.X, Y: a.Y}, &B{X: a.X, Y: a.Y}, C{X: a.X, Z: a.Y}, B{X: a.X, Y: b.Y}
        }
    """))
    }

    fun testStructConversionTagsBeforeGo18() {
        useGoVersion("1.7")
        assertEquals(emptyList<String>(), problems("S1016", structs + """
        func f(a A) B {
        	return B{X: a.X, Y: a.Y}
        }
    """))
        useGoVersion("1.8")
        assertEquals(1, problems("S1016", structs + """
        func g(a A) B {
        	return B{X: a.X, Y: a.Y}
        }
    """).size)
    }

    fun testStructConversionFix() = fix("""
        package p

        type A struct{ X, Y int }

        type B struct{ X, Y int }

        func f(a A) B {
        	return <caret>B{a.X, a.Y}
        }
    """, "Use type conversion", """
        package p

        type A struct{ X, Y int }

        type B struct{ X, Y int }

        func f(a A) B {
        	return B(a)
        }
    """)

    // ---- S1017

    fun testTrimPrefix() {
        assertEquals(listOf(
            "9: should replace this if statement with an unconditional strings.TrimPrefix",
            "12: should replace this if statement with an unconditional strings.TrimSuffix",
            "15: should replace this if statement with an unconditional strings.TrimPrefix",
            "18: should replace this if statement with an unconditional bytes.TrimPrefix",
            "21: should replace this if statement with an unconditional strings.TrimPrefix",
        ), problems("S1017", """
            package p

            import (
            	"bytes"
            	"strings"
            )

            func f(s, p string, b, q []byte) (string, []byte) {
            	if strings.HasPrefix(s, p) {
            		s = s[len(p):]
            	}
            	if strings.HasSuffix(s, p) {
            		s = s[:len(s)-len(p)]
            	}
            	if strings.HasPrefix(s, "ab") {
            		s = s[2:]
            	}
            	if bytes.HasPrefix(b, q) {
            		b = b[len(q):]
            	}
            	if strings.HasPrefix(s, p) {
            		s = strings.TrimPrefix(s, p)
            	}
            	if strings.HasPrefix(s, "ab") {
            		s = s[3:]
            	}
            	if strings.HasPrefix(s, p) {
            		s = s[len(p):]
            	} else {
            		s = ""
            	}
            	if strings.HasPrefix(s, p) {
            		s = s[len(s):]
            	}
            	return s, b
            }
        """))
    }

    fun testTrimPrefixFix() = fix("""
        package p

        import "strings"

        func f(s, p string) string {
        	i<caret>f strings.HasPrefix(s, p) {
        		s = s[len(p):]
        	}
        	return s
        }
    """, "Replace with TrimPrefix", """
        package p

        import "strings"

        func f(s, p string) string {
        	s = strings.TrimPrefix(s, p)
        	return s
        }
    """)

    // ---- S1018

    fun testLoopSlide() {
        assertEquals(listOf("4: should use copy() instead of loop for sliding slice elements"), problems("S1018", """
            package p

            func f(bs []byte, n, offset int, arr [8]byte) {
            	for i := 0; i < n; i++ {
            		bs[i] = bs[offset+i]
            	}
            	for i := 0; i < n; i++ {
            		bs[i] = bs[i+offset]
            	}
            	for i := 0; i < n; i++ {
            		arr[i] = arr[offset+i]
            	}
            }
        """))
    }

    fun testLoopSlideFix() = fix("""
        package p

        func f(bs []byte, n, offset int) {
        	f<caret>or i := 0; i < n; i++ {
        		bs[i] = bs[offset+i]
        	}
        }
    """, "Use copy() instead of loop", """
        package p

        func f(bs []byte, n, offset int) {
        	copy(bs[:n], bs[offset:])
        }
    """)

    // ---- S1021

    fun testMergeVarAssign() {
        assertEquals(listOf("4: should merge variable declaration with assignment on next line"), problems("S1021", """
            package p

            func f() (int, int, int) {
            	var x uint
            	x = 1
            	var y int
            	y = y + 1
            	var z int
            	z = 1
            	z = 2
            	var w int
            	println()
            	w = 3
            	return int(x), y, z + w
            }
        """))
    }

    fun testMergeVarAssignFix() = fix("""
        package p

        func f() uint {
        	<caret>var x uint
        	x = 1
        	return x
        }
    """, "Merge declaration with assignment", """
        package p

        func f() uint {
        	var x uint = 1
        	return x
        }
    """)

    // ---- S1023

    fun testRedundantControlFlow() {
        assertEquals(listOf("7: redundant break statement", "12: redundant return statement", "16: redundant return statement"), problems("S1023", """
            package p

            func f(x int) {
            	switch x {
            	case 1:
            		println(1)
            		break
            	case 2:
            		break
            	}
            	println(x)
            	return
            }

            var g = func() {
            	return
            }

            func h() int {
            	return 1
            }
        """))
    }

    fun testRedundantReturnFix() = fix("""
        package p

        func f() {
        	println(1)

        	re<caret>turn
        }
    """, "Remove redundant statement", """
        package p

        func f() {
        	println(1)
        }
    """)

    fun testRedundantReturnNoFixWithTrailingComment() = noFix("S1023", """
        package p

        func f() {
        	println(1)
        	re<caret>turn // done
        }
    """, "Remove redundant statement")

    // ---- S1029

    fun testRangeStringRunes() {
        assertEquals(listOf("5: should range over string, not []rune(string)"), problems("S1029", """
            package p

            func f(s string, rs []rune) {
            	var n int
            	for _, r := range []rune(s) {
            		n += int(r)
            	}
            	for i, r := range []rune(s) {
            		n += i + int(r)
            	}
            	for _, r := range rs {
            		n += int(r)
            	}
            	for _, b := range []byte(s) {
            		n += int(b)
            	}
            	println(n)
            }
        """))
    }

    fun testRangeStringRunesFix() = fix("""
        package p

        func f(s string) {
        	f<caret>or _, r := range []rune(s) {
        		println(r)
        	}
        }
    """, "Range over the string", """
        package p

        func f(s string) {
        	for _, r := range s {
        		println(r)
        	}
        }
    """)

    // ---- S1031

    fun testNilCheckAroundRange() {
        assertEquals(listOf("4: unnecessary nil check around range", "9: unnecessary nil check around range"), problems("S1031", """
            package p

            func f(xs []int, m map[string]int, p *[3]int, ch chan int) {
            	if xs != nil {
            		for _, x := range xs {
            			println(x)
            		}
            	}
            	if m != nil {
            		for k := range m {
            			println(k)
            		}
            	}
            	if p != nil {
            		for i := range p {
            			println(i)
            		}
            	}
            	if ch != nil {
            		for v := range ch {
            			println(v)
            		}
            	}
            	if xs != nil {
            		for _, x := range xs {
            			println(x)
            		}
            		println()
            	}
            }
        """))
    }

    fun testNilCheckAroundRangeFix() = fix("""
        package p

        func f(xs []int) {
        	i<caret>f xs != nil {
        		for _, x := range xs {
        			println(x)
        		}
        	}
        }
    """, "Remove nil check", """
        package p

        func f(xs []int) {
        	for _, x := range xs {
        		println(x)
        	}
        }
    """)

    // ---- S1033

    fun testGuardedDelete() {
        assertEquals(listOf("4: unnecessary guard around call to delete"), problems("S1033", """
            package p

            func f(m map[string]int, k, j string) {
            	if _, ok := m[k]; ok {
            		delete(m, k)
            	}
            	if _, ok := m[k]; ok {
            		delete(m, j)
            	}
            	if _, ok := m[k]; !ok {
            		delete(m, k)
            	}
            	if _, ok := m[k]; ok {
            		delete(m, k)
            		println()
            	}
            }
        """))
    }

    fun testGuardedDeleteFix() = fix("""
        package p

        func f(m map[string]int, k string) {
        	i<caret>f _, ok := m[k]; ok {
        		delete(m, k)
        	}
        }
    """, "Remove guard", """
        package p

        func f(m map[string]int, k string) {
        	delete(m, k)
        }
    """)

    // ---- S1034

    fun testTypeSwitchAssert() {
        assertEquals(listOf("4: assigning the result of this type assertion to a variable (switch x := x.(type)) could eliminate type assertions in switch cases"),
            problems("S1034", """
            package p

            func f(x, y interface{}) {
            	switch x.(type) {
            	case int:
            		println(x.(int) + 1)
            	case string:
            		println(x.(string), y.(string))
            	}
            	switch x.(type) {
            	case int:
            		println(y.(int))
            	}
            	switch v := x.(type) {
            	case int:
            		println(v)
            	}
            }
        """))
    }

    fun testTypeSwitchAssertFix() = fix("""
        package p

        func f(x interface{}) {
        	switch <caret>x.(type) {
        	case int:
        		println(x.(int) + 1)
        	case string:
        		println(len(x.(string)))
        	default:
        		println(x)
        	}
        }
    """, "Simplify type switch", """
        package p

        func f(x interface{}) {
        	switch x := x.(type) {
        	case int:
        		println(x + 1)
        	case string:
        		println(len(x))
        	default:
        		println(x)
        	}
        }
    """)

    fun testTypeSwitchAssertNoFixForCommaOk() = noFix("S1034", """
        package p

        func f(x interface{}) {
        	switch <caret>x.(type) {
        	case int:
        		v, ok := x.(int)
        		println(v, ok)
        	}
        }
    """, "Simplify type switch")

    // ---- S1036

    fun testMapGuard() {
        assertEquals(listOf(
            "4: unnecessary guard around map access",
            "9: unnecessary guard around map access",
            "14: unnecessary guard around map access",
        ), problems("S1036", """
            package p

            func f(m map[string][]string, c map[string]int, k string) {
            	if _, ok := m[k]; ok {
            		m[k] = append(m[k], "x")
            	} else {
            		m[k] = []string{"x"}
            	}
            	if _, ok := c[k]; ok {
            		c[k] += 4
            	} else {
            		c[k] = 4
            	}
            	if _, ok := c[k]; ok {
            		c[k]++
            	} else {
            		c[k] = 1
            	}
            	if _, ok := c[k]; ok {
            		c[k]++
            	} else {
            		c[k] = 2
            	}
            	if _, ok := c[k]; ok {
            		c[k] += 4
            	} else {
            		c[k] = 5
            	}
            }
        """))
    }

    fun testMapGuardFix() = fix("""
        package p

        func f(c map[string]int, k string) {
        	i<caret>f _, ok := c[k]; ok {
        		c[k]++
        	} else {
        		c[k] = 1
        	}
        }
    """, "Simplify map access", """
        package p

        func f(c map[string]int, k string) {
        	c[k]++
        }
    """)

    // ---- S1037

    fun testElaborateSleep() {
        assertEquals(listOf("6: should use time.Sleep instead of elaborate way of sleeping"), problems("S1037", """
            package p

            import "time"

            func f(d time.Duration, ch chan int) {
            	select {
            	case <-time.After(d):
            	}
            	select {
            	case <-time.After(d):
            	case <-ch:
            	}
            	select {
            	case t := <-time.After(d):
            		println(t.Second())
            	}
            }
        """))
    }

    fun testElaborateSleepFix() = fix("""
        package p

        import "time"

        func f(d time.Duration) {
        	sel<caret>ect {
        	case <-time.After(d):
        		println("woke")
        	}
        }
    """, "Use time.Sleep", """
        package p

        import "time"

        func f(d time.Duration) {
        	time.Sleep(d)
        	println("woke")
        }
    """)

    private companion object {
        val B8 = listOf("S1000", "S1001", "S1005", "S1006", "S1008", "S1011", "S1016", "S1017", "S1018", "S1021", "S1023", "S1029", "S1031",
            "S1033", "S1034", "S1036", "S1037")
    }
}
