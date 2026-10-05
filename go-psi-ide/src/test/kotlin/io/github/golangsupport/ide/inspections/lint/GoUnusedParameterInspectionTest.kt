package io.github.golangsupport.ide.inspections.lint

import io.github.golangsupport.ide.GoSemanticIdeTestBase

/** Unused parameters (gopls `unusedparams`): what is reported, what is skipped, and both fixes. */
class GoUnusedParameterInspectionTest : GoSemanticIdeTestBase() {

    private fun doHighlight(text: String, fileName: String = "a.go") {
        myFixture.enableInspections(GoUnusedParameterInspection())
        myFixture.configureByText(fileName, text.trimIndent() + "\n")
        myFixture.checkHighlighting(true, false, true)
    }

    private fun fixesAt(text: String, others: Map<String, String> = emptyMap()): List<String> {
        myFixture.enableInspections(GoUnusedParameterInspection())
        for ((name, content) in others) myFixture.addFileToProject(name, content.trimIndent() + "\n")
        myFixture.configureByText("a.go", text.trimIndent() + "\n")
        return myFixture.availableIntentions.map { it.text }.filter { it == RENAME || it == REMOVE }
    }

    private fun doFix(before: String, fix: String, after: String, others: Map<String, Pair<String, String>> = emptyMap()) {
        myFixture.enableInspections(GoUnusedParameterInspection())
        for ((name, content) in others) myFixture.addFileToProject(name, content.first.trimIndent() + "\n")
        myFixture.configureByText("a.go", before.trimIndent() + "\n")
        val offered = myFixture.availableIntentions
        myFixture.launchAction(offered.singleOrNull { it.text == fix } ?: error("$fix not in ${offered.map { it.text }}"))
        myFixture.checkResult(after.trimIndent() + "\n")
        for ((name, content) in others) myFixture.checkResult(name, content.second.trimIndent() + "\n", true)
    }

    fun testReported() = doHighlight(
        """
        package p

        type S struct{}

        func u9add(a, <warning descr="Unused parameter 'b'">b</warning> int) int {
        	return a
        }

        func (s S) u9method(<warning descr="Unused parameter 'x string'">x string</warning>, y int) int {
        	f := func() int { return y }
        	return f()
        }

        func u9shadow(<warning descr="Unused parameter 'v int'">v int</warning>) {
        	v := 2
        	println(v)
        }

        func u9calls() {
        	println(u9add(1, 2), S{}.u9method("", 1))
        	u9shadow(1)
        }
        """,
    )

    fun testExportedAndTestingHelpersSkipped() = doHighlight(
        """
        package p

        import "testing"

        // exported: callers outside the module fix the signature (GOROOT noise)
        func U9Exported(a, b int) int { return a }

        type U9T struct{}

        func (U9T) U9Method(x string) int { return 1 }

        // cmd/cgo/internal/test: helpers keep the `t *testing.T` shape
        func u9helper(t *testing.T) { println() }

        func u9use() { println(U9Exported(1, 2), U9T{}.U9Method("")); u9helper(nil) }
        """,
    )

    // cmd/compile: `arch.LoadRegResult = loadRegResult` and `(*Name).doChildren` implementing the unexported `Node.doChildren`
    fun testFieldAssignedAndUnexportedInterfaceSkipped() = doHighlight(
        """
        package p

        type u9Node interface{ doKids(do func(u9Node) bool) bool }

        type u9Name struct{}

        func (n *u9Name) doKids(do func(u9Node) bool) bool { return false }

        type u9Arch struct{ load func(a, b int) int }

        func u9load(a, b int) int { return a }

        func u9setup(arch *u9Arch) { arch.load = u9load; var _ u9Node = &u9Name{} }
        """,
    )

    fun testSkipped() = doHighlight(
        """
        package p

        import "net/http"

        type I interface{ u9impl(x int) }

        type T struct{}

        func (T) u9impl(x int) { println() }

        func u9asm(x int)

        func u9empty(x int) {}

        func u9panics(x int) {
        	panic("not implemented")
        }

        func init() { println() }

        func u9handler(w http.ResponseWriter, r *http.Request) { println() }

        func u9callback(x int) { println() }

        var u9hook = u9callback

        //export u9exported
        func u9exported(x int) { println() }

        func u9blank(_ int, y int) { println(y) }

        func u9lit() {
        	f := func(x int) { println() }
        	f(1)
        	var _ I = T{}
        }

        func u9unresolved(x int) { println(x.y) }
        """,
    )

