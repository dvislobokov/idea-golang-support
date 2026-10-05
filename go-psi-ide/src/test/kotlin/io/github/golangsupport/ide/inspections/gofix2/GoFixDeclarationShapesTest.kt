package io.github.golangsupport.ide.inspections.gofix2

import com.intellij.codeInspection.LocalInspectionTool

/** new(expr), omitzero, +build, typed embed. */
class GoFixNewExprTest : GoFixInspectionTestBase() {
    override fun tool(): LocalInspectionTool = GoFixNewExprInspection()

    fun testGenericHelperCall() = fix(
        """
        package a

        func ptr[T any](x T) *T { return &x }

        var p = <SYNTAX_UPDATE descr="call of ptr(x) can be simplified to new(x)">ptr(30 + 12)</SYNTAX_UPDATE>
        """,
        "Simplify call of ptr to new",
        """
        package a

        func ptr[T any](x T) *T { return &x }

        var p = new(30 + 12)
        """,
    )

    fun testTypedHelperKeepsTheConstantType() = fix(
        """
        package a

        func int64Ptr(v int64) *int64 { return &v }

        var p = <SYNTAX_UPDATE descr="call of int64Ptr(x) can be simplified to new(x)">int64Ptr(5)</SYNTAX_UPDATE>
        """,
        "Simplify call of int64Ptr to new",
        """
        package a

        func int64Ptr(v int64) *int64 { return &v }

        var p = new(int64(5))
        """,
    )

    fun testImmediateLiteral() = fix(
        """
        package a

        func name() string { return "x" }

        var p = <SYNTAX_UPDATE descr="function literal can be simplified to new(name())">func() *string { v := name(); return &v }()</SYNTAX_UPDATE>
        """,
        "Simplify to new(expr)",
        """
        package a

        func name() string { return "x" }

        var p = new(name())
        """,
    )

    fun testTemporaryUsedOnlyForItsAddress() = fix(
        """
        package a

        type Level int

        func f(n int) *Level {
        	x := Level(n)
        	return <SYNTAX_UPDATE descr="variable 'x' is used only for its address; it can be created with new(Level(n))">&x</SYNTAX_UPDATE>
        }
        """,
        "Simplify to new(expr)",
        """
        package a

        type Level int

        func f(n int) *Level {
        	return new(Level(n))
        }
        """,
    )

    fun testOtherShapesStayQuiet() = highlight(
        """
        package a

        func notHelper(x int) *int { y := x; y++; return &y }

        func f(n int) (*int, int) {
        	x := n
        	p := &x
        	x++
        	q := notHelper(n)
        	_ = q
        	return p, x
        }
        """
    )

    fun testBelowGo126() {
        goVersion("1.25")
        highlight("package a\n\nfunc ptr[T any](x T) *T { return &x }\n\nvar p = ptr(1)")
    }
}

class GoFixOmitZeroTest : GoFixInspectionTestBase() {
    override fun tool(): LocalInspectionTool = GoFixOmitZeroInspection()

    private val w = "Omitempty has no effect on nested struct fields"

    fun testTimeFieldToOmitZero() = fix(
        """
        package a

        import "time"

        type Event struct {
        	Name    string    `json:"name,omitempty"`
        	Created time.Time `json:"created,<SYNTAX_UPDATE descr="$w">omitempty</SYNTAX_UPDATE>" db:"created"`
        }
        """,
        "Replace omitempty with omitzero (behavior change)",
        """
        package a

        import "time"

        type Event struct {
        	Name    string    `json:"name,omitempty"`
        	Created time.Time `json:"created,omitzero" db:"created"`
        }
        """,
    )

    fun testRemoveTheOnlyOption() = fix(
        """
        package a

        type Inner struct{ A int }

        type Outer struct {
        	In Inner `json:",<SYNTAX_UPDATE descr="$w">omitempty</SYNTAX_UPDATE>"`
        }
        """,
        "Remove redundant omitempty tags",
        """
        package a

        type Inner struct{ A int }

        type Outer struct {
        	In Inner
        }
        """,
    )

    fun testPointersMapsAndOtherKeysStayQuiet() = highlight(
        """
        package a

        type Inner struct{ A int }

        type Outer struct {
        	P *Inner          `json:"p,omitempty"`
        	M map[string]int  `json:"m,omitempty"`
        	X Inner           `xml:"x,omitempty"`
        	Z Inner           `json:"z,omitempty,omitzero"`
        }
        """
    )

    fun testBelowGo124() {
        goVersion("1.23")
        highlight("package a\n\ntype Inner struct{ A int }\n\ntype Outer struct {\n\tIn Inner `json:\"in,omitempty\"`\n}")
    }
}

class GoFixPlusBuildTest : GoFixInspectionTestBase() {
    override fun tool(): LocalInspectionTool = GoFixPlusBuildInspection()

    fun testObsoleteNextToGoBuild() = fix(
        """
        //go:build linux && amd64
        <SYNTAX_UPDATE descr="+build line is no longer needed">// +build linux,amd64</SYNTAX_UPDATE>

        package a
        """,
        "Remove obsolete +build lines",
        """
        //go:build linux && amd64

        package a
        """,
    )

    fun testReplacedWithoutGoBuild() = fix(
        """
        <SYNTAX_UPDATE descr="+build line is obsolete; use //go:build">// +build linux darwin</SYNTAX_UPDATE>
        <SYNTAX_UPDATE descr="+build line is obsolete; use //go:build">// +build !386</SYNTAX_UPDATE>

        package a
        """,
        "Replace +build lines with //go:build",
        """
        //go:build (linux || darwin) && !386

        package a
        """,
    )

    fun testNotInTheHeader() = highlight("package a\n\n// +build linux\nvar x int")

    fun testBelowGo117() {
        goVersion("1.16")
        highlight("//go:build linux\n// +build linux\n\npackage a")
    }
}

class GoFixEmbedTypedTest : GoFixInspectionTestBase() {
    override fun tool(): LocalInspectionTool = GoFixEmbedTypedInspection()

    fun testStringUsedOnlyAsBytes() {
        myFixture.addFileToProject("schema.json", "{}")
        fix(
            """
            package a

            import (
            	_ "embed"
            	"encoding/json"
            )

            //go:embed schema.json
            var <SYNTAX_UPDATE descr="Embedded variable 'schema' is used only as []byte; it can be declared as []byte">schema</SYNTAX_UPDATE> string

            func load(v any) error { return json.Unmarshal([]byte(schema), v) }
            """,
            "Declare 'schema' as []byte",
            """
            package a

            import (
            	_ "embed"
            	"encoding/json"
            )

            //go:embed schema.json
            var schema []byte

            func load(v any) error { return json.Unmarshal(schema, v) }
            """,
        )
    }

    fun testOtherUsesStayQuiet() = highlight(
        """
        package a

        import _ "embed"

        //go:embed a.go
        var text string

        //go:embed a.go
        var raw string

        var plain string

        func use([]byte) {}

        func f() string {
        	b := []byte(raw)
        	use(b)
        	use([]byte(plain))
        	return text
        }
        """
    )
}
