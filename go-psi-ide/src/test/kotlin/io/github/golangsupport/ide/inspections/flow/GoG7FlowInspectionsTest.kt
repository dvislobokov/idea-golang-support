package io.github.golangsupport.ide.inspections.flow

import com.intellij.codeInspection.LocalInspectionTool
import io.github.golangsupport.ide.GoSemanticIdeTestBase

/** GoLand parity G7, data flow: constant conditions (`GoDfaConstantCondition`) and division by zero (`GoDivisionByZero`). */
class GoG7FlowInspectionsTest : GoSemanticIdeTestBase() {

    private fun doHighlight(text: String, vararg tools: LocalInspectionTool) {
        myFixture.enableInspections(*tools)
        myFixture.configureByText("a.go", text.trimIndent())
        myFixture.checkHighlighting(true, false, true)
    }

    fun testConstantConditionFromNilness() = doHighlight(
        """
        package p

        type T struct{ n int }

        func get() *T { return nil }

        func a(p *T) int {
        	if p == nil {
        		return 0
        	}
        	if <warning descr="Condition 'p != nil' is always 'true'">p != nil</warning> {
        		return p.n
        	}
        	q := &T{}
        	if <warning descr="Condition 'q == nil' is always 'false'">q == nil</warning> {
        		return 1
        	}
        	return 2
        }

        func idioms(p *T) int {
        	if p != nil {
        		return p.n
        	}
        	var r *T
        	if r == nil {
        		r = get()
        	}
        	if r != nil {
        		return r.n
        	}
        	return 0
        }
        """,
        GoConstantConditionInspection(),
    )

    // G10: the probe of GoLand 2026.2.3 (probe2/style.go `shadow`), GoBoolExpressions WARNING "Condition 'x > 0' is always 'true'"
    fun testConstantConditionOfTheShadowProbe() = doHighlight(
        """
        package p

        func shadow(ch chan int) {
        	x := 1
        	if <warning descr="Condition 'x > 0' is always 'true'">x > 0</warning> {
        		x := 2
        		_ = x
        	}
        	_ = x
        	select {
        	case n := <-ch:
        		_ = n
        	default:
        	}
        }
        """,
        GoConstantConditionInspection(),
    )

    fun testConstantConditionFromValues() = doHighlight(
        """
        package p

        import "runtime"

        const debug = false

        func a(k int) int {
        	n := 0
        	if <warning descr="Condition 'n == 0' is always 'true'">n == 0</warning> {
        		k++
        	}
        	t := 2
        	if <warning descr="Condition 't >= 3' is always 'false'">t >= 3</warning> {
        		k++
        	}
        	var m int
        	if <warning descr="Condition 'm > 0' is always 'false'">m > 0</warning> {
        		k++
        	}
        	if <warning descr="Condition '1 > 2' is always 'false'">1 > 2</warning> {
        		k++
        	}
        	return k
        }

        func idioms(k int) int {
        	i := 0
        	for i < k {
        		i++
        	}
        	n := 0
        	if k > 1 {
        		n = 1
        	}
        	if n == 0 {
        		k++
        	}
        	c := 0
        	p := &c
        	*p = 2
        	if c == 0 {
        		k++
        	}
        	if debug || runtime.GOOS == "windows" {
        		k++
        	}
        	if k == 0 {
        		k++
        	}
        	return k
        }
        """,
        GoConstantConditionInspection(),
    )

    fun testDivisionByZero() = doHighlight(
        """
        package p

        func a(n int, f float64) (int, float64) {
        	d := 0
        	r := n / <warning descr="Division by zero">d</warning>
        	r %= <warning descr="Division by zero">d</warning>
        	g := f / <warning descr="Division by zero">0</warning>
        	g /= <warning descr="Division by zero">0.0</warning>
        	return r, g
        }

        func idioms(n, k int, f float64) int {
        	d := 0
        	if k > 0 {
        		d = k
        	}
        	const zero = 0.0
        	_ = f / zero
        	_ = 1.0 / 2
        	return n / d
        }
        """,
        GoDivisionByZeroInspection(),
    )
}
