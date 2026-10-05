package io.github.golangsupport.ide.intentions

import io.github.golangsupport.ide.GoSemanticIdeTestBase

/** GoLand parity G4, expressions: Flip binary operator, the four Negate expression variants, Specify type explicitly. */
class GoExpressionIntentionsTest : GoSemanticIdeTestBase() {

    private fun doTest(before: String, intention: String, after: String) {
        myFixture.configureByText("a.go", before.trimIndent() + "\n")
        val offered = myFixture.availableIntentions
        myFixture.launchAction(offered.singleOrNull { it.text == intention } ?: error("$intention not in ${offered.map { it.text }}"))
        myFixture.checkResult(after.trimIndent() + "\n")
    }

    private fun assertNotOffered(text: String, prefix: String) {
        myFixture.configureByText("a.go", text.trimIndent() + "\n")
        val offered = myFixture.availableIntentions.map { it.text }
        assertTrue("$prefix in $offered", offered.none { it.startsWith(prefix) })
    }

    // --- flip ---

    fun testFlipComparisonMirrorsTheOperator() = doTest(
        """
        package p

        func f(x int) bool { return x <caret>>= 0 && x <= 100 }
        """,
        "Flip '>=' to '<='",
        """
        package p

        func f(x int) bool { return 0 <= x && x <= 100 }
        """,
    )

    fun testFlipCommutativeKeepsSpacingAndParenthesizesTheOldLeft() = doTest(
        """
        package p

        func f(a, b, c int) int { return a - b <caret>+ c }
        """,
        "Flip '+'",
        """
        package p

        func f(a, b, c int) int { return c + (a - b) }
        """,
    )

    fun testFlipAndChainNeedsNoParentheses() = doTest(
        """
        package p

        func f(a, b, c bool) bool { return a && b <caret>&& c }
        """,
        "Flip '&&'",
        """
        package p

        func f(a, b, c bool) bool { return c && a && b }
        """,
    )

    fun testFlipStringConcatenationChangesSemantics() = doTest(
        """
        package p

        func f(a, b string) string { return a <caret>+ b }
        """,
        "Flip '+' (changes semantics)",
        """
        package p

        func f(a, b string) string { return b + a }
        """,
    )

    fun testNoFlipOutsideABinaryExpression() = assertNotOffered(
        """
        package p

        func f(a int) int { return <caret>a }
        """,
        "Flip",
    )

    // --- negate ---

    private val condition = """
        package p

        func f(x int) {
        	if x < 0 |<caret>| 0 < x && x < 10 {
        		println(x)
        	}
        }
        """

    fun testNegateExpression() = doTest(
        condition,
        "Negate '||' to '&&'",
        """
        package p

        func f(x int) {
        	if !(x >= 0 && !(0 < x && x < 10)) {
        		println(x)
        	}
        }
        """,
    )

    fun testNegateExpressionRecursively() = doTest(
        condition,
        "Negate '||' to '&&' recursively",
        """
        package p

        func f(x int) {
        	if !(x >= 0 && (0 >= x || x >= 10)) {
        		println(x)
        	}
        }
        """,
    )

    fun testNegateRemovesTheNotAroundTheExpression() = doTest(
        """
        package p

        func f(a, b int) bool { return !(a <caret>== b) }
        """,
        "Negate '==' to '!='",
        """
        package p

        func f(a, b int) bool { return a != b }
        """,
    )

    fun testNegateTopmostExpression() = doTest(
        """
        package p

        func f(ok bool, x int) bool { return ok && (x <caret>< 0 || x > 10) }
        """,
        "Negate topmost '&&' to '||'",
        """
        package p

        func f(ok bool, x int) bool { return !(!ok || !(x < 0 || x > 10)) }
        """,
    )

    fun testNegateTopmostExpressionRecursively() = doTest(
        """
        package p

        func f(ok bool, x int) bool { return ok && (x <caret>< 0 || x > 10) }
        """,
        "Negate topmost '&&' to '||' recursively",
        """
        package p

        func f(ok bool, x int) bool { return !(!ok || x >= 0 && x <= 10) }
        """,
    )

    fun testNoTopmostOrRecursiveWhenTheyWriteTheSame() {
        myFixture.configureByText("a.go", "package p\n\nfunc f(a, b int) bool { return a <caret>< b }\n")
        val offered = myFixture.availableIntentions.map { it.text }.filter { it.startsWith("Negate") }
        assertEquals(listOf("Negate '<' to '>='"), offered)
    }

    fun testNoNegateOfAFloatOrdering() = assertNotOffered(
        """
        package p

        func f(a, b float64) bool { return a <caret>< b }
        """,
        "Negate",
    )

    // --- specify type ---

    fun testSpecifyTypeOfVar() = doTest(
        """
        package p

        var <caret>i = 1
        """,
        "Specify type explicitly",
        """
        package p

        var i int = 1
        """,
    )

    fun testSpecifyTypeOfConstAndSeveralNames() = doTest(
        """
        package p

        func f() {
        	const a, <caret>b = "x", "y"
        	_, _ = a, b
        }
        """,
        "Specify type explicitly",
        """
        package p

        func f() {
        	const a, b string = "x", "y"
        	_, _ = a, b
        }
        """,
    )

    fun testSpecifyTypeQualifiesAnotherPackage() = doTest(
        """
        package p

        import "time"

        var <caret>d = time.Second
        """,
        "Specify type explicitly",
        """
        package p

        import "time"

        var d time.Duration = time.Second
        """,
    )

    fun testNoSpecifyTypeForDifferentTypesOrATypedSpec() {
        assertNotOffered("package p\n\nvar <caret>a, b = 1, \"s\"\n", "Specify type explicitly")
        assertNotOffered("package p\n\nvar <caret>a int = 1\n", "Specify type explicitly")
        assertNotOffered("package p\n\nvar <caret>a = nil\n", "Specify type explicitly")
    }
}
