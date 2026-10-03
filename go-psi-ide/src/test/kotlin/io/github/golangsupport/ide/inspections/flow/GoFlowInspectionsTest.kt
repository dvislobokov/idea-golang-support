package io.github.golangsupport.ide.inspections.flow

import com.intellij.codeInspection.LocalInspectionTool
import io.github.golangsupport.ide.GoSemanticIdeTestBase

/**
 * The data-flow inspections (wave 4): error flow, nil flow, ineffectual assignments and lost cancel functions; each with its
 * common idioms as negative cases and its quick fix (text before, fix, text after).
 */
class GoFlowInspectionsTest : GoSemanticIdeTestBase() {

    private fun doHighlight(text: String, vararg tools: LocalInspectionTool) {
        myFixture.enableInspections(*tools)
        myFixture.configureByText("a.go", text.trimIndent() + "\n")
        myFixture.checkHighlighting(true, false, true)
    }

    private fun doFix(before: String, fix: String, after: String, vararg tools: LocalInspectionTool) {
        myFixture.enableInspections(*tools)
        myFixture.configureByText("a.go", before.trimIndent() + "\n")
        val offered = myFixture.availableIntentions
        myFixture.launchAction(offered.singleOrNull { it.text == fix } ?: error("$fix not in ${offered.map { it.text }}"))
        myFixture.checkResult(after.trimIndent() + "\n")
    }

    private val helpers = """

        func f() (int, error) { return 0, nil }
        func g() error        { return nil }
        func use(...any)      {}
    """.trimIndent()

    // --- error overwritten ---

    fun testErrorOverwritten() = doHighlight(
        """
        package p

        func a() error {
        	_, <weak_warning descr="err is overwritten before being checked">err</weak_warning> := f()
        	_, err = f()
        	return err
        }

        func b(c bool) error {
        	<weak_warning descr="err is overwritten before being checked">err</weak_warning> := g()
        	if c {
        		err = g()
        	} else {
        		_, err = f()
        	}
        	return err
        }

        func resetByConversion() (int, error) {
        	n, err := 0, error(nil)
        	n, err = f()
        	return n, err
        }

        func readOnSomePath() (int, error) {
        	n, err := f()
        	if n == 0 {
        		return 0, err
        	}
        	n, err = f()
        	return n, err
        }

        func lastErrorWins(xs []int) error {
        	var last error
        	for range xs {
        		last = g()
        	}
        	return last
        }

        func idioms(xs []int) error {
        	if err := g(); err != nil {
        		return err
        	}
        	err := g()
        	if err != nil {
        		return err
        	}
        	err = g()
        	if err != nil {
        		return err
        	}
        	for range xs {
        		err = g()
        		if err != nil {
        			return err
        		}
        	}
        	n, err := f()
        	if n > 0 {
        		err = nil
        	}
        	if err != nil {
        		return err
        	}
        	err = g()
        	err = wrap(err)
        	return err
        }

        func closure() error {
        	err := g()
        	defer func() { use(err) }()
        	err = g()
        	return err
        }

        func wrap(err error) error { return err }
        """ + helpers,
        GoErrorOverwrittenInspection(),
    )

    // --- wrong error checked ---

    fun testWrongErrorChecked() = doHighlight(
        """
        package p

        func a() (int, error) {
        	var err2 error
        	use(err2)
        	x, err := f()
        	if err != nil {
        		return 0, err
        	}
        	y, err2 := f()
        	if <weak_warning descr="err2 is not checked; the condition tests err">err</weak_warning> != nil {
        		return 0, err
        	}
        	return x + y, nil
        }

        func ok() (int, error) {
        	x, err := f()
        	if err != nil {
        		return 0, err
        	}
        	y, err2 := f()
        	if err2 != nil {
        		return 0, err2
        	}
        	z, err := f()
        	if err != nil || z == 0 {
        		return 0, err
        	}
        	return x + y + z, nil
        }

        func unchecked() (int, error) {
        	x, err := f()
        	y, err2 := f()
        	if err != nil {
        		return 0, err
        	}
        	return x + y, err2
        }
        """ + helpers,
        GoWrongErrorCheckedInspection(),
    )

    fun testCheckTheNewErrorFix() = doFix(
        """
        package p

        func a() (int, error) {
        	var err2 error
        	use(err2)
        	x, err := f()
        	if err != nil {
        		return 0, err
        	}
        	y, err2 := f()
        	if e<caret>rr != nil {
        		return 0, err
        	}
        	return x + y, nil
        }
        """ + helpers,
        "Check 'err2' instead",
        """
        package p

        func a() (int, error) {
        	var err2 error
        	use(err2)
        	x, err := f()
        	if err != nil {
        		return 0, err
        	}
        	y, err2 := f()
        	if err2 != nil {
        		return 0, err
        	}
        	return x + y, nil
        }
        """ + helpers,
        GoWrongErrorCheckedInspection(),
    )

