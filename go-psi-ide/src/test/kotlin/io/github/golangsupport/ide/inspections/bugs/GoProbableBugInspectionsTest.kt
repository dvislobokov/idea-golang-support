package io.github.golangsupport.ide.inspections.bugs

import io.github.golangsupport.ide.inspections.GoParityInspectionTestBase

/** GoLand's "Probable bugs" and "Control flow issues" checks of PLAN.md G7 (first line): reports, negatives, quick fixes. */
class GoProbableBugInspectionsTest : GoParityInspectionTestBase() {

    // --- GoDeferGo ---

    fun testDeferGo() = doHighlight(
        """
        package p

        func f() {
        	<weak_warning descr="defer should not call recover() directly">defer recover()</weak_warning>
        	<weak_warning descr="go should not call panic() directly">go panic("x")</weak_warning>
        	<weak_warning descr="defer should not call panic() directly">defer panic("y")</weak_warning>
        	defer func() { recover() }()
        }

        func g(recover func()) {
        	defer recover()
        }
        """,
        GoDeferGoInspection(),
    )

    fun testDeferGoWrapFix() = doFix(
        "package p\n\nfunc f() {\n\tdefer <caret>recover()\n}",
        "Wrap in a function literal",
        "package p\n\nfunc f() {\n\tdefer func() { recover() }()\n}",
        GoDeferGoInspection(),
    )

    // --- GoImportUsedAsName ---

    fun testImportUsedAsName() = doHighlight(
        """
        package p

        import (
        	"strings"
        	_ "embed"
        	str "strconv"
        )

        func f(<warning descr="Parameter 'strings' collides with imported package name">strings</warning> []string) {}

        func g() {
        	<warning descr="Variable 'str' collides with imported package name">str</warning> := 1
        	embed := 2
        	strconv := 3
        	_, _, _ = str, embed, strconv
        }

        var _ = strings.ToUpper
        """,
        GoImportUsedAsNameInspection(),
    )

    fun testImportUsedAsNameOffersRename() {
        val texts = offered("package p\n\nimport \"strings\"\n\nfunc f(<caret>strings int) {}", GoImportUsedAsNameInspection())
        assertTrue(texts.toString(), "Rename" in texts)
    }

    // --- GoReservedWordUsedAsName ---

    fun testReservedWordUsedAsName() = doHighlight(
        """
        package p

        func f() {
        	<warning descr="Variable 'len' collides with the 'builtin' function">len</warning> := 3
        	_ = len
        }

        type <warning descr="Type 'error' collides with the 'builtin' type">error</warning> struct{}

        func g(<warning descr="Parameter 'new' collides with the 'builtin' function">new</warning> int) {}

        const <warning descr="Constant 'true' collides with the 'builtin' constant">true</warning> = 0

        type S struct{ len int }

        func (S) copy() {}
        """,
        GoReservedWordUsedAsNameInspection(),
    )

    // --- GoIrregularIota ---

    /** GoLand's semantics (its description, and probe2/iota3.go seen live): the same list with `iota` after only specs without a list. */
    fun testIrregularIota() = doHighlight(
        """
        package p

        type Weekday int

        const (
        	a = iota
        	b
        	<weak_warning descr="Irregular usage of 'iota'">c = iota</weak_warning>
        )

        const (
        	a1, aa1 = iota, iota
        	b1, bb1
        	<weak_warning descr="Irregular usage of 'iota'">c1, cc1 = iota, iota</weak_warning>
        )

        const (
        	j Weekday = iota
        	k
        	<weak_warning descr="Irregular usage of 'iota'">l Weekday = iota</weak_warning>
        )

        const (
        	M0 = iota
        	_
        	<weak_warning descr="Irregular usage of 'iota'">M2 = iota</weak_warning>
        )
        """,
        GoIrregularIotaInspection(),
    )

    fun testIrregularIotaQuietForms() = doHighlight(
        """
        package p

        const Single = iota

        const (
        	A0 = iota
        	A1 = iota
        	A2
        )

        const (
        	d = iota * 2
        	e = iota * 2
        )

        const (
        	D0 = iota * 2
        	D1
        	D2 = iota * 2
        )

        const (
        	f = 1 << iota
        	g
        	i = 1 << iota
        )

        const (
        	H0 = iota
        	H1 = 7
        	H2
        )

        const (
        	C0 = iota
        	C1 = 10
        	C2 = iota
        )

        const (
        	x, xx = iota, iota
        	y, yy
        	z, zz = iota + 40, iota
        )

        const (
        	t0 int = iota
        	t1
        	t2 = iota
        )
        """,
        GoIrregularIotaInspection(),
    )

    fun testIrregularIotaRemoveRepetition() = doFix(
        "package p\n\nconst (\n\tA = iota\n\tB\n\tC = <caret>iota\n)",
        "Remove the repeated expression",
        "package p\n\nconst (\n\tA = iota\n\tB\n\tC\n)",
        GoIrregularIotaInspection(),
    )

    // --- GoMixedReceiverTypes ---

