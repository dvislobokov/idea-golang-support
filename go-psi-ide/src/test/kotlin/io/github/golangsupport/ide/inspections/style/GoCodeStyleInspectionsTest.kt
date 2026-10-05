package io.github.golangsupport.ide.inspections.style

import io.github.golangsupport.ide.formatter.GoCodeStyleSettings
import io.github.golangsupport.ide.inspections.GoParityInspectionTestBase

/** GoLand's "Code style issues" group (PLAN.md G7, first line): what is reported, what stays quiet, every quick fix. */
class GoCodeStyleInspectionsTest : GoParityInspectionTestBase() {

    // --- GoCommentLeadingSpace ---

    /** Runs [body] with Code Style | Go | Other "Add a leading space to comments" on (it is off by default, and so is the inspection). */
    private fun withLeadingSpaceOption(body: () -> Unit) {
        val settings = GoCodeStyleSettings.of(myFixture.configureByText("x.go", "package p\n"))
        settings.ADD_LEADING_SPACE_TO_COMMENTS = true
        try {
            body()
        } finally {
            settings.ADD_LEADING_SPACE_TO_COMMENTS = false
        }
    }

    fun testCommentLeadingSpace() = withLeadingSpaceOption {
        doHighlight(
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
    }

    /** GoLand (seen live): without the Code Style option the inspection is quiet even on `//no space` in a function body. */
    fun testCommentLeadingSpaceQuietWithoutTheCodeStyleOption() {
        assertFalse(GoCodeStyleSettings.of(myFixture.configureByText("x.go", "package p\n")).ADD_LEADING_SPACE_TO_COMMENTS)
        doHighlight(
            """
            package p

            //bad comment
            func f() {
            	x := 1 //no space trailing
            	//no space standalone
            	_ = x
            }
            """,
            GoCommentLeadingSpaceInspection(),
        )
    }

    fun testCommentLeadingSpaceFix() = withLeadingSpaceOption {
        doFix(
            "package p\n\n//<caret>bad\nfunc f() {}",
            "Add a space after '//'",
            "package p\n\n// bad\nfunc f() {}",
            GoCommentLeadingSpaceInspection(),
        )
    }

    // --- GoCommentStart ---

    fun testCommentStartWrongName() = doHighlight(
        """
        package p

        <weak_warning descr="Comment should have the following format 'Foo ...' (with an optional leading article)">// Does things.</weak_warning>
        func Foo() {}

        <weak_warning descr="Comment should have the following format 'Bar ...' (with an optional leading article)">// bar is a thing.</weak_warning>
        type Bar struct{}

        <weak_warning descr="Comment should have the following format 'Run ...' (with an optional leading article)">// Starts it.</weak_warning>
        func (b Bar) Run() {}

        <weak_warning descr="Comment should have the following format 'V ...' (with an optional leading article)">// Value of it.</weak_warning>
        var V = 1

        func NoComment() {}

        // A Thing is fine, so are articles and Deprecated.
        type Thing struct{}

        // Deprecated: use Foo.
        func Old() {}
        """,
        GoCommentStartInspection(),
    )

    /** GoLand (seen live) reports a doc comment without the leading space and a block doc comment too. */
    fun testCommentStartOnNoSpaceAndBlockDocs() = doHighlight(
        """
        package p

        <weak_warning descr="Comment should have the following format 'Snake ...' (with an optional leading article)">//no leading space here</weak_warning>
        var Snake = 1

        <weak_warning descr="Comment should have the following format 'BlockDoc ...' (with an optional leading article)">/*block comment*/</weak_warning>
        func BlockDoc() {}
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
        doHighlight("package main\n\n<weak_warning descr=\"Comment should have the following format 'Foo ...' (with an optional leading article)\">// Does.</weak_warning>\nfunc Foo() {}", GoCommentStartInspection(), fileName = "main.go")
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
        	e1 = errors.New(<weak_warning descr="Error string should not be capitalized or end with punctuation mark">"Something failed"</weak_warning>)
        	e2 = fmt.Errorf(<weak_warning descr="Error string should not be capitalized or end with punctuation mark">"failed: %w."</weak_warning>, e1)
        	e3 = errors.New("failed\n")
        	e9 = errors.New(<weak_warning descr="Error string should not be capitalized or end with punctuation mark">"Capitalized error."</weak_warning>)
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

        var A, <weak_warning descr="Exported variable 'B' should have its own declaration">B</weak_warning> int

        var a, <weak_warning descr="Exported variable 'C' should have its own declaration">C</weak_warning> = 1, 2

        var d, e int

        var First, second int

        const (
        	K, <weak_warning descr="Exported constant 'L' should have its own declaration">L</weak_warning> = 1, 2
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
        "package p\n\nvar A, B<caret> int",
        "Split into separate declarations",
        "package p\n\nvar A int\nvar B int",
        GoExportedOwnDeclarationInspection(),
    )

    fun testExportedOwnDeclarationSplitsGroupSpec() = doFix(
        "package p\n\nconst (\n\tK, L<caret> = 1, 2\n)",
        "Split into separate declarations",
        "package p\n\nconst (\n\tK = 1\n\tL = 2\n)",
        GoExportedOwnDeclarationInspection(),
    )

    // --- GoNameStartsWithPackageName ---

    fun testNameStartsWithPackageName() = doHighlight(
        """
        package probe

        type <weak_warning descr="Name starts with the package name">ProbeThing</weak_warning> struct{}

        func <weak_warning descr="Name starts with the package name">ProbeRun</weak_warning>() {}

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

    /** GoLand (seen live on probe2/style.go): when the names differ, every named receiver of the type is "different", generic ones also "generic". */
    fun testReceiverNames() = doHighlight(
        """
        package p

        type T struct{}

        func (<weak_warning descr="Receiver names are different"><weak_warning descr="Receiver has a generic name">this</weak_warning></weak_warning> T) A() {}

        func (<weak_warning descr="Receiver names are different">t</weak_warning> T) B() {}

        func (<weak_warning descr="Receiver names are different">x</weak_warning> T) C() {}

        func (<weak_warning descr="Receiver names are different">_</weak_warning> T) D() {}

        func (T) E() {}

        func (<weak_warning descr="Receiver names are different">t</weak_warning> *T) F() {}

        type U struct{}

        func (u U) A() {}

        func (u *U) B() {}

        func (U) C() {}

        type S struct{}

        func (<weak_warning descr="Receiver has a generic name">self</weak_warning> S) A() {}
        """,
        GoReceiverNamesInspection(),
    )

    /** The names are compared over the files of the package: `y` in another file makes every receiver of the type "different". */
    fun testReceiverNamesAcrossFiles() {
        myFixture.addFileToProject("other.go", "package p\n\nfunc (y Config) M6() {}\n")
        myFixture.addFileToProject("ext_test.go", "package p_test\n\ntype Config struct{}\n\nfunc (z Config) M7() {}\n")
        doHighlight(
            """
            package p

            type Config struct{}

            func (<weak_warning descr="Receiver names are different">c</weak_warning> Config) M1() {}

            func (<weak_warning descr="Receiver names are different">c</weak_warning> *Config) M2() {}
            """,
            GoReceiverNamesInspection(),
        )
    }

    fun testReceiverNamesSameNameEverywhereIsQuiet() {
        myFixture.addFileToProject("other.go", "package p\n\nfunc (c Config) M6() {}\n")
        doHighlight("package p\n\ntype Config struct{}\n\nfunc (c Config) M1() {}\n\nfunc (Config) M2() {}", GoReceiverNamesInspection())
    }

    fun testReceiverNamesRenameThis() = doFix(
        "package p\n\ntype Circle struct{ r int }\n\nfunc (th<caret>is Circle) R() int { return this.r }",
        "Rename to 'c'",
        "package p\n\ntype Circle struct{ r int }\n\nfunc (c Circle) R() int { return c.r }",
        GoReceiverNamesInspection(),
    )

    fun testReceiverNamesRenameToTheUsualName() = doFix(
        "package p\n\ntype T struct{}\n\nfunc (t T) A() {}\n\nfunc (t T) B() {}\n\nfunc (<caret>x T) C() { _ = x }",
        "Rename to 't'",
        "package p\n\ntype T struct{}\n\nfunc (t T) A() {}\n\nfunc (t T) B() {}\n\nfunc (t T) C() { _ = t }",
        GoReceiverNamesInspection(),
    )

    fun testReceiverNamesRemoveUnderscore() = doFix(
        "package p\n\ntype T struct{}\n\nfunc (t T) A() {}\n\nfunc (<caret>_ T) D() {}",
        "Remove the receiver name",
        "package p\n\ntype T struct{}\n\nfunc (t T) A() {}\n\nfunc (T) D() {}",
        GoReceiverNamesInspection(),
    )

    // --- GoRedundantElseInIf ---

    fun testRedundantElseInIf() = doHighlight(
        """
        package p

        func f(x int) int {
        	if x > 0 {
        		return 1
        	} <weak_warning descr="Redundant 'else' in 'if'">else</weak_warning> {
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
        		} <weak_warning descr="Redundant 'else' in 'if'">else</weak_warning> {
        			println(x)
        		}
        		if x == 0 {
        			panic("zero")
        		} <weak_warning descr="Redundant 'else' in 'if'">else</weak_warning> {
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

    /** Regression: `v, err := b()` outdented next to an outer `err` would assign it (or not compile); a later `w :=` would clash. */
    fun testRedundantElseNoFixWhenNamesClash() {
        for (body in listOf("v, err := b()\n\t\t_ = v\n\t\treturn err", "w := 1\n\t\t_ = w")) {
            val texts = offered(
                """
                package p

                func b() (int, error) { return 0, nil }

                func g(x int) error {
                	var err error
                	if x > 0 {
                		return nil
                	} el<caret>se {
                		BODY
                	}
                	w := 2
                	_ = w
                	return err
                }
                """.trimIndent().replace("BODY", body),
                GoRedundantElseInIfInspection(),
            )
            assertFalse(texts.toString(), "Remove redundant 'else'" in texts)
        }
    }

    fun testRedundantElseFixWithFreshNames() = doFix(
        """
        package p

        func b() (int, error) { return 0, nil }

        func g(x int) error {
        	if x > 0 {
        		return nil
        	} el<caret>se {
        		v, err := b()
        		_ = v
        		return err
        	}
        }
        """,
        "Remove redundant 'else'",
        """
        package p

        func b() (int, error) { return 0, nil }

        func g(x int) error {
        	if x > 0 {
        		return nil
        	}
        	v, err := b()
        	_ = v
        	return err
        }
        """,
        GoRedundantElseInIfInspection(),
    )

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
        	TimeoutSecs time.Duration
        	RetrySecs   int
        }

        const <weak_warning descr="Unit-specific suffix 'Ms'">delayMs</weak_warning> time.Duration = 5

        func f(waitMs time.Duration, timeout time.Duration) {
        	<weak_warning descr="Unit-specific suffix 'Seconds'">timeoutSeconds</weak_warning> := 2 * time.Second
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
        	<weak_warning descr="Imports are not sorted">"os"</weak_warning>
        	<weak_warning descr="Imports are not sorted">str "strings"</weak_warning>
        	<weak_warning descr="Imports are not sorted">"fmt"</weak_warning>

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

        func <weak_warning descr="Use camel case instead of snake case">parse_url</weak_warning>(<weak_warning descr="Use camel case instead of snake case">my_arg</weak_warning> int) {
        	<weak_warning descr="Use camel case instead of snake case">local_v</weak_warning> := my_arg
        	_ = local_v
        }

        type <weak_warning descr="Use camel case instead of snake case">Http_Server</weak_warning> struct {
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

    /** `range «text» severity` of the struct-literal findings of [text] (INFORMATION ones included, which highlighting markers skip). */
    private fun structFindings(text: String): List<String> {
        myFixture.enableInspections(GoStructInitializationWithoutFieldNamesInspection())
        myFixture.configureByText("a.go", text.trimIndent() + "\n")
        return myFixture.doHighlighting().filter { it.description == "Fields are assigned without explicit names" }.sortedBy { it.startOffset }
            .map { "«${it.text}» ${it.severity.name}" }
    }

    /** GoLand (seen live): its own package's types and anonymous structs only at INFORMATION level, over the whole `T{…}` when typed. */
    fun testStructInitializationWithoutFieldNamesOfThisPackageIsInformation() = assertEquals(
        listOf(
            "«P{1, 2}» INFORMATION", "«{\"a\", 1}» INFORMATION", "«{3, 4}» INFORMATION", "«{5, 6}» INFORMATION", "«P{7, 8}» INFORMATION",
        ),
        structFindings(
            """
            package p

            type P struct{ X, Y int }

            var (
            	a = P{1, 2}
            	b = P{X: 1}
            	c = P{}
            	d = []struct {
            		n string
            		v int
            	}{
            		{"a", 1},
            	}
            	e = []int{1, 2}
            	f = []*P{{3, 4}}
            	g = map[string]P{"k": {5, 6}}
            	h = &P{7, 8}
            )
            """,
        ),
    )

    fun testStructInitializationWithoutFieldNamesOfAnotherPackage() {
        myFixture.addFileToProject("b.go", "package p\n\ntype Local struct{ A int }\n")
        doHighlight(
            """
            package p

            import "io"

            var (
            	a = <weak_warning descr="Fields are assigned without explicit names">io.LimitedReader{nil, 10}</weak_warning>
            	b = []io.LimitedReader{<weak_warning descr="Fields are assigned without explicit names">{nil, 20}</weak_warning>}
            	c = Local{5}
            )
            """,
            GoStructInitializationWithoutFieldNamesInspection(),
        )
    }

    fun testStructInitializationAddFieldNames() = doFix(
        "package p\n\ntype P struct{ X, Y int }\n\nvar a = P{<caret>1, 2}",
        "Add field names",
        "package p\n\ntype P struct{ X, Y int }\n\nvar a = P{X: 1, Y: 2}",
        GoStructInitializationWithoutFieldNamesInspection(),
    )

    /** Regression: a blank field cannot be keyed (`_: 0` does not compile), so the literal is reported without the fix. */
    fun testStructInitializationNoFixWithBlankField() {
        val texts = offered("package p\n\ntype P struct {\n\tX int\n\t_ int\n}\n\nvar a = P{<caret>1, 2}", GoStructInitializationWithoutFieldNamesInspection())
        assertFalse(texts.toString(), "Add field names" in texts)
    }
}