    // --- nil error returned ---

    fun testNilErrorReturn() = doHighlight(
        """
        package p

        func a() (int, error) {
        	n, err := f()
        	if err != nil {
        		return 0, <weak_warning descr="error is not nil but nil is returned">nil</weak_warning>
        	}
        	return n, nil
        }

        func b() error {
        	if err := g(); err == nil {
        		use()
        	} else {
        		return <weak_warning descr="error is not nil but nil is returned">nil</weak_warning>
        	}
        	return nil
        }

        func idioms() (int, error) {
        	n, err := f()
        	if err != nil {
        		return 0, err
        	}
        	if err := g(); err != nil {
        		use(err)
        		return 0, nil
        	}
        	if err := g(); err != nil {
        		err = nil
        		return 0, nil
        	}
        	if err := g(); err == nil {
        		return n, nil
        	}
        	if err := g(); err != nil && n > 0 {
        		return 0, wrap(err)
        	}
        	return n, nil
        }

        func noError() int {
        	if err := g(); err != nil {
        		return 0
        	}
        	return 1
        }

        func wrap(err error) error { return err }
        """ + helpers,
        GoNilErrorReturnInspection(),
    )

    // --- defer before the error check ---

    private val closer = """

        type File struct{ Body *File }

        func (f *File) Close() error { return nil }

        func open() (*File, error) { return &File{}, nil }
    """.trimIndent()

    fun testDeferBeforeErrorCheck() = doHighlight(
        """
        package p

        func a() error {
        	f, err := open()
        	defer <warning descr="f is used before err is checked; f may be nil">f.Close()</warning>
        	if err != nil {
        		return err
        	}
        	return nil
        }

        func b() error {
        	resp, err := open()
        	defer <warning descr="resp is used before err is checked; resp may be nil">resp.Body.Close()</warning>
        	return err
        }

        func idioms() error {
        	f, err := open()
        	if err != nil {
        		return err
        	}
        	defer f.Close()
        	g, err := open()
        	if err != nil || g == nil {
        		return err
        	}
        	defer g.Close()
        	return nil
        }
        """ + closer,
        GoDeferBeforeErrorCheckInspection(),
    )

    fun testMoveDeferFix() = doFix(
        """
        package p

        func a() error {
        	f, err := open()
        	defer f.Cl<caret>ose()
        	if err != nil {
        		return err
        	}
        	return nil
        }
        """ + closer,
        "Move defer after the error check",
        """
        package p

        func a() error {
        	f, err := open()
        	if err != nil {
        		return err
        	}
        	defer f.Close()
        	return nil
        }
        """ + closer,
        GoDeferBeforeErrorCheckInspection(),
    )

    // --- nil dereference ---

    fun testNilDereference() = doHighlight(
        """
        package p

        import "unsafe"

        type T struct{ X int }

        func (t T) Value() int { return t.X }
        func (t *T) Ptr() int  { return 0 }

        type I interface{ M() }

        func a(p *T, m map[string]int, fn func(), i I) {
        	if p == nil {
        		use(<warning descr="nil dereference of p">p</warning>.X)
        		use(<warning descr="nil dereference of p">p</warning>.Value())
        		use(p.Ptr())
        		use(*<warning descr="nil dereference of p">p</warning>)
        	}
        	if m == nil {
        		use(m["a"])
        		<warning descr="write to nil map m">m</warning>["a"] = 1
        	}
        	if fn == nil {
        		<warning descr="call of nil function fn">fn</warning>()
        	}
        	if i == nil {
        		<warning descr="nil dereference of i">i</warning>.M()
        	}
        	var q *T
        	use(<warning descr="nil dereference of q">q</warning>.X)
        }

        func sizeOnly() uintptr {
        	var r *T
        	return unsafe.Sizeof(*r)
        }

        func idioms(p *T, q *T) int {
        	if p == nil {
        		return 0
        	}
        	use(p.X)
        	if q == nil {
        		q = &T{}
        	}
        	use(q.X)
        	if p != nil && p.X > 0 {
        		use(p.X)
        	}
        	var r *T
        	if r == nil || r.X > 0 {
        		use()
        	}
        	ok := r != nil && r.X > 0
        	use(ok)
        	f := func() { use(p.X) }
        	f()
        	return p.X
        }
        """ + helpers,
        GoNilDereferenceInspection(),
    )

