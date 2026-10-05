package io.github.golangsupport.ide.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.testFramework.replaceService
import io.github.golangsupport.ide.GoSemanticIdeTestBase

/**
 * The analysis inspections over the PSI (wave 2, part E): exhaustive switch, struct tags, `context.Context` placement and the
 * `errors` package, with their quick fixes (text before, fix, text after) and the negative cases.
 */
class GoAnalysisInspectionsTest : GoSemanticIdeTestBase() {

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

    // --- exhaustive switch ---

    private fun withColors(body: String) = colors + "\n\n" + body.trimIndent()

    private val colors = """
        package p

        type Color int

        const (
        	Red Color = iota
        	Green
        	Blue
        	Cyan
        	Magenta
        	Crimson = Red
        )
    """.trimIndent()

    fun testIotaSwitchReportsMissingConstants() = doHighlight(
        withColors("""

        func f(c Color) {
        	<warning descr="Missing 'case' statements for 'iota' consts in 'switch'">switch</warning> c {
        	case Red:
        	}
        	switch c {
        	case Crimson, Green, Blue, Cyan, Magenta:
        	}
        	switch c {
        	case Green:
        	default:
        	}
        	switch {
        	case c == Red:
        	}
        }

        func g(i int) {
        	switch i {
        	case 1:
        	}
        }
        """),
        GoSwitchMissingCasesForIotaConstsInspection(),
    )

    /** Probe `iota3.go` of the GoLand recon (2026-10-05): one finding on `switch` for `j, k, l` with only `j` handled. */
    fun testIotaSwitchGoLandProbe() = doHighlight(
        """
        package p

        const (
        	j Weekday = iota
        	k
        	l Weekday = iota
        )

        type Weekday int

        func sw(w Weekday) {
        	<warning descr="Missing 'case' statements for 'iota' consts in 'switch'">switch</warning> w {
        	case j:
        	}
        }
        """,
        GoSwitchMissingCasesForIotaConstsInspection(),
    )

    /** GoLand's description: a constant of the block counts even when its own spec does not use `iota`; constants outside such blocks never do. */
    fun testIotaSwitchMembersAreTheWholeIotaBlock() = doHighlight(
        """
        package p

        type W int

        const (
        	A W = iota
        	B
        	C W = 7
        )

        const Other W = 9

        type Plain int

        const (
        	P1 Plain = 1
        	P2 Plain = 2
        )

        func f(w W, p Plain) {
        	<warning descr="Missing 'case' statements for 'iota' consts in 'switch'">switch</warning> w {
        	case A, B:
        	}
        	switch w {
        	case A, B, C:
        	}
        	switch p {
        	case P1:
        	}
        }
        """,
        GoSwitchMissingCasesForIotaConstsInspection(),
    )

    // seen live: GoLand reports a switch over bit flags (`1 << iota`) too
    fun testIotaSwitchReportsFlags() = doHighlight(
        """
        package p

        type Perm int

        const (
        	Read Perm = 1 << iota
        	Write
        	Exec
        )

        func f(p Perm) {
        	<warning descr="Missing 'case' statements for 'iota' consts in 'switch'">switch</warning> p {
        	case Read:
        	}
        }
        """,
        GoSwitchMissingCasesForIotaConstsInspection(),
    )

    fun testIotaSwitchOverAnotherPackage() = doHighlight(
        """
        package p

        import "reflect"

        func f(d reflect.ChanDir) {
        	<warning descr="Missing 'case' statements for 'iota' consts in 'switch'">switch</warning> d {
        	case reflect.RecvDir, reflect.SendDir:
        	}
        }
        """,
        GoSwitchMissingCasesForIotaConstsInspection(),
    )

    fun testTypeSwitchIsNotReported() = doHighlight(
        """
        package p

        type Shape interface{ Area() float64 }

        type Polygon interface {
        	Shape
        	Sides() int
        }

        type Circle struct{}

        func (c *Circle) Area() float64 { return 0 }

        type Square struct{}

        func (s Square) Area() float64 { return 0 }
        func (s Square) Sides() int     { return 4 }

        func f(s Shape, err error) {
        	switch s.(type) {
        	case *Circle:
        	}
        	switch s.(type) {
        	case *Circle, Polygon:
        	}
        	switch s.(type) {
        	case nil:
        	default:
        	}
        	switch err.(type) {
        	case nil:
        	}
        }
        """,
        GoSwitchMissingCasesForIotaConstsInspection(),
    )

    fun testCreateCaseClauseFix() = doFix(
        withColors("""

        func f(c Color) {
        	switch<caret> c {
        	case Green:
        	}
        }
        """),
        "Create missing iota clauses",
        withColors("""

        func f(c Color) {
        	switch c {
        	case Green:
        	case Red:
        	case Blue:
        	case Cyan:
        	case Magenta:
        	}
        }
        """),
        GoSwitchMissingCasesForIotaConstsInspection(),
    )

