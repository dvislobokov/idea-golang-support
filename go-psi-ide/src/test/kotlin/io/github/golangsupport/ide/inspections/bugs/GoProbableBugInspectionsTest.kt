package io.github.golangsupport.ide.inspections.bugs

import io.github.golangsupport.ide.inspections.GoParityInspectionTestBase

/** GoLand's "Probable bugs" and "Control flow issues" checks of PLAN.md G7 (first line): reports, negatives, quick fixes. */
class GoProbableBugInspectionsTest : GoParityInspectionTestBase() {

    // --- GoDeferGo ---

    fun testDeferGo() = doHighlight(
        """
        package p

        func f() {
        	defer <weak_warning descr="'recover()' is called directly by 'defer' and does not stop a panic">recover()</weak_warning>
        	go <weak_warning descr="'panic()' is called directly by 'go'">panic("x")</weak_warning>
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
        	<warning descr="Variable 'len' collides with the builtin function">len</warning> := 3
        	_ = len
        }

        type <warning descr="Type 'error' collides with the builtin type">error</warning> struct{}

        func g(<warning descr="Parameter 'new' collides with the builtin function">new</warning> int) {}

        const <warning descr="Constant 'true' collides with the builtin constant">true</warning> = 0

        type S struct{ len int }

        func (S) copy() {}
        """,
        GoReservedWordUsedAsNameInspection(),
    )

    // --- GoIrregularIota ---

    fun testIrregularIota() = doHighlight(
        """
        package p

        const a = <weak_warning descr="'iota' in a single constant declaration is always 0">iota</weak_warning>

        const (
        	A = iota
        	B <weak_warning descr="Redundant repetition of the previous constant expression with 'iota'">= iota</weak_warning>
        	C
        )

        const (
        	X = iota * 10
        	Y = iota * 20
        	Z = 5
        )
        """,
        GoIrregularIotaInspection(),
    )

    fun testIrregularIotaReplaceWithZero() = doFix(
        "package p\n\nconst a = <caret>iota",
        "Replace with 0",
        "package p\n\nconst a = 0",
        GoIrregularIotaInspection(),
    )

    fun testIrregularIotaRemoveRepetition() = doFix(
        "package p\n\nconst (\n\tA = iota\n\tB = <caret>iota\n)",
        "Remove the repeated expression",
        "package p\n\nconst (\n\tA = iota\n\tB\n)",
        GoIrregularIotaInspection(),
    )

    // --- GoMixedReceiverTypes ---

    fun testMixedReceiverTypes() = doHighlight(
        """
        package p

        type T struct{}

        func (t *T) A() {}

        func (t *T) B() {}

        func (t <weak_warning descr="Methods of 'T' have both value and pointer receivers">T</weak_warning>) C() {}

        type U struct{}

        func (u <weak_warning descr="Methods of 'U' have both value and pointer receivers">U</weak_warning>) A() {}

        func (u *U) B() {}

        type V struct{}

        func (v V) A() {}

        func (v V) B() {}
        """,
        GoMixedReceiverTypesInspection(),
    )

    fun testMixedReceiverTypesAcrossFiles() {
        myFixture.addFileToProject("b.go", "package p\n\nfunc (t *T) A() {}\n\nfunc (t *T) B() {}\n")
        doHighlight("package p\n\ntype T struct{}\n\nfunc (t <weak_warning descr=\"Methods of 'T' have both value and pointer receivers\">T</weak_warning>) C() {}", GoMixedReceiverTypesInspection())
    }

    fun testMixedReceiverToPointerFix() = doFix(
        "package p\n\ntype T struct{}\n\nfunc (t *T) A() {}\n\nfunc (t <caret>T) C() {}",
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

    fun testAssignmentToReceiver() = doHighlight(
        """
        package p

        type C struct {
        	X int
        	p *C
        	m map[string]int
        }

        func (c C) Set() {
        	<weak_warning descr="Assignment to a field of value receiver 'c' is lost when the method returns">c.X</weak_warning> = 1
        }

        func (c C) With(x int) C {
        	c.X = x
        	return c
        }

        func (c *C) Reset() {
        	<weak_warning descr="Assignment to method receiver 'c' does not propagate to callers">c</weak_warning> = nil
        }

        func (c C) Shared() {
        	c.p.X = 1
        	c.m["a"] = 1
        }

        func (c *C) Deref() {
        	*c = C{}
        	c.X = 3
        }
        """,
        GoAssignmentToReceiverInspection(),
    )

    fun testAssignmentToReceiverPointerFix() = doFix(
        "package p\n\ntype C struct{ X int }\n\nfunc (c C) Set() {\n\t<caret>c.X = 1\n}",
        "Change receiver to pointer",
        "package p\n\ntype C struct{ X int }\n\nfunc (c *C) Set() {\n\tc.X = 1\n}",
        GoAssignmentToReceiverInspection(),
    )
}