    /** GoLand (seen live): the name of every method of the type is reported. */
    fun testMixedReceiverTypes() = doHighlight(
        """
        package p

        type T struct{}

        func (t *T) <weak_warning descr="$MIXED_T">A</weak_warning>() {}

        func (t *T) <weak_warning descr="$MIXED_T">B</weak_warning>() {}

        func (t T) <weak_warning descr="$MIXED_T">C</weak_warning>() {}

        type U struct{}

        func (u U) <weak_warning descr="$MIXED_U">A</weak_warning>() {}

        func (u *U) <weak_warning descr="$MIXED_U">B</weak_warning>() {}

        type V struct{}

        func (v V) A() {}

        func (v V) B() {}
        """,
        GoMixedReceiverTypesInspection(),
    )

    fun testMixedReceiverTypesAcrossFiles() {
        myFixture.addFileToProject("b.go", "package p\n\nfunc (t *T) A() {}\n\nfunc (t *T) B() {}\n")
        doHighlight("package p\n\ntype T struct{}\n\nfunc (t T) <weak_warning descr=\"$MIXED_T\">C</weak_warning>() {}", GoMixedReceiverTypesInspection())
    }

    fun testMixedReceiverNoFixOnTheMajority() {
        val texts = offered("package p\n\ntype T struct{}\n\nfunc (t *T) <caret>A() {}\n\nfunc (t *T) B() {}\n\nfunc (t T) C() {}", GoMixedReceiverTypesInspection())
        assertFalse(texts.toString(), texts.any { it.startsWith("Change receiver") })
    }

    fun testMixedReceiverToPointerFix() = doFix(
        "package p\n\ntype T struct{}\n\nfunc (t *T) A() {}\n\nfunc (t T) <caret>C() {}",
        "Change receiver to pointer",
        "package p\n\ntype T struct{}\n\nfunc (t *T) A() {}\n\nfunc (t *T) C() {}",
        GoMixedReceiverTypesInspection(),
    )

    // --- GoTypeAssertionOnErrors ---

    fun testTypeAssertionOnErrors() = doHighlight(
        """
        package p

        type MyErr struct{}

        func (*MyErr) Error() string { return "" }

        func f(err error, x any) {
        	if e, ok := <weak_warning descr="Type assertion on errors fails on wrapped errors">err.(*MyErr)</weak_warning>; ok {
        		_ = e
        	}
        	_ = x.(*MyErr)
        }

        func (m *MyErr) Is(err error) bool {
        	_, ok := err.(*MyErr)
        	return ok
        }
        """,
        GoTypeAssertionOnErrorsInspection(),
    )

    fun testTypeAssertionOnErrorsUseErrorsAs() = doFix(
        """
        package p

        import "fmt"

        type MyErr struct{}

        func (*MyErr) Error() string { return "" }

        func f(err error) {
        	if e, ok := err.(*My<caret>Err); ok {
        		fmt.Println(e)
        	}
        }
        """,
        "Replace with 'errors.As'",
        """
        package p

        import (
        	"errors"
        	"fmt"
        )

        type MyErr struct{}

        func (*MyErr) Error() string { return "" }

        func f(err error) {
        	var e *MyErr
        	if errors.As(err, &e) {
        		fmt.Println(e)
        	}
        }
        """,
        GoTypeAssertionOnErrorsInspection(),
    )

    /** Regression: an `e` already in the block (or used after the `if`) must not be redeclared or shadowed: the new variable is `e2`. */
    fun testTypeAssertionOnErrorsUseErrorsAsPicksFreeName() = doFix(
        """
        package p

        import (
        	"errors"
        	"fmt"
        )

        type MyErr struct{}

        func (*MyErr) Error() string { return "" }

        func f(err error) {
        	e := errors.New("x")
        	if e, ok := err.(*My<caret>Err); ok {
        		fmt.Println(e)
        	}
        	fmt.Println(e)
        }
        """,
        "Replace with 'errors.As'",
        """
        package p

        import (
        	"errors"
        	"fmt"
        )

        type MyErr struct{}

        func (*MyErr) Error() string { return "" }

        func f(err error) {
        	e := errors.New("x")
        	var e2 *MyErr
        	if errors.As(err, &e2) {
        		fmt.Println(e2)
        	}
        	fmt.Println(e)
        }
        """,
        GoTypeAssertionOnErrorsInspection(),
    )

    // --- GoAssignmentToReceiver ---

    /** GoLand (seen live): only assignments to the receiver itself; `c.name = v` is not reported. */
    fun testAssignmentToReceiver() = doHighlight(
        """
        package p

        type C struct {
        	X int
        	p *C
        	m map[string]int
        }

        func (c C) Set(v int) {
        	c.X = v
        	<weak_warning descr="Assignment to the method receiver doesn't propagate to other calls">c</weak_warning> = C{}
        }

        func (c C) With(x int) C {
        	c.X = x
        	return c
        }

        func (c *C) Reset() {
        	<weak_warning descr="Assignment to the method receiver propagates only to callees but not to callers">c</weak_warning> = nil
        }

        func (c C) Shared() {
        	c.p.X = 1
        	c.m["a"] = 1
        }

        func (c *C) Deref() {
        	*c = C{}
        	c.X = 3
        }

        type N int

        func (n N) Inc() {
        	<weak_warning descr="Assignment to the method receiver doesn't propagate to other calls">n</weak_warning>++
        }
        """,
        GoAssignmentToReceiverInspection(),
    )

    private companion object {
        const val MIXED_T = "Struct T has methods on both value and pointer receivers. Such usage is not recommended by the Go Documentation."
        const val MIXED_U = "Struct U has methods on both value and pointer receivers. Such usage is not recommended by the Go Documentation."
    }
}