    fun testCreateDefaultClauseFix() = doFix(
        withColors("""

        func f(c Color) {
        	switch<caret> c {
        	case Green:
        	}
        }
        """),
        "Create 'default' clause",
        withColors("""

        func f(c Color) {
        	switch c {
        	case Green:
        	default:
        		panic("unhandled default case")
        	}
        }
        """),
        GoSwitchMissingCasesForIotaConstsInspection(),
    )

    // --- struct tags ---

    fun testStructTags() = doHighlight(
        """
        package p

        type Base struct{}

        type S struct {
        	A int <warning descr="struct field tag `json:a` not compatible with reflect.StructTag.Get: bad syntax for struct tag value">`json:a`</warning>
        	B int <warning descr="Duplicate key \"json\" in struct field tag">`json:"b" json:"c"`</warning>
        	C int `json:"c"`
        	D int <warning descr="struct field D repeats json tag \"c\" also at field C">`json:"c,omitempty"`</warning>
        	E int `json:"-"`
        	F int `json:"-"`
        	G int `json:",omitempty"`
        	H int `json:",omitempty"`
        	X int `xml:"x,attr"`
        	Y int `xml:"x"`
        	<warning descr="struct field i has json tag but is not exported">i</warning> int `json:"i"`
        	j int `json:"-"`
        	k int `yaml:"k"`
        	Base `json:"base"`
        	Exported int `json:"exported" yaml:"exported" db:"exported"`
        }
        """,
        GoStructTagInspection(),
    )

    fun testFixTagQuoting() = doFix(
        """
        package p

        type S struct {
        	A int `json:a<caret>,omitempty`
        }
        """,
        "Fix quoting",
        """
        package p

        type S struct {
        	A int `json:"a,omitempty"`
        }
        """,
        GoStructTagInspection(),
    )

    fun testRemoveDuplicateTagKey() = doFix(
        """
        package p

        type S struct {
        	A int `json:"a" <caret>xml:"a" json:"b"`
        }
        """,
        "Remove duplicate key",
        """
        package p

        type S struct {
        	A int `json:"a" xml:"a"`
        }
        """,
        GoStructTagInspection(),
    )

    // --- context.Context ---

    fun testContextPlacement() = doHighlight(
        """
        package p

        import (
        	"context"
        	"testing"
        )

        func a(id int, ctx <warning descr="context.Context should be the first parameter of a function">context.Context</warning>) {}

        func b(ctx context.Context, id int) {}

        func c(t *testing.T, ctx context.Context) {}

        type R struct{}

        func (ctx R) m(c context.Context, id int) {}

        func d(ctx context.Context) {
        	use(<weak_warning descr="context.Background() is passed where 'ctx' is available">context.Background()</weak_warning>)
        	if true {
        		ctx := <warning descr="'ctx' is shadowed by context.TODO(): the caller's deadline and cancellation are lost">context.TODO()</warning>
        		use(ctx)
        	}
        	ctx = <warning descr="'ctx' is replaced by context.Background(): the caller's deadline and cancellation are lost">context.Background()</warning>
        	go func() { use(context.Background()) }()
        	use(ctx)
        }

        func e() { use(context.Background()) }

        func use(ctx context.Context) {}
        """,
        GoContextPlacementInspection(),
    )

    fun testUseContextFix() = doFix(
        """
        package p

        import (
        	"context"
        	"time"
        )

        func f(ctx context.Context) {
        	c, cancel := context.WithTimeout(context.Back<caret>ground(), time.Second)
        	defer cancel()
        	_ = c
        }
        """,
        "Use ctx",
        """
        package p

        import (
        	"context"
        	"time"
        )

        func f(ctx context.Context) {
        	c, cancel := context.WithTimeout(ctx, time.Second)
        	defer cancel()
        	_ = c
        }
        """,
        GoContextPlacementInspection(),
    )

    fun testRemoveContextReplacementFix() = doFix(
        """
        package p

        import "context"

        func f(ctx context.Context) {
        	ctx = context.TO<caret>DO()
        	g(ctx)
        }

        func g(ctx context.Context) {}
        """,
        "Use ctx (remove the assignment)",
        """
        package p

        import "context"

        func f(ctx context.Context) {
        	g(ctx)
        }

        func g(ctx context.Context) {}
        """,
        GoContextPlacementInspection(),
    )

    // --- errors ---

    fun testErrorsPackage() = doHighlight(
        """
        package p

        import (
        	"errors"
        	"io"
        	"os"
        )

        var ErrNotFound = errors.New("not found")

        type MyErr struct{}

        func (e *MyErr) Error() string { return "" }

        func (e *MyErr) Is(target error) bool { return target == ErrNotFound }

        func f(err error) {
        	var pe *os.PathError
        	_ = errors.As(err, <warning descr="${GoErrorsPackageInspection.AS_MESSAGE}">pe</warning>)
        	_ = errors.As(err, &pe)
        	var e error
        	_ = errors.As(err, <warning descr="second argument to errors.As should not be *error">&e</warning>)
        	var a any
        	_ = errors.As(err, a)
        	var m MyErr
        	_ = errors.As(err, <warning descr="${GoErrorsPackageInspection.AS_MESSAGE}">m</warning>)
        	_ = errors.As(err, <warning descr="${GoErrorsPackageInspection.AS_MESSAGE}">nil</warning>)
        	if <weak_warning descr="Comparison with sentinel error 'ErrNotFound' is false for wrapped errors; use errors.Is">err == ErrNotFound</weak_warning> {
        	}
        	if <weak_warning descr="Comparison with sentinel error 'io.EOF' is false for wrapped errors; use errors.Is">io.EOF != err</weak_warning> {
        	}
        	if err == nil {
        	}
        	local := errors.New("x")
        	if err == local {
        	}
        }
        """,
        GoErrorsPackageInspection(),
    )

