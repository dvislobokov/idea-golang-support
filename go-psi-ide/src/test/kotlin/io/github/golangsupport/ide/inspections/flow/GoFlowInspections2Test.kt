package io.github.golangsupport.ide.inspections.flow

import com.intellij.codeInspection.LocalInspectionTool
import io.github.golangsupport.ide.GoSemanticIdeTestBase

/**
 * The second batch of data-flow inspections (wave 4): nil error returned, shadowed error, result used before the error check,
 * impossible nil checks, nil value with nil error, unreachable code. Idioms are negative cases, fixes are text before / after.
 */
class GoFlowInspections2Test : GoSemanticIdeTestBase() {

    /** Appended after the trimmed test text (not part of it: its indentation must not take part in `trimIndent`). */
    private val helpers = """

type T struct{ n int }
type R struct{}

func (R) Read(p []byte) (int, error) { return 0, nil }
func f() (int, error)               { return 0, nil }
func g() error                      { return nil }
func get() (*T, error)              { return &T{}, nil }
func next(p *T) *T                  { return p }
func use(...any)                    {}
"""

    private fun doHighlight(text: String, vararg tools: LocalInspectionTool) {
        myFixture.enableInspections(*tools)
        myFixture.configureByText("a.go", text.trimIndent() + "\n" + helpers)
        myFixture.checkHighlighting(true, false, true)
    }

    private fun doFix(before: String, fix: String, after: String, vararg tools: LocalInspectionTool) {
        myFixture.enableInspections(*tools)
        myFixture.configureByText("a.go", before.trimIndent() + "\n" + helpers)
        val offered = myFixture.availableIntentions
        myFixture.launchAction(offered.singleOrNull { it.text == fix } ?: error("$fix not in ${offered.map { it.text }}"))
        myFixture.checkResult(after.trimIndent() + "\n" + helpers)
    }

    // --- nil error returned ---

    fun testErrNilReturned() = doHighlight(
        """
        package p

        func a() (int, error) {
        	_, err := f()
        	if err == nil {
        		return 0, <weak_warning descr="err is nil here; nil is returned">err</weak_warning>
        	}
        	return 0, err
        }

        func b() (int, error) {
        	_, err := f()
        	if err != nil {
        		use(1)
        	} else {
        		return 1, <weak_warning descr="err is nil here; nil is returned">err</weak_warning>
        	}
        	return 0, nil
        }

        func idioms() (int, error) {
        	_, err := f()
        	if err != nil {
        		return 0, err
        	}
        	if err == nil {
        		return 1, nil
        	}
        	err = g()
        	if err == nil {
        		err = g()
        		return 2, err
        	}
        	if err := g(); err == nil {
        		return 3, nil
        	}
        	return 0, err
        }
        """, GoErrNilReturnedInspection(),
    )

    fun testErrNilReturnedFix() = doFix(
        """
        package p

        func a() (int, error) {
        	_, err := f()
        	if err == nil {
        		return 0, e<caret>rr
        	}
        	return 0, err
        }
        """, "Return nil",
        """
        package p

        func a() (int, error) {
        	_, err := f()
        	if err == nil {
        		return 0, nil
        	}
        	return 0, err
        }
        """, GoErrNilReturnedInspection(),
    )

    // --- shadowed error ---

    fun testShadowedError() = doHighlight(
        """
        package p

        func a(xs []int) error {
        	var err error
        	for range xs {
        		_, <warning descr="err declared in this block shadows the outer err; the outer value is returned">err</warning> := f()
        		if err != nil {
        			continue
        		}
        	}
        	return err
        }

        func b(c bool) error {
        	err := g()
        	if c {
        		<warning descr="err declared in this block shadows the outer err; the outer value is returned">err</warning> := g()
        		if err != nil {
        			use(1)
        		}
        	}
        	return err
        }

        func innerReturned(c bool) error {
        	var err error
        	if c {
        		_, err := f()
        		if err != nil {
        			return err
        		}
        	}
        	return err
        }

        func innerWrapped(c bool) error {
        	var err error
        	if c {
        		_, err := f()
        		if err != nil {
        			return wrap(err)
        		}
        	}
        	return err
        }

        func innerLogged(c bool) error {
        	var err error
        	if c {
        		_, err := f()
        		if err != nil {
        			use(err)
        		}
        	}
        	return err
        }

        func initOnly(c bool) error {
        	var err error
        	if err := g(); err != nil {
        		use(1)
        	}
        	return err
        }

        func noOuterRead(c bool) error {
        	var err error
        	use(err)
        	if c {
        		_, err := f()
        		if err != nil {
        			use(1)
        		}
        	}
        	return nil
        }

        func noOuter(c bool) error {
        	if c {
        		_, err := f()
        		if err != nil {
        			use(1)
        		}
        	}
        	return nil
        }

        func wrap(e error) error { return e }
        """, GoShadowedErrorInspection(),
    )

