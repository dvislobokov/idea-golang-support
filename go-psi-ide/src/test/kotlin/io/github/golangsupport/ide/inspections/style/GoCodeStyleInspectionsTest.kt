package io.github.golangsupport.ide.inspections.style

import io.github.golangsupport.ide.inspections.GoParityInspectionTestBase

/** GoLand's "Code style issues" group (PLAN.md G7, first line): what is reported, what stays quiet, every quick fix. */
class GoCodeStyleInspectionsTest : GoParityInspectionTestBase() {

    // --- GoCommentLeadingSpace ---

    fun testCommentLeadingSpace() = doHighlight(
        """
        package p

        <weak_warning descr="Line comment should have a space after '//'">//bad comment</weak_warning>
        // good comment
        //go:generate stringer -type=X
        //nolint:errcheck
        //lint:ignore U1000 reason
        //export Foo
        ////
        //
        func f() {} <weak_warning descr="Line comment should have a space after '//'">//trailing</weak_warning>
        """,
        GoCommentLeadingSpaceInspection(),
    )

    fun testCommentLeadingSpaceFix() = doFix(
        "package p\n\n//<caret>bad\nfunc f() {}",
        "Add a space after '//'",
        "package p\n\n// bad\nfunc f() {}",
        GoCommentLeadingSpaceInspection(),
    )

    // --- GoCommentStart ---

    fun testCommentStartWrongName() = doHighlight(
        """
        package p

        <weak_warning descr="comment on exported function Foo should be of the form \"Foo ...\"">// Does things.</weak_warning>
        func Foo() {}

        <weak_warning descr="comment on exported type Bar should be of the form \"Bar ...\"">// bar is a thing.</weak_warning>
        type Bar struct{}

        <weak_warning descr="comment on exported method Bar.Run should be of the form \"Run ...\"">// Starts it.</weak_warning>
        func (b Bar) Run() {}

        <weak_warning descr="comment on exported var V should be of the form \"V ...\"">// Value of it.</weak_warning>
        var V = 1

        func NoComment() {}

        // A Thing is fine, so are articles and Deprecated.
        type Thing struct{}

        // Deprecated: use Foo.
        func Old() {}
        """,
        GoCommentStartInspection(),
    )

    fun testCommentStartMeaningless() = doHighlight(
        """
        package store

        <weak_warning descr="Comment should be meaningful or it should be removed">// NewOrder</weak_warning>
        func NewOrder() {}

        // Order is an order.
        type Order struct{}
        """,
        GoCommentStartInspection(),
    )

    fun testCommentStartChecksMainButNotTests() {
        doHighlight("package main\n\n<weak_warning descr=\"comment on exported function Foo should be of the form \\\"Foo ...\\\"\">// Does.</weak_warning>\nfunc Foo() {}", GoCommentStartInspection(), fileName = "main.go")
        doHighlight("package p\n\n// Does.\nfunc Foo() {}", GoCommentStartInspection(), fileName = "a_test.go")
    }

    fun testCommentStartRewritesFirstWord() = doFix(
        "package p\n\n// <caret>foo does things.\nfunc Foo() {}",
        "Start comment with 'Foo'",
        "package p\n\n// Foo does things.\nfunc Foo() {}",
        GoCommentStartInspection(),
    )

    fun testCommentStartPrependsName() = doFix(
        "package p\n\n// <caret>Returns the answer.\nfunc Answer() int { return 42 }",
        "Start comment with 'Answer'",
        "package p\n\n// Answer returns the answer.\nfunc Answer() int { return 42 }",
        GoCommentStartInspection(),
    )

    fun testCommentStartRemovesMeaninglessComment() = doFix(
        "package p\n\n// New<caret>Order\nfunc NewOrder() {}",
        "Remove comment",
        "package p\n\nfunc NewOrder() {}",
        GoCommentStartInspection(),
    )

    // --- GoErrorStringFormat ---

