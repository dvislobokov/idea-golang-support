package io.github.golangsupport.ide.inspections.redundancy

import io.github.golangsupport.ide.inspections.GoParityInspectionTestBase

/** GoLand's "Declaration redundancy" group and "Redundant parentheses" (PLAN.md G7, first line): reports, negatives, quick fixes. */
class GoRedundancyInspectionsTest : GoParityInspectionTestBase() {

    // --- GoEmptyDeclaration ---

    fun testEmptyDeclaration() = doHighlight(
        """
        package p

        <warning descr="Empty 'import' declaration">import ()</warning>

        <warning descr="Empty 'var' declaration">var ()</warning>

        <warning descr="Empty 'const' declaration">const ()</warning>

        <warning descr="Empty 'type' declaration">type ()</warning>

        var (
        	// kept: a comment inside
        )

        var x = 1
        """,
        GoEmptyDeclarationInspection(),
    )

    fun testEmptyDeclarationFix() = doFix(
        "package p\n\nvar (<caret>)\n\nvar x = 1",
        "Delete empty declaration",
        "package p\n\n\nvar x = 1",
        GoEmptyDeclarationInspection(),
    )

    // --- GoPreferNilSlice ---

    fun testPreferNilSlice() = doHighlight(
        """
        package p

        var pkg = []int{}

        func f() {
        	s := <weak_warning descr="Empty slice declared using a literal">[]int{}</weak_warning>
        	var t = <weak_warning descr="Empty slice declared using a literal">[]string{}</weak_warning>
        	u := []int{1}
        	a := [2]int{}
        	var v []int = []int{}
        	m := map[string]int{}
        	x, y := []int{}, 1
        	_, _, _, _, _, _, _, _ = s, t, u, a, v, m, x, y
        }
        """,
        GoPreferNilSliceInspection(),
    )

    fun testPreferNilSliceShortVarFix() = doFix(
        "package p\n\nfunc f() []int {\n\ts := []int{<caret>}\n\treturn s\n}",
        "Replace with nil slice declaration",
        "package p\n\nfunc f() []int {\n\tvar s []int\n\treturn s\n}",
        GoPreferNilSliceInspection(),
    )

    fun testPreferNilSliceVarFix() = doFix(
        "package p\n\nfunc f() []string {\n\tvar t = []string{<caret>}\n\treturn t\n}",
        "Replace with nil slice declaration",
        "package p\n\nfunc f() []string {\n\tvar t []string\n\treturn t\n}",
        GoPreferNilSliceInspection(),
    )

    // --- GoRedundantComma ---

    fun testRedundantComma() = doHighlight(
        """
        package p

        func f(a, b int) {}

        func g() {
        	f(1, 2<weak_warning descr="Redundant comma">,</weak_warning>)
        	f(
        		1,
        		2,
        	)
        	_ = []int{1, 2<weak_warning descr="Redundant comma">,</weak_warning> }
        	_ = []int{
        		1, 2,
        	}
        }
        """,
        GoRedundantCommaInspection(),
    )

    fun testRedundantCommaFix() = doFix(
        "package p\n\nvar a = []int{1, 2<caret>,}",
        "Remove redundant comma",
        "package p\n\nvar a = []int{1, 2}",
        GoRedundantCommaInspection(),
    )

    // --- GoRedundantSemicolon ---

    fun testRedundantSemicolon() = doHighlight(
        """
        package p

        func f() {
        	x := 1<weak_warning descr="Redundant semicolon">;</weak_warning>
        	for i := 0; i < 3; i++ {
        	}
        	for ; ; {
        		break
        	}
        	if x > 0 { x++; x-- }
        	if y := x; y > 0 {
        	}
        	_ = x<weak_warning descr="Redundant semicolon">;</weak_warning> // trailing comment
        }
        """,
        GoRedundantSemicolonInspection(),
    )

    fun testRedundantSemicolonFix() = doFix(
        "package p\n\nfunc f() {\n\tx := 1<caret>;\n\t_ = x\n}",
        "Remove redundant semicolon",
        "package p\n\nfunc f() {\n\tx := 1\n\t_ = x\n}",
        GoRedundantSemicolonInspection(),
    )

    // --- GoRedundantParens ---