    // --- result used before the error check ---

    fun testResultUsedBeforeErrorCheck() = doHighlight(
        """
        package p

        func a() error {
        	t, err := get()
        	use(<weak_warning descr="t is used before err is checked">t</weak_warning>.n)
        	if err != nil {
        		return err
        	}
        	return nil
        }

        func b() error {
        	t, err := get()
        	use(<weak_warning descr="t is used before err is checked">t</weak_warning>)
        	if err != nil {
        		return err
        	}
        	return nil
        }

        func checkedFirst() error {
        	t, err := get()
        	if err != nil {
        		return err
        	}
        	use(t.n)
        	return nil
        }

        func compared() error {
        	t, err := get()
        	if t == nil || err != nil {
        		return err
        	}
        	return nil
        }

        func comparedFirst() error {
        	t, err := get()
        	if t != nil {
        		use(1)
        	}
        	if err != nil {
        		return err
        	}
        	use(t.n)
        	return nil
        }

        func reader(r R, p []byte) error {
        	n, err := r.Read(p)
        	use(n)
        	if err != nil {
        		return err
        	}
        	return nil
        }

        func notNilable() error {
        	n, err := f()
        	use(n)
        	if err != nil {
        		return err
        	}
        	return nil
        }

        func neverChecked() {
        	t, err := get()
        	use(t.n)
        	err = nil
        }

        func deferred() error {
        	t, err := get()
        	defer use(t)
        	if err != nil {
        		return err
        	}
        	return nil
        }
        """, GoResultUsedBeforeErrorCheckInspection(),
    )

    // --- impossible nil check ---

    fun testImpossibleNilCheck() = doHighlight(
        """
        package p

        func a() {
        	p := &T{}
        	if <weak_warning descr="p is never nil here; the condition is always false">p == nil</weak_warning> {
        		use(1)
        	}
        	m := make(map[string]int)
        	if <weak_warning descr="m is never nil here; the condition is always true">m != nil</weak_warning> {
        		use(2)
        	}
        	s := []int{1}
        	if <weak_warning descr="s is never nil here; the condition is always false">s == nil</weak_warning> {
        		use(3)
        	}
        }

        func b(p *T) {
        	if p != nil {
        		if <weak_warning descr="p is never nil here; the condition is always true">p != nil</weak_warning> {
        			use(p)
        		}
        	}
        }

        func c(p *T) {
        	if p == nil {
        		if <weak_warning descr="p is always nil here; the condition is always true">p == nil</weak_warning> {
        			use(p)
        		}
        	}
        }

        func callResult() {
        	t, _ := get()
        	if t == nil {
        		use(1)
        	}
        	u := next(&T{})
        	if u != nil {
        		use(2)
        	}
        }

        func param(p *T) {
        	if p == nil {
        		use(1)
        	}
        }

        func zeroValue() {
        	var p *T
        	if p == nil {
        		use(1)
        	}
        }

        func loop() {
        	p := &T{}
        	for i := 0; i < 3; i++ {
        		if p == nil {
        			use(1)
        		}
        		p = next(p)
        	}
        }

        func branches(c bool) {
        	var p *T
        	if c {
        		p = &T{}
        	}
        	if p != nil {
        		use(1)
        	}
        }

        func captured() {
        	p := &T{}
        	func() { p = nil }()
        	if p == nil {
        		use(1)
        	}
        }
        """, GoImpossibleNilCheckInspection(),
    )

    // --- nil value with nil error ---

    fun testNilValueNilError() = doHighlight(
        """
        package p

        func find() (*T, error) {
        	<weak_warning descr="nil value and nil error are returned together; return a sentinel error or a non-nil value">return nil, nil</weak_warning>
        }

        func GetAll(ok bool) ([]int, error) {
        	if ok {
        		<weak_warning descr="nil value and nil error are returned together; return a sentinel error or a non-nil value">return nil, nil</weak_warning>
        	}
        	return []int{}, nil
        }

        // lookup returns nil, nil when the key is missing.
        func lookup() (*T, error) {
        	return nil, nil
        }

        // other returns nil if there is nothing.
        func other() (*T, error) {
        	return nil, nil
        }

        func count() (int, error) {
        	return 0, nil
        }

        func withError() (*T, error) {
        	return nil, g()
        }

        func withValue() (*T, error) {
        	return &T{}, nil
        }
        """, GoNilValueNilErrorInspection(),
    )