    fun testTestFunctionsSkipped() = doHighlight(
        """
        package p

        import "testing"

        func TestU9(t *testing.T) { println() }

        func BenchmarkU9(b *testing.B) { println() }

        func u9helper(t *testing.T, <warning descr="Unused parameter 'n int'">n int</warning>) { t.Log() }
        """,
        "u9_test.go",
    )

    fun testRenameToBlank() = doFix(
        """
        package p

        func u9f(a, <caret>b int) int {
        	return a
        }
        """,
        RENAME,
        """
        package p

        func u9f(a, _ int) int {
        	return a
        }
        """,
    )

    fun testRemoveFromGroupAcrossFiles() = doFix(
        """
        package p

        func u9g(a, <caret>b int) int {
        	return a
        }

        func u9use() int { return u9g(1, 2) + u9g(3, 4) }
        """,
        REMOVE,
        """
        package p

        func u9g(a int) int {
        	return a
        }

        func u9use() int { return u9g(1) + u9g(3) }
        """,
        mapOf(
            "b.go" to (
                """
                package p

                func u9other(x int) int { return u9g(x, x+1) }
                """ to
                    """
                package p

                func u9other(x int) int { return u9g(x) }
                """
                ),
        ),
    )

    fun testRemoveFirstOfGroupAndMethod() = doFix(
        """
        package p

        type S struct{}

        func (s S) u9m(<caret>a, b int) int {
        	return b
        }

        func u9use(s S) int { return s.u9m(1, 2) }
        """,
        REMOVE,
        """
        package p

        type S struct{}

        func (s S) u9m(b int) int {
        	return b
        }

        func u9use(s S) int { return s.u9m(2) }
        """,
    )

    fun testRemoveVariadic() = doFix(
        """
        package p

        func u9v(a int, <caret>xs ...int) int {
        	return a
        }

        func u9use(s []int) int { return u9v(1, 2, 3) + u9v(1) + u9v(1, s...) }
        """,
        REMOVE,
        """
        package p

        func u9v(a int) int {
        	return a
        }

        func u9use(s []int) int { return u9v(1) + u9v(1) + u9v(1) }
        """,
    )

    fun testRemoveOnlyParameterAndUnusedImport() = doFix(
        """
        package p

        import "time"

        func u9t(<caret>d time.Duration) {
        	println()
        }

        func u9use() {
        	u9t(time.Second)
        }
        """,
        REMOVE,
        """
        package p

        func u9t() {
        	println()
        }

        func u9use() {
        	u9t()
        }
        """,
    )

    fun testRemoveNotOfferedWithSideEffectArgument() = assertEquals(
        listOf(RENAME),
        fixesAt(
            """
            package p

            func u9next() int { return 0 }

            func u9s(a, <caret>b int) int { return a }

            func u9use() int { return u9s(1, u9next()) }
            """,
        ),
    )

    fun testRemoveNotOfferedWithReceiveArgument() = assertEquals(
        listOf(RENAME),
        fixesAt(
            """
            package p

            func u9r(a, <caret>b int) int { return a }

            func u9use(c chan int) int { return u9r(1, <-c) }
            """,
        ),
    )

    fun testRemoveNotOfferedWithMethodExpression() = assertEquals(
        listOf(RENAME),
        fixesAt(
            """
            package p

            type S struct{}

            func (S) u9me(a, <caret>b int) int { return a }

            func u9use() int { return S.u9me(S{}, 1, 2) }
            """,
        ),
    )

    fun testNotReportedWhenUsedAsValueInAnotherFile() = assertEquals(
        emptyList<String>(),
        fixesAt(
            """
            package p

            func u9cb(a, <caret>b int) int { return a }
            """,
            mapOf("b.go" to "package p\n\nfunc u9run(f func(int, int) int) int { return f(1, 2) }\n\nvar _ = u9run(u9cb)"),
        ),
    )

    private companion object {
        const val RENAME = "Rename to _"
        const val REMOVE = "Remove unused parameter"
    }
}