    fun testRedundantParens() = doHighlight(
        """
        package p

        type T struct{ a int }

        func f(x int) int {
        	if <weak_warning descr="Redundant parentheses">(x > 0)</weak_warning> {
        		return <weak_warning descr="Redundant parentheses">(x)</weak_warning>
        	}
        	y := <weak_warning descr="Redundant parentheses">(x + 1)</weak_warning>
        	z := (x + 1) * 2
        	if (T{}) == (T{a: x}) {
        	}
        	var p <weak_warning descr="Redundant parentheses">(int)</weak_warning> = 1
        	q := -(-x)
        	return y + z + p + q
        }
        """,
        GoRedundantParensInspection(),
    )

    fun testRedundantParensFix() = doFix(
        "package p\n\nfunc f(x int) int {\n\treturn (<caret>x)\n}",
        "Remove redundant parentheses",
        "package p\n\nfunc f(x int) int {\n\treturn x\n}",
        GoRedundantParensInspection(),
    )

    // --- GoRedundantImportAlias ---

    fun testRedundantImportAlias() = doHighlight(
        """
        package p

        import (
        	<weak_warning descr="Redundant alias 'fmt'">fmt</weak_warning> "fmt"
        	str "strings"
        	_ "embed"
        )

        var _, _ = fmt.Sprint, str.ToUpper
        """,
        GoRedundantImportAliasInspection(),
    )

    fun testRedundantImportAliasFix() = doFix(
        "package p\n\nimport f<caret>mt \"fmt\"\n\nvar _ = fmt.Sprint",
        "Remove redundant alias",
        "package p\n\nimport \"fmt\"\n\nvar _ = fmt.Sprint",
        GoRedundantImportAliasInspection(),
    )

    // --- GoRedundantTypeDeclInCompositeLit ---

    fun testRedundantTypeDeclInCompositeLit() = doHighlight(
        """
        package p

        type P struct{ X int }

        var (
        	a = []P{<warning descr="Redundant type declaration">P</warning>{1}, {2}}
        	b = []*P{<warning descr="Redundant type declaration">&P</warning>{1}}
        	c = map[string]P{"k": <warning descr="Redundant type declaration">P</warning>{1}}
        	d = map[P]int{<warning descr="Redundant type declaration">P</warning>{1}: 2}
        	e = []any{P{1}}
        	f = [2]P{<warning descr="Redundant type declaration">P</warning>{1}}
        	g = []*P{{1}}
        )
        """,
        GoRedundantTypeDeclInCompositeLitInspection(),
    )

    fun testRedundantTypeDeclFix() = doFix(
        "package p\n\ntype P struct{ X int }\n\nvar a = []*P{&<caret>P{1}}",
        "Remove redundant type",
        "package p\n\ntype P struct{ X int }\n\nvar a = []*P{{1}}",
        GoRedundantTypeDeclInCompositeLitInspection(),
    )

    // --- GoVarAndConstTypeMayBeOmitted ---

    fun testVarAndConstTypeMayBeOmitted() = doHighlight(
        """
        package p

        import "fmt"

        var s <weak_warning descr="Type can be omitted">string</weak_warning> = fmt.Sprint(1)

        var n <weak_warning descr="Type can be omitted">int</weak_warning> = 1

        var f float64 = 1

        const c int = 1

        const d <weak_warning descr="Type can be omitted">int</weak_warning> = int(2)

        var e error = nil

        var w fmt.Stringer = nil

        var x, y <weak_warning descr="Type can be omitted">int</weak_warning> = 1, 2

        var z int
        """,
        GoVarAndConstTypeMayBeOmittedInspection(),
    )

    fun testVarTypeMayBeOmittedFix() = doFix(
        "package p\n\nvar n i<caret>nt = 1",
        "Remove type",
        "package p\n\nvar n = 1",
        GoVarAndConstTypeMayBeOmittedInspection(),
    )

    // --- GoUnusedTypeParameter ---

    fun testUnusedTypeParameter() = doHighlight(
        """
        package p

        func F[<warning descr="Unused type parameter 'T'">T</warning> any](x int) int { return x }

        func G[T any](x T) T { return x }

        func H[K comparable, V any](m map[K]V) {}

        func I[T any]() {
        	var x T
        	_ = x
        }

        func J[T any, S ~[]T](s S) {}

        type S[<warning descr="Unused type parameter 'T'">T</warning> any] struct{}

        type U[T any] struct{ v T }

        func K[_ any]() {}
        """,
        GoUnusedTypeParameterInspection(),
    )

    fun testUnusedTypeParameterFix() = doFix(
        "package p\n\nfunc F[<caret>T any](x int) int { return x }",
        "Rename to '_'",
        "package p\n\nfunc F[_ any](x int) int { return x }",
        GoUnusedTypeParameterInspection(),
    )
}