    // --- unreachable code ---

    fun testUnreachableCode() = doHighlight(
        """
        package p

        import "os"

        func afterReturn() int {
        	return 1
        	<warning descr="unreachable code">use(1)</warning>
        	use(2)
        }

        func afterPanic() {
        	panic("x")
        	<warning descr="unreachable code">use(1)</warning>
        }

        func afterExit() {
        	os.Exit(1)
        	use(1) // vet does not count os.Exit
        }

        func afterLoop() {
        	for {
        		use(1)
        	}
        	<warning descr="unreachable code">use(2)</warning>
        }

        func afterGoto() {
        	goto L
        	<warning descr="unreachable code">use(1)</warning>
        L:
        	use(2)
        }

        func inBranch(c bool) int {
        	if c {
        		return 1
        		<warning descr="unreachable code">use(1)</warning>
        	}
        	return 0
        }

        func allClausesEnd(x int) {
        	switch x {
        	case 1:
        		panic("a")
        	default:
        		panic("b")
        	}
        	<warning descr="unreachable code">use(2)</warning>
        }

        func loopBreak(c bool) {
        	for {
        		if c {
        			break
        		}
        	}
        	use(1)
        }

        func selectCases(ch chan int) {
        	select {
        	case <-ch:
        		use(1)
        	}
        	use(2)
        }

        func labelAfterReturn(c bool) {
        	if c {
        		goto end
        	}
        	return
        end:
        	use(1)
        }

        func unusedLabelAfterReturn() {
        	return
        end:
        	use(1)
        }

        func switchDefaultPanic(x int) {
        	switch x {
        	case 1:
        		use(1)
        	default:
        		panic("x")
        	}
        	use(2)
        }

        func ordinary(c bool) int {
        	if c {
        		return 1
        	}
        	use(1)
        	return 0
        }

        func loopContinue(xs []int) {
        	for range xs {
        		if len(xs) > 1 {
        			continue
        		}
        		use(1)
        	}
        	use(2)
        }
        """, GoUnreachableCodeInspection(),
    )

    fun testQuietAfterExitsAndHandledShadows() = doHighlight(
        """
        package p

        import (
        	"errors"
        	"log"
        	"os"
        )

        // cmd/go noCompiler: the compiler wants the return after log.Fatalf
        func fatal() error {
        	log.Fatalf("unknown compiler")
        	return nil
        }

        func exit() int {
        	os.Exit(2)
        	return 0
        }

        func g() error { return errors.New("x") }

        // debug/buildinfo: the inner err is handled by leaving the function
        func handled(c bool) error {
        	var err error
        	if c {
        		err := g()
        		if err != nil {
        			return errors.New("unrecognized")
        		}
        	}
        	return err
        }
        """,
        GoUnreachableCodeInspection(), GoShadowedErrorInspection(),
    )

    fun testResultUsedQuietOnSlicesAndMaps() = doHighlight(
        """
        package p

        func out() ([]byte, error)          { return nil, nil }
        func index() (map[string]int, error) { return nil, nil }

        // cmd.CombinedOutput: partial output is logged before the error is checked (seen in GOROOT)
        func partial() error {
        	b, err := out()
        	println(len(b), b[0])
        	m, err2 := index()
        	println(m["x"])
        	if err2 != nil {
        		return err2
        	}
        	return err
        }

        func ptr() (*T, error) { return nil, nil }

        // cmd/go modfetch: the result is nil-checked instead of the error
        func guarded() (*T, error) {
        	t, err := ptr()
        	if t != nil {
        		c := *t
        		t = &c
        	}
        	return t, err
        }
        """,
        GoResultUsedBeforeErrorCheckInspection(),
    )

    fun testUnreachableCodeFix() = doFix(
        """
        package p

        func a() int {
        	return 1
        	us<caret>e(1)
        	use(2)
        }
        """, "Delete unreachable code",
        """
        package p

        func a() int {
        	return 1
        }
        """, GoUnreachableCodeInspection(),
    )

    fun testUnreachableCodeFixStopsAtLabel() = doFix(
        """
        package p

        func a() {
        	goto L
        	us<caret>e(1)
        L:
        	use(2)
        }
        """, "Delete unreachable code",
        """
        package p

        func a() {
        	goto L
        L:
        	use(2)
        }
        """, GoUnreachableCodeInspection(),
    )
}