    // --- ineffectual assignment ---

    fun testIneffectualAssignment() = doHighlight(
        """
        package p

        func a(xs []int, k int) int {
        	n := len(xs)
        	use(n)
        	<weak_warning descr="ineffectual assignment to n">n</weak_warning> = k + 1
        	n = k + 2
        	use(n)
        	m := 1
        	<weak_warning descr="ineffectual assignment to m">m</weak_warning>++
        	return n
        }

        func b(k int) (r int) {
        	<weak_warning descr="ineffectual assignment to r">r</weak_warning> = k
        	return 2
        }

        func literal(k int) {
        	go func() {
        		v := k
        		use(v)
        		<weak_warning descr="ineffectual assignment to v">v</weak_warning> = k * 2
        	}()
        }

        func idioms(c bool, xs []int) (r int, err error) {
        	x := 0
        	if c {
        		x = 1
        	} else {
        		x = 2
        	}
        	use(x)
        	var s string
        	s = "a"
        	use(s)
        	r = 5
        	if c {
        		return
        	}
        	for _, v := range xs {
        		r += v
        	}
        	y := 1
        	defer func() { use(y) }()
        	y = 2
        	z := 1
        	p := &z
        	z = 2
        	use(p)
        	for i := 0; i < len(xs); i++ {
        		use(i)
        	}
        	mode := modeDefault
        	switch {
        	case c:
        		mode = 1
        	default:
        		mode = 2
        	}
        	use(mode)
        	buf, w := []byte(nil), 0
        	buf, w = xs2(), 1
        	use(buf, w)
        	a, b := f()
        	a, b = f()
        	use(a, b)
        	return r, nil
        }

        const modeDefault = 0

        func xs2() []byte { return nil }

        func panics(c bool) int {
        	v := 1
        	if c {
        		v = 2
        		panic("x")
        	}
        	return v
        }
        """ + helpers,
        GoIneffectualAssignmentInspection(),
    )

    fun testIneffectualQuietOnSwapsAndGeneratedFiles() {
        doHighlight(
            """
            package p
    
            func swap(x, y int, c bool) int {
            	if c {
            		x, y = y, x
            	}
            	return x
            }
            """ + helpers,
            GoIneffectualAssignmentInspection(),
        )
        doHighlight(
            """
            // Code generated by rulegen; DO NOT EDIT.
    
            package p
    
            func dead(k int) int {
            	n := k
            	n = k + 1
            	return n
            }
            """.trimIndent() + helpers,
            GoIneffectualAssignmentInspection(),
        )
    }

    fun testIneffectualErrorIsReportedOnce() = doHighlight(
        """
        package p

        func a() error {
        	<weak_warning descr="err is overwritten before being checked">err</weak_warning> := g()
        	err = g()
        	return err
        }
        """ + helpers,
        GoIneffectualAssignmentInspection(), GoErrorOverwrittenInspection(),
    )

    fun testRemoveAssignmentFix() = doFix(
        """
        package p

        func a(xs []int, k int) int {
        	n := len(xs)
        	use(n)
        	<caret>n = k + 1
        	n = k + 2
        	return n
        }
        """ + helpers,
        "Remove assignment to 'n'",
        """
        package p

        func a(xs []int, k int) int {
        	n := len(xs)
        	use(n)
        	n = k + 2
        	return n
        }
        """ + helpers,
        GoIneffectualAssignmentInspection(),
    )

    // --- lost cancel ---

    fun testLostCancel() = doHighlight(
        """
        package p

        import (
        	"context"
        	"time"
        )

        func a(ctx context.Context) error {
        	ctx, <warning descr="the cancel function is not used on all paths (possible context leak)">cancel</warning> := context.WithTimeout(ctx, time.Second)
        	if err := g(); err != nil {
        		return err
        	}
        	defer cancel()
        	use(ctx)
        	return nil
        }

        func b(ctx context.Context) {
        	ctx, <warning descr="the cancel function returned by context.WithCancel should be called, not discarded, to avoid a context leak">_</warning> = context.WithCancel(ctx)
        	use(ctx)
        }

        func idioms(ctx context.Context) (context.Context, context.CancelFunc) {
        	c1, cancel1 := context.WithCancel(ctx)
        	defer cancel1()
        	c2, cancel2 := context.WithDeadline(c1, time.Now())
        	go func() { cancel2() }()
        	c3, cancel3 := context.WithTimeout(c2, time.Second)
        	if c3 == nil {
        		panic("x")
        	}
        	use(c3)
        	return c3, cancel3
        }
        """ + helpers,
        GoLostCancelInspection(),
    )
}