    fun testTakeAddressFix() = doFix(
        """
        package p

        import (
        	"errors"
        	"os"
        )

        func f(err error) bool {
        	var pe *os.PathError
        	return errors.As(err, p<caret>e)
        }
        """,
        "Take the address of target",
        """
        package p

        import (
        	"errors"
        	"os"
        )

        func f(err error) bool {
        	var pe *os.PathError
        	return errors.As(err, &pe)
        }
        """,
        GoErrorsPackageInspection(),
    )

    fun testUseErrorsIsFixAddsImport() = doFix(
        """
        package p

        import "io"

        func f(err error) bool {
        	return err !=<caret> io.EOF
        }
        """,
        "Replace with !errors.Is(err, io.EOF)",
        """
        package p

        import (
        	"errors"
        	"io"
        )

        func f(err error) bool {
        	return !errors.Is(err, io.EOF)
        }
        """,
        GoErrorsPackageInspection(),
    )

    /** Nothing is reported while the host gives diagnostics to another source (the gate). */
    fun testSilentWhenDiagnosticsAreOff() {
        val gate = object : io.github.golangsupport.ide.GoIdeFeatureGate {
            override fun enabled(feature: io.github.golangsupport.ide.GoIdeFeature, project: com.intellij.openapi.project.Project) =
                feature != io.github.golangsupport.ide.GoIdeFeature.DIAGNOSTICS
        }
        com.intellij.openapi.application.ApplicationManager.getApplication().replaceService(io.github.golangsupport.ide.GoIdeFeatureGate::class.java, gate, testRootDisposable)
        doHighlight(
            """
            package p

            type S struct {
            	A int `json:a`
            }
            """,
            GoStructTagInspection(),
        )
    }

    // --- time layouts ---

    fun testTimeLayoutProblems() = doHighlight(
        """
        package p

        import "time"

        func f(t time.Time, s string) {
        	_ = t.Format(<weak_warning descr="Layout uses 'yyyy-MM-dd HH:mm:ss' notation; Go layouts use the reference time 2006-01-02 15:04:05">"yyyy-MM-dd HH:mm:ss"</weak_warning>)
        	_, _ = time.Parse(<weak_warning descr="Layout uses 'YYYY-MM-DD' notation; Go layouts use the reference time 2006-01-02 15:04:05">"YYYY-MM-DD"</weak_warning>, s)
        	_ = t.Format(<weak_warning descr="Layout contains no time elements">"date"</weak_warning>)
        	_ = t.Format(<weak_warning descr="Day and month swapped? '2006-02-01' formats day 02 as month">"2006-02-01"</weak_warning>)
        	_ = t.Format(<weak_warning descr="Day and month swapped? '2006-02-01' formats day 02 as month">"2006-02-01 15:04"</weak_warning>)
        }
        """,
        GoTimeLayoutInspection(),
    )

    fun testTimeLayoutValidLayoutsAreQuiet() = doHighlight(
        """
        package p

        import "time"

        type T struct{}

        func (T) Format(layout string) string { return layout }

        const custom = "date"

        func f(t time.Time, s string, o T) {
        	_ = t.Format(time.RFC3339)
        	_ = t.Format(time.Kitchen)
        	_ = t.Format("2006-01-02")
        	_ = t.Format("02/01/2006 15:04:05.000")
        	_ = t.Format("")
        	_ = t.Format(s)
        	_ = t.Format(custom)
        	_ = o.Format("yyyy-MM-dd")
        	_, _ = time.Parse(time.DateTime, s)
        }
        """,
        GoTimeLayoutInspection(),
    )

    fun testConvertToGoLayout() = doFix(
        """
        package p

        import "time"

        func f(t time.Time) {
        	_ = t.Format(<caret>"dd/MM/yyyy HH:mm")
        }
        """,
        "Convert to Go layout",
        """
        package p

        import "time"

        func f(t time.Time) {
        	_ = t.Format("02/01/2006 15:04")
        }
        """,
        GoTimeLayoutInspection(),
    )

    fun testSwapDayAndMonth() = doFix(
        """
        package p

        import "time"

        func f(t time.Time) {
        	_ = t.Format(`<caret>2006-02-01 15:04`)
        }
        """,
        "Swap to '2006-01-02'",
        """
        package p

        import "time"

        func f(t time.Time) {
        	_ = t.Format(`2006-01-02 15:04`)
        }
        """,
        GoTimeLayoutInspection(),
    )
}