    fun testErrorStringFormat() = doHighlight(
        """
        package p

        import (
        	"errors"
        	"fmt"
        )

        func Errorf(s string) error { return nil }

        var (
        	e1 = errors.New("<weak_warning descr="Error string should not be capitalized">S</weak_warning>omething failed")
        	e2 = fmt.Errorf("failed: %w<weak_warning descr="Error string should not end with punctuation or a newline">.</weak_warning>", e1)
        	e3 = errors.New("failed<weak_warning descr="Error string should not end with punctuation or a newline">\n</weak_warning>")
        	e4 = errors.New("URL is bad")
        	e5 = errors.New("IPv4 missing")
        	e6 = errors.New("not found")
        	e7 = Errorf("Local function.")
        	e8 = fmt.Sprintf("Not an error.")
        )
        """,
        GoErrorStringFormatInspection(),
    )

    fun testErrorStringLowercaseFix() = doFix(
        """
        package p

        import "errors"

        var e = errors.New("<caret>Something failed")
        """,
        "Lowercase the first letter",
        """
        package p

        import "errors"

        var e = errors.New("something failed")
        """,
        GoErrorStringFormatInspection(),
    )

    fun testErrorStringPunctuationFix() = doFix(
        """
        package p

        import "fmt"

        var e = fmt.Errorf("failed: %d.<caret>", 1)
        """,
        "Remove the trailing punctuation",
        """
        package p

        import "fmt"

        var e = fmt.Errorf("failed: %d", 1)
        """,
        GoErrorStringFormatInspection(),
    )

    // --- GoExportedOwnDeclaration ---

    fun testExportedOwnDeclaration() = doHighlight(
        """
        package p

        var <weak_warning descr="Exported var A should have its own declaration">A</weak_warning>, B int

        var a, <weak_warning descr="Exported var C should have its own declaration">C</weak_warning> = 1, 2

        var d, e int

        const (
        	<weak_warning descr="Exported const K should have its own declaration">K</weak_warning>, L = 1, 2
        )

        var One int

        func f() {
        	var X, Y int
        	_, _ = X, Y
        }
        """,
        GoExportedOwnDeclarationInspection(),
    )

    fun testExportedOwnDeclarationSplitsSingleSpec() = doFix(
        "package p\n\nvar A<caret>, B int",
        "Split into separate declarations",
        "package p\n\nvar A int\nvar B int",
        GoExportedOwnDeclarationInspection(),
    )

    fun testExportedOwnDeclarationSplitsGroupSpec() = doFix(
        "package p\n\nconst (\n\tK<caret>, L = 1, 2\n)",
        "Split into separate declarations",
        "package p\n\nconst (\n\tK = 1\n\tL = 2\n)",
        GoExportedOwnDeclarationInspection(),
    )

    // --- GoNameStartsWithPackageName ---

    fun testNameStartsWithPackageName() = doHighlight(
        """
        package probe

        type <weak_warning descr="type name will be used as probe.ProbeThing by other packages, and that stutters; consider calling this Thing">ProbeThing</weak_warning> struct{}

        func <weak_warning descr="func name will be used as probe.ProbeRun by other packages, and that stutters; consider calling this Run">ProbeRun</weak_warning>() {}

        type Prober interface{}

        type Probe struct{}

        func probeLocal() {}

        func (p Probe) ProbeIt() {}
        """,
        GoNameStartsWithPackageNameInspection(),
    )

    fun testNameStartsWithPackageNameQuietInMain() = doHighlight("package main\n\nfunc MainThing() {}\n\nfunc main() {}", GoNameStartsWithPackageNameInspection(), fileName = "main.go")

    fun testNameStartsWithPackageNameRename() = doFix(
        "package probe\n\ntype Probe<caret>Thing struct{}\n\nvar _ ProbeThing",
        "Rename to 'Thing'",
        "package probe\n\ntype Thing struct{}\n\nvar _ Thing",
        GoNameStartsWithPackageNameInspection(),
    )

    // --- GoReceiverNames ---

    fun testReceiverNames() = doHighlight(
        """
        package p

        type T struct{}

        func (<weak_warning descr="Receiver name should be a reflection of its identity; don't use generic names such as 'this' or 'self'">this</weak_warning> T) A() {}

        func (t T) B() {}

        func (<weak_warning descr="Receiver name x should be consistent with previous receiver name t for T">x</weak_warning> T) C() {}

        func (<weak_warning descr="Receiver name should not be an underscore, omit the name if it is unused">_</weak_warning> T) D() {}

        func (T) E() {}

        func (t *T) F() {}

        type U struct{}

        func (u U) A() {}
        """,
        GoReceiverNamesInspection(),
    )

