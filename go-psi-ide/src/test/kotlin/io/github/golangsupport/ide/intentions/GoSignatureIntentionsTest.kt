package io.github.golangsupport.ide.intentions

import io.github.golangsupport.ide.GoSemanticIdeTestBase

/** GoLand parity G4, signatures: Expand signature types, Reuse signature types. */
class GoSignatureIntentionsTest : GoSemanticIdeTestBase() {

    private fun doTest(before: String, intention: String, after: String) {
        myFixture.configureByText("a.go", before.trimIndent() + "\n")
        val offered = myFixture.availableIntentions
        myFixture.launchAction(offered.singleOrNull { it.text == intention } ?: error("$intention not in ${offered.map { it.text }}"))
        myFixture.checkResult(after.trimIndent() + "\n")
    }

    private fun assertNotOffered(text: String, intention: String) {
        myFixture.configureByText("a.go", text.trimIndent() + "\n")
        val offered = myFixture.availableIntentions.map { it.text }
        assertFalse("$intention in $offered", intention in offered)
    }

    fun testExpandParametersAndResults() = doTest(
        """
        package p

        func <caret>foo(s1, s2 string) (i1, i2 int) {
        	return 0, 1
        }
        """,
        "Expand signature types",
        """
        package p

        func foo(s1 string, s2 string) (i1 int, i2 int) {
        	return 0, 1
        }
        """,
    )

    fun testExpandInAFunctionLiteral() = doTest(
        """
        package p

        var f = func(a, <caret>b []int, c bool) {}
        """,
        "Expand signature types",
        """
        package p

        var f = func(a []int, b []int, c bool) {}
        """,
    )

    fun testNoExpandWhenEveryParameterHasItsType() = assertNotOffered(
        """
        package p

        func foo(<caret>a int, b string) {}
        """,
        "Expand signature types",
    )

    fun testNoSignatureIntentionsInTheBody() {
        assertNotOffered("package p\n\nfunc foo(a, b int) {\n\t<caret>println(a, b)\n}\n", "Expand signature types")
    }

    fun testReuseAdjacentTypes() = doTest(
        """
        package p

        func (r *R) <caret>bar(s1 string, s2 string, n int, m int, x string) (i1 int, i2 int) {
        	return 0, 1
        }

        type R struct{}
        """,
        "Reuse signature types",
        """
        package p

        func (r *R) bar(s1, s2 string, n, m int, x string) (i1, i2 int) {
        	return 0, 1
        }

        type R struct{}
        """,
    )

    fun testReuseKeepsTheVariadicParameterAlone() = doTest(
        """
        package p

        func f(<caret>a int, b int, c ...int) {}
        """,
        "Reuse signature types",
        """
        package p

        func f(a, b int, c ...int) {}
        """,
    )

    fun testNoReuseForUnnamedOrDifferentTypes() {
        assertNotOffered("package p\n\nfunc f(<caret>int, int) {}\n", "Reuse signature types")
        assertNotOffered("package p\n\nfunc f(<caret>a int, b string) {}\n", "Reuse signature types")
    }
}
