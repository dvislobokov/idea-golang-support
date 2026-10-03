package io.github.golangsupport.ide.rules.staticcheck

import com.intellij.openapi.util.Disposer
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.ide.rules.GoRuleLevel
import io.github.golangsupport.ide.rules.GoRuleNeed
import io.github.golangsupport.ide.rules.GoRuleScope
import io.github.golangsupport.ide.rules.GoRuleSet
import io.github.golangsupport.ide.rules.GoRuleSettings
import io.github.golangsupport.ide.rules.GoRules

/**
 * Batch B4 (staticcheck SA4xxx / SA5010 / SA9006 and govet `ifaceassert`, `nilfunc`, `shift`, `bools`, `stringintconv`, `unsafeptr`:
 * suspicious expressions). Fixtures mark the expected problem of a line with a trailing `// want [ID] message`; every other line must
 * stay quiet (only B4 ids are compared). Quick fixes are checked text before / after.
 */
class GoStaticcheckExpressionRulesTest : GoSemanticIdeTestBase() {

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(GoRules())
        Disposer.register(testRootDisposable) {
            GoRuleSettings.getInstance(project).loadState(GoRuleSettings.State())
            GoRuleSet.getInstance(project).invalidate()
        }
    }

    private val settings: GoRuleSettings get() = GoRuleSettings.getInstance(project)

    private fun check(text: String, fileName: String = "sc.go") {
        val source = text.trimIndent() + "\n"
        val expected = source.lines().mapIndexedNotNull { i, line ->
            line.substringAfter("// want ", "").takeIf { it.isNotEmpty() }?.let { "${i + 1}: $it" }
        }
        myFixture.configureByText(fileName, source)
        val document = myFixture.editor.document
        val actual = myFixture.doHighlighting().filter { B4.containsMatchIn(it.description ?: "") }
            .map { "${document.getLineNumber(it.startOffset) + 1}: ${it.description}" }
        assertEquals(expected.sorted().joinToString("\n"), actual.sorted().joinToString("\n"))
    }

    private fun fix(before: String, fix: String, after: String) {
        myFixture.configureByText("fix.go", before.trimIndent() + "\n")
        val offered = myFixture.availableIntentions
        myFixture.launchAction(offered.firstOrNull { it.text == fix } ?: error("$fix not in ${offered.map { it.text }}"))
        myFixture.checkResult(after.trimIndent() + "\n")
    }

    fun testAllRegistered() {
        val rules = GoRuleSet.getInstance(project).allRules.associateBy { it.id }
        for (id in IDS) {
            val rule = rules[id] ?: error("$id is not registered")
            val linter = if (id.startsWith("govet:")) "govet" else "staticcheck"
            assertEquals(id, linter, rule.linter)
            assertTrue(id, rule.enabledByDefault)
            assertEquals(id, GoRuleLevel.WARNING, rule.defaultLevel)
            assertEquals(id, GoRuleScope.EXPRESSION, rule.scope)
            assertTrue(id, GoRuleNeed.TYPES in rule.needs || id in SYNTAX)
            assertTrue(id, rule.description.isNotBlank() && rule.title.isNotBlank())
        }
    }

    fun testSA4000IdenticalOperands() = check("""
        package sc

        import "math/rand"

        type T struct{ A int }

        var _ = T{} == T{}

        func g() int { return 0 }

        func f(x, y int, a bool, s []int, p *T, fl float64, arr [2]float64) {
        	_ = x == x // want [SA4000] identical expressions on the left and right side of the '==' operator
        	_ = a && a // want [SA4000] identical expressions on the left and right side of the '&&' operator
        	_ = x-x // want [SA4000] identical expressions on the left and right side of the '-' operator
        	_ = p.A != p.A // want [SA4000] identical expressions on the left and right side of the '!=' operator
        	_ = s[0] < s[ 0 ] // want [SA4000] identical expressions on the left and right side of the '<' operator
        	_ = len(s) >= len(s) // want [SA4000] identical expressions on the left and right side of the '>=' operator
        	_ = x &^ x // want [SA4000] identical expressions on the left and right side of the '&^' operator
        	_ = x + x
        	_ = x * x
        	_ = x == y
        	_ = fl == fl
        	_ = arr == arr
        	_ = rand.Intn(2) - rand.Intn(2)
        	_ = g() == g()
        }
    """)

    fun testSA4001IneffectiveCopy() = check("""
        package sc

        func f(p *int, x int) {
        	_ = &*p // want [SA4001] &*x will be simplified to x. It will not copy x.
        	_ = *&x // want [SA4001] *&x will be simplified to x. It will not copy x.
        	_ = *p
        	_ = &x
        	v := *p
        	_ = &v
        }
    """)

    fun testSA4003ExtremeComparison() = check("""
        package sc

        import "math"

        type ID uint32

        func f(u uint, u8 uint8, i8 int8, i int, id ID) {
        	_ = u < 0 // want [SA4003] no value of type uint is less than 0
        	_ = u >= 0 // want [SA4003] every value of type uint is >= 0
        	_ = 0 > u // want [SA4003] no value of type uint is less than 0
        	_ = id < 0 // want [SA4003] no value of type ID is less than 0
        	_ = u8 > math.MaxUint8 // want [SA4003] no value of type uint8 is greater than math.MaxUint8
        	_ = u8 <= 255 // want [SA4003] every value of type uint8 is <= math.MaxUint8
        	_ = i8 < math.MinInt8 // want [SA4003] no value of type int8 is less than math.MinInt8
        	_ = i8 >= math.MinInt8 // want [SA4003] every value of type int8 is >= math.MinInt8
        	_ = i < 0
        	_ = u > 0
        	_ = u == 0
        	_ = u8 < math.MaxUint8
        	_ = i8 > -128
        	const zero = 0
        	_ = u < zero
        }
    """)

    fun testSA4012NaN() = check("""
        package sc

        import (
        	"math"
        	m "math"
        )

        func f(x float64) {
        	_ = x == math.NaN() // want [SA4012] no value is equal to NaN, not even NaN itself
        	_ = m.NaN() != x // want [SA4012] no value is equal to NaN, not even NaN itself
        	_ = x < (math.NaN()) // want [SA4012] no value is equal to NaN, not even NaN itself
        	_ = math.IsNaN(x)
        	_ = x + math.NaN()
        	_ = x == math.Inf(1)
        }
    """)

    fun testSA4013DoubleNegation() = check("""
        package sc

        func f(b bool) {
        	_ = !!b // want [SA4013] negating a boolean twice has no effect; is this a typo?
        	_ = !b
        	_ = !(!b)
        }
    """)

    fun testSA4016SillyBitwise() = check("""
        package sc

        const (
        	FlagA = iota
        	FlagB
        )

        const Zero = 0

        func f(x int, u uint8) {
        	_ = x & 0 // want [SA4016] x & 0 always equals 0
        	_ = x | 0 // want [SA4016] x | 0 always equals x
        	_ = u ^ 0x0 // want [SA4016] u ^ 0x0 always equals u
        	_ = x & FlagA // want [SA4016] x & FlagA always equals 0; FlagA is defined as iota and has value 0, maybe FlagA is meant to be 1 << iota?
        	_ = x | FlagA // want [SA4016] x | FlagA always equals x; FlagA is defined as iota and has value 0, maybe FlagA is meant to be 1 << iota?
        	_ = x & Zero
        	_ = x & FlagB
        	_ = x << 0
        	_ = x & 1
        	_ = 0 | x
        }
    """)

    fun testSA4022AddressIsNil() = check("""
        package sc

        type T struct{}

        func f(x int, t T) {
        	_ = &x == nil // want [SA4022] the address of a variable cannot be nil
        	_ = &t != nil // want [SA4022] the address of a variable cannot be nil
        	p := &x
        	_ = p == nil
        	_ = nil == &x
        }
    """)

    fun testSA4024BuiltinNegative() = check("""
        package sc

        func f(s []int, c chan int) {
        	_ = len(s) < 0 // want [SA4024] builtin function len does not return negative values
        	_ = 0 > cap(c) // want [SA4024] builtin function cap does not return negative values
        	_ = len(s) <= 0
        	_ = len(s) == 0
        	_ = len(s) < 1
        }

        func g(s []int) {
        	len := func([]int) int { return -1 }
        	_ = len(s) < 0
        }
    """)

    fun testSA4025IntegerDivision() = check("""
        package sc

        func f(x int) {
        	_ = 1 / 2 // want [SA4025] the integer division '1 / 2' results in zero
        	var r float64 = 3/4 // want [SA4025] the integer division '3/4' results in zero
        	_ = 4 / 2
        	_ = 4 / 3
        	_ = 1.0 / 2
        	_ = x / 2
        	_ = r
        }
    """)

    fun testSA4026NegativeZero() = check("""
        package sc

        func f() {
        	_ = -0.0 // want [SA4026] in Go, the floating-point literal '-0.0' is the same as '0.0', it does not produce a negative zero
        	_ = -float64(0) // want [SA4026] in Go, the floating-point expression '-float64(0)' is the same as 'float64(0)', it does not produce a negative zero
        	_ = float32(-0) // want [SA4026] in Go, the floating-point expression 'float32(-0)' is the same as 'float32(0)', it does not produce a negative zero
        	_ = -0.5
        	_ = -0
        	_ = float64(-1)
        	_ = -float64(1)
        }
    """)

    fun testSA4028ModuloOne() = check("""
        package sc

        func f(x int) {
        	_ = x % 1 // want [SA4028] x % 1 is always zero
        	_ = x % 2
        	_ = x * 1
        }
    """)

    fun testSA4032BuildConstraints() = check("""
        //go:build linux && amd64

        package sc

        import "runtime"

        func f() {
        	_ = runtime.GOOS == "windows" // want [SA4032] due to the file's build constraints, runtime.GOOS will never equal "windows"
        	_ = runtime.GOARCH != "arm64" // want [SA4032] due to the file's build constraints, runtime.GOARCH will never equal "arm64"
        	_ = runtime.GOOS == "linux"
        	_ = runtime.GOOS == "android"
        	_ = runtime.GOOS == "zos"
        	_ = runtime.GOARCH == "amd64"
        	_ = "windows" == runtime.GOOS
        }
    """)

    fun testSA4032FileName() = check("""
        package sc

        import "runtime"

        func f() bool {
        	return runtime.GOOS == "darwin" // want [SA4032] due to the file's build constraints, runtime.GOOS will never equal "darwin"
        }

        func g() bool {
        	return runtime.GOARCH == "arm64" || runtime.GOOS == "linux"
        }
    """, fileName = "sc_linux.go")

    fun testSA4032UnknownTag() = check("""
        //go:build linux || foo

        package sc

        import "runtime"

        func f() bool {
        	return runtime.GOOS == "windows"
        }
    """)

    fun testSA4032NoConstraints() = check("""
        package sc

        import "runtime"

        func f() bool {
        	return runtime.GOOS == "windows"
        }
    """)

    fun testSA9006DubiousShift() = check("""
        package sc

        func f(a uint32, b int8, c int, d uint64) {
        	_ = uint64(a << 40) // want [SA9006] shifting 32-bit value by 40 bits will always clear it
        	_ = b >> 8 // want [SA9006] shifting 8-bit value by 8 bits will always clear it
        	a <<= 32 // want [SA9006] shifting 32-bit value by 32 bits will always clear it
        	_ = c << 64 // want [govet:shift] c (64 bits) too small for shift of 64
        	_ = d << 63
        	_ = uint64(a) << 40
        	a >>= 31
        }
    """)

    fun testGovetShiftWhenSA9006IsOff() {
        settings.setEnabled("SA9006", false)
        check("""
            package sc

            func f(a uint32, u uint) {
            	_ = a << 40 // want [govet:shift] a (32 bits) too small for shift of 40
            	a <<= 32 // want [govet:shift] a (32 bits) too small for shift of 32
            	_ = u >> 64 // want [govet:shift] u (64 bits) too small for shift of 64
            	_ = u << 63
            	const k = ^uint(0) >> 63
            	if false {
            		_ = u << 64
            	}
            	if true {
            	} else {
            		_ = u << 64
            	}
            	switch {
            	case false:
            		_ = u << 64
            	}
            	switch 1 {
            	case 2:
            		_ = u << 64
            	case 1:
            		_ = u << 64 // want [govet:shift] u (64 bits) too small for shift of 64
            	}
            }
        """)
    }

    fun testGovetShiftTypeParameter() = check("""
        package sc

        func f[T int8 | int64](v T) T {
        	return v << 8 // want [govet:shift] v (may be 8 bits) too small for shift of 8
        }
    """)

    private val assertions = """
        package sc

        type A interface{ F() int }

        type B interface{ F() string }

        type C interface {
        	F() int
        	G()
        }

        type S struct{}

        func (S) F() int { return 0 }

        func f(a A, e any, s S) {
        	_ = a.(B) // want [SA5010] impossible type assertion; A and B contradict each other: wrong type for F method (have func() int, want func() string)
        	_, _ = a.(interface{ F() string }) // want [SA5010] impossible type assertion; A and interface{F() string} contradict each other: wrong type for F method (have func() int, want func() string)
        	_ = a.(C)
        	_ = e.(B)
        	_ = a.(S)
        	switch v := a.(type) {
        	case nil:
        	case B: // want [SA5010] impossible type assertion; A and B contradict each other: wrong type for F method (have func() int, want func() string)
        		_ = v
        	case C, S:
        	}
        }
    """

    fun testSA5010ImpossibleAssertion() = check(assertions)

    fun testGovetIfaceAssertWhenSA5010IsOff() {
        settings.setEnabled("SA5010", false)
        check(assertions.lines().joinToString("\n") { line ->
            when {
                "a.(B)" in line || "case B:" in line ->
                    line.substringBefore("// want") + "// want [govet:ifaceassert] impossible type assertion: no type can implement both A and B (conflicting types for F method)"
                "a.(interface{ F() string })" in line ->
                    line.substringBefore("// want") + "// want [govet:ifaceassert] impossible type assertion: no type can implement both A and interface{F() string} (conflicting types for F method)"
                else -> line
            }
        })
    }

    fun testSA5010Generics() = check("""
        package sc

        type G[T any] interface{ F() T }

        type A interface{ F() int }

        func f[T any](a A, g G[string], x interface{ F() T }) {
        	_ = a.(G[string])
        	_ = g.(A)
        	_ = a.(interface{ F() T })
        }
    """)

    fun testGovetNilFunc() = check("""
        package sc

        type T struct{ fn func() }

        func (T) M() {}

        func g() {}

        func f(t T, fv func()) {
        	_ = g == nil // want [govet:nilfunc] comparison of function g == nil is always false
        	_ = nil != g // want [govet:nilfunc] comparison of function g != nil is always true
        	_ = t.M == nil // want [govet:nilfunc] comparison of function M == nil is always false
        	_ = t.fn == nil
        	_ = fv == nil
        }
    """)

    fun testGovetBools() = check("""
        package sc

        func g() bool { return true }

        func f(x int, a, b bool, s string) {
        	_ = a || b || a // want [govet:bools] redundant or: a || a
        	_ = x != 1 || x != 2 // want [govet:bools] suspect or: x != 1 || x != 2
        	_ = x == 1 && (x == 2) // want [govet:bools] suspect and: x == 1 && x == 2
        	_ = x == 1 || x == 2
        	_ = x != 1 && x != 2
        	_ = g() || b || g()
        	_ = s == "a" || s == "a" // want [SA4000] identical expressions on the left and right side of the '||' operator
        }
    """)

    fun testGovetBoolsWhenSA4000IsOff() {
        settings.setEnabled("SA4000", false)
        check("""
            package sc

            func f(s string) {
            	_ = s == "a" || s == "a" // want [govet:bools] redundant or: s == "a" || s == "a"
            }
        """)
    }

    fun testGovetStringIntConv() = check("""
        package sc

        type A string

        type B = string

        type C int

        func f(i int, r rune, b byte, c C, u uintptr, i64 int64, i32 int32) {
        	const p = 0
        	_ = string(i) // want [govet:stringintconv] conversion from int to string yields a string of one rune, not a string of digits
        	_ = string(p) // want [govet:stringintconv] conversion from untyped int to string yields a string of one rune, not a string of digits
        	_ = A(c) // want [govet:stringintconv] conversion from C (int) to A (string) yields a string of one rune, not a string of digits
        	_ = B(u) // want [govet:stringintconv] conversion from uintptr to B (string) yields a string of one rune, not a string of digits
        	_ = string(i64) // want [govet:stringintconv] conversion from int64 to string yields a string of one rune, not a string of digits
        	_ = string(r)
        	_ = string(b)
        	_ = string(i32)
        	_ = string('a')
        	_ = string(rune(i))
        	_ = []byte("x")
        }
    """)

    fun testGovetUnsafePointer() = check("""
        package sc

        import (
        	"reflect"
        	"unsafe"
        )

        func f(p unsafe.Pointer, u uintptr, v reflect.Value, s []byte, h *reflect.SliceHeader) {
        	_ = unsafe.Pointer(u) // want [govet:unsafeptr] possible misuse of unsafe.Pointer
        	_ = unsafe.Pointer(uintptr(p) + 8)
        	_ = unsafe.Pointer(uintptr(p) + u)
        	_ = unsafe.Pointer(v.Pointer())
        	_ = unsafe.Pointer(h.Data)
        	hdr := *(*reflect.SliceHeader)(unsafe.Pointer(&s)) // want [govet:unsafeptr] possible misuse of reflect.SliceHeader
        	_ = &hdr // want [govet:unsafeptr] possible misuse of reflect.SliceHeader
        	_ = unsafe.Pointer(&s)
        }
    """)

    fun testUnknownTypesStayQuiet() = check("""
        package sc

        func f() {
        	_ = undef == undef
        	_ = undef < 0
        	_ = undef << 64
        	_ = string(undef)
        	_ = unsafe.Pointer(undef)
        	_ = undef.(Undef)
        	_ = undef == nil
        	_ = undef & 0
        }
    """)

    // ---- suppression and switching off

    fun testNolintAndLintIgnore() = check("""
        package sc

        func g() {}

        func f(x int) {
        	_ = x == x //nolint:staticcheck
        	//lint:ignore SA4000 on purpose
        	_ = x == x
        	_ = x == x // want [SA4000] identical expressions on the left and right side of the '==' operator
        	_ = g == nil //nolint:govet
        	_ = g == nil // want [govet:nilfunc] comparison of function g == nil is always false
        }
    """)

    fun testDisabledRule() {
        settings.setEnabled("SA4028", false)
        check("""
            package sc

            func f(x int) int {
            	return x % 1
            }
        """)
    }

    // ---- quick fixes

    fun testFixIneffectiveCopy() = fix(
        "package sc\n\nfunc f(p *int) *int {\n\treturn &<caret>*p\n}",
        "Simplify &*x to x",
        "package sc\n\nfunc f(p *int) *int {\n\treturn p\n}",
    )

    fun testFixSingleNegation() = fix(
        "package sc\n\nfunc f(b bool) bool {\n\treturn !<caret>!b\n}",
        "Turn into single negation",
        "package sc\n\nfunc f(b bool) bool {\n\treturn !b\n}",
    )

    fun testFixRemoveDoubleNegation() = fix(
        "package sc\n\nfunc f(b bool) bool {\n\treturn !<caret>!b\n}",
        "Remove double negation",
        "package sc\n\nfunc f(b bool) bool {\n\treturn b\n}",
    )

    fun testFixSillyBitwise() = fix(
        "package sc\n\nfunc f(x int) int {\n\treturn x <caret>| 0\n}",
        "Replace with x",
        "package sc\n\nfunc f(x int) int {\n\treturn x\n}",
    )

    fun testFixNaN() = fix(
        "package sc\n\nimport m \"math\"\n\nfunc f(x float64) bool {\n\treturn x <caret>!= m.NaN()\n}",
        "Use math.IsNaN",
        "package sc\n\nimport m \"math\"\n\nfunc f(x float64) bool {\n\treturn !m.IsNaN(x)\n}",
    )

    fun testFixNegativeZeroAddsImport() = fix(
        "package sc\n\nfunc f() float64 {\n\treturn <caret>-0.0\n}",
        "Use math.Copysign to create negative zero",
        "package sc\n\nimport \"math\"\n\nfunc f() float64 {\n\treturn math.Copysign(0, -1)\n}",
    )

    fun testFixNegativeZeroFloat32() = fix(
        "package sc\n\nimport \"math\"\n\nvar _ = math.Pi\n\nfunc f() float32 {\n\treturn <caret>float32(-0)\n}",
        "Use math.Copysign to create negative zero",
        "package sc\n\nimport \"math\"\n\nvar _ = math.Pi\n\nfunc f() float32 {\n\treturn float32(math.Copysign(0, -1))\n}",
    )

    fun testFixStringIntConvSprint() = fix(
        "package sc\n\nfunc f(i int) string {\n\treturn <caret>string(i)\n}",
        "Format the number as a decimal",
        "package sc\n\nimport \"fmt\"\n\nfunc f(i int) string {\n\treturn fmt.Sprint(i)\n}",
    )

    fun testFixStringIntConvNamedTarget() = fix(
        "package sc\n\nimport \"fmt\"\n\ntype A string\n\nvar _ = fmt.Sprint\n\nfunc f(i int) A {\n\treturn <caret>A(i)\n}",
        "Format the number as a decimal",
        "package sc\n\nimport \"fmt\"\n\ntype A string\n\nvar _ = fmt.Sprint\n\nfunc f(i int) A {\n\treturn A(fmt.Sprint(i))\n}",
    )

    fun testFixStringIntConvRune() = fix(
        "package sc\n\nfunc f(i int) string {\n\treturn <caret>string(i)\n}",
        "Convert a single rune to a string",
        "package sc\n\nfunc f(i int) string {\n\treturn string(rune(i))\n}",
    )

    private companion object {
        val IDS = listOf(
            "SA4000", "SA4001", "SA4003", "SA4012", "SA4013", "SA4016", "SA4022", "SA4024", "SA4025", "SA4026", "SA4028", "SA4032", "SA9006", "SA5010",
            "govet:ifaceassert", "govet:nilfunc", "govet:shift", "govet:bools", "govet:stringintconv", "govet:unsafeptr",
        )
        val SYNTAX = setOf("SA4001", "SA4013", "SA4028")
        val B4 = Regex("""^\[(SA40(00|01|03|12|13|16|22|24|25|26|28|32)|SA9006|SA5010|govet:(ifaceassert|nilfunc|shift|bools|stringintconv|unsafeptr))]""")
    }
}