    fun testReceiverNamesRenameThis() = doFix(
        "package p\n\ntype Circle struct{ r int }\n\nfunc (th<caret>is Circle) R() int { return this.r }",
        "Rename to 'c'",
        "package p\n\ntype Circle struct{ r int }\n\nfunc (c Circle) R() int { return c.r }",
        GoReceiverNamesInspection(),
    )

    fun testReceiverNamesRemoveUnderscore() = doFix(
        "package p\n\ntype T struct{}\n\nfunc (<caret>_ T) D() {}",
        "Remove the receiver name",
        "package p\n\ntype T struct{}\n\nfunc (T) D() {}",
        GoReceiverNamesInspection(),
    )

    // --- GoRedundantElseInIf ---

    fun testRedundantElseInIf() = doHighlight(
        """
        package p

        func f(x int) int {
        	if x > 0 {
        		return 1
        	} <weak_warning descr="'if' block ends with a 'return' statement, so drop this 'else' and outdent its block">else</weak_warning> {
        		x++
        	}
        	if x > 1 {
        		x--
        	} else {
        		x++
        	}
        	if x > 2 {
        		return 2
        	} else if x > 3 {
        		return 3
        	} else {
        		return 4
        	}
        }

        func g(xs []int) {
        	for _, x := range xs {
        		if x < 0 {
        			continue
        		} <weak_warning descr="'if' block ends with a 'continue' statement, so drop this 'else' and outdent its block">else</weak_warning> {
        			println(x)
        		}
        		if x == 0 {
        			panic("zero")
        		} <weak_warning descr="'if' block ends with a 'panic' statement, so drop this 'else' and outdent its block">else</weak_warning> {
        			println(x)
        		}
        	}
        }
        """,
        GoRedundantElseInIfInspection(),
    )

    fun testRedundantElseFix() = doFix(
        """
        package p

        func f(x int) int {
        	if x > 0 {
        		return 1
        	} el<caret>se {
        		x++
        		return x
        	}
        }
        """,
        "Remove redundant 'else'",
        """
        package p

        func f(x int) int {
        	if x > 0 {
        		return 1
        	}
        	x++
        	return x
        }
        """,
        GoRedundantElseInIfInspection(),
    )

    fun testRedundantElseNoFixWithInit() {
        val texts = offered(
            """
            package p

            func f() (int, bool) { return 0, true }

            func g() int {
            	if v, ok := f(); !ok {
            		return 0
            	} el<caret>se {
            		return v
            	}
            }
            """,
            GoRedundantElseInIfInspection(),
        )
        assertFalse(texts.toString(), "Remove redundant 'else'" in texts)
    }

    // --- GoTypeParameterInLowerCase (information level) ---

    fun testTypeParameterInLowerCase() {
        assertTrue("Rename to 'T'" in offered("package p\n\nfunc F[<caret>t any, K comparable](x t, k K) t { return x }", GoTypeParameterInLowerCaseInspection()))
        assertTrue(offered("package p\n\nfunc F[t any, <caret>K comparable](x t, k K) t { return x }", GoTypeParameterInLowerCaseInspection()).none { it.startsWith("Rename to") })
    }

    fun testTypeParameterInLowerCaseRename() = doFix(
        "package p\n\nfunc F[t<caret> any](x t) t { return x }",
        "Rename to 'T'",
        "package p\n\nfunc F[T any](x T) T { return x }",
        GoTypeParameterInLowerCaseInspection(),
    )

    // --- GoUnitSpecificDurationSuffix ---

    fun testUnitSpecificDurationSuffix() = doHighlight(
        """
        package p

        import "time"

        type C struct {
        	<weak_warning descr="field TimeoutSecs is of type time.Duration; don't use unit-specific suffix \"Secs\"">TimeoutSecs</weak_warning> time.Duration
        	RetrySecs   int
        }

        func f(<weak_warning descr="parameter delayMs is of type time.Duration; don't use unit-specific suffix \"Ms\"">delayMs</weak_warning> time.Duration, timeout time.Duration) {
        	<weak_warning descr="var timeoutSeconds is of type time.Duration; don't use unit-specific suffix \"Seconds\"">timeoutSeconds</weak_warning> := 2 * time.Second
        	countSecs := 3
        	_, _, _, _ = timeoutSeconds, countSecs, delayMs, timeout
        }
        """,
        GoUnitSpecificDurationSuffixInspection(),
    )

    fun testUnitSpecificDurationSuffixRename() = doFix(
        """
        package p

        import "time"

        func f() time.Duration {
        	timeout<caret>Seconds := 2 * time.Second
        	return timeoutSeconds
        }
        """,
        "Rename to 'timeout'",
        """
        package p

        import "time"

        func f() time.Duration {
        	timeout := 2 * time.Second
        	return timeout
        }
        """,
        GoUnitSpecificDurationSuffixInspection(),
    )

    // --- GoUnsortedImport ---

    fun testUnsortedImport() = doHighlight(
        """
        package p

        import (
        	"os"
        	<weak_warning descr="Import is not sorted">"fmt"</weak_warning>

        	"errors"
        	"strings"
        )

        var _, _, _, _ = os.Args, fmt.Sprint, errors.New, strings.ToUpper
        """,
        GoUnsortedImportInspection(),
    )

    fun testUnsortedImportFix() = doFix(
        """
        package p

        import (
        	"strings"
        	<caret>"os"
        	"fmt" // printing

        	"errors"
        )

        var _, _, _, _ = os.Args, fmt.Sprint, errors.New, strings.ToUpper
        """,
        "Sort imports",
        """
        package p

        import (
        	"fmt" // printing
        	"os"
        	"strings"

        	"errors"
        )

        var _, _, _, _ = os.Args, fmt.Sprint, errors.New, strings.ToUpper
        """,
        GoUnsortedImportInspection(),
    )

    // --- GoSnakeCaseUsage ---

    fun testSnakeCaseUsage() = doHighlight(
        """
        package p

        const MAX_SIZE = 1

        var _x, y_ = 1, 2

        func <weak_warning descr="Don't use underscores in Go names; func parse_url should be parseUrl">parse_url</weak_warning>(<weak_warning descr="Don't use underscores in Go names; parameter my_arg should be myArg">my_arg</weak_warning> int) {
        	<weak_warning descr="Don't use underscores in Go names; var local_v should be localV">local_v</weak_warning> := my_arg
        	_ = local_v
        }

        type <weak_warning descr="Don't use underscores in Go names; type Http_Server should be HttpServer">Http_Server</weak_warning> struct {
        	field_name int
        }
        """,
        GoSnakeCaseUsageInspection(),
    )

    fun testSnakeCaseQuietForTestFunctions() = doHighlight(
        "package p\n\nfunc Test_parse(t int) {}\n\nfunc Example_hello() {}",
        GoSnakeCaseUsageInspection(),
        fileName = "a_test.go",
    )

    fun testSnakeCaseRename() = doFix(
        "package p\n\nfunc f() int {\n\tlocal<caret>_v := 1\n\treturn local_v\n}",
        "Rename to 'localV'",
        "package p\n\nfunc f() int {\n\tlocalV := 1\n\treturn localV\n}",
        GoSnakeCaseUsageInspection(),
    )

    // --- GoStructInitializationWithoutFieldNames ---

    fun testStructInitializationWithoutFieldNames() = doHighlight(
        """
        package p

        type P struct{ X, Y int }

        var (
        	a = P<weak_warning descr="Fields are assigned without explicit names">{1, 2}</weak_warning>
        	b = P{X: 1}
        	c = P{}
        	d = []struct {
        		n string
        		v int
        	}{
        		<weak_warning descr="Fields are assigned without explicit names">{"a", 1}</weak_warning>,
        	}
        	e = []int{1, 2}
        	f = []*P{<weak_warning descr="Fields are assigned without explicit names">{3, 4}</weak_warning>}
        	g = map[string]P{"k": <weak_warning descr="Fields are assigned without explicit names">{5, 6}</weak_warning>}
        	h = &P<weak_warning descr="Fields are assigned without explicit names">{7, 8}</weak_warning>
        )
        """,
        GoStructInitializationWithoutFieldNamesInspection(),
    )

    fun testStructInitializationAddFieldNames() = doFix(
        "package p\n\ntype P struct{ X, Y int }\n\nvar a = P{<caret>1, 2}",
        "Add field names",
        "package p\n\ntype P struct{ X, Y int }\n\nvar a = P{X: 1, Y: 2}",
        GoStructInitializationWithoutFieldNamesInspection(),
    )
}
