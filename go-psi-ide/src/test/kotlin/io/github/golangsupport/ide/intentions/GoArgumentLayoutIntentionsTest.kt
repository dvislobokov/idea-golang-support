package io.github.golangsupport.ide.intentions

/** G4 layout and strings: Put arguments / elements on separate lines and back, Join concatenated string literals. */
class GoArgumentLayoutIntentionsTest : GoIntentionTestSupport() {

    fun testSplitArguments() = doTest(
        """
        package p

        func f(a, b int, c ...string) {}

        func g(xs []string) {
        	f(1, <caret>2, xs...)
        }
        """,
        "Put arguments on separate lines",
        """
        package p

        func f(a, b int, c ...string) {}

        func g(xs []string) {
        	f(
        		1,
        		2,
        		xs...,
        	)
        }
        """,
    )

    fun testSplitMovesTheLinesOfAFunctionLiteral() = doTest(
        """
        package p

        func run(name string, f func()) {}

        func g() {
        	run(<caret>"x", func() {
        		println()
        	})
        }
        """,
        "Put arguments on separate lines",
        """
        package p

        func run(name string, f func()) {}

        func g() {
        	run(
        		"x",
        		func() {
        			println()
        		},
        	)
        }
        """,
    )

    fun testSplitElements() = doTest(
        """
        package p

        var xs = []int{1, <caret>2, 3}
        """,
        "Put elements on separate lines",
        """
        package p

        var xs = []int{
        	1,
        	2,
        	3,
        }
        """,
    )

    fun testJoinArguments() = doTest(
        """
        package p

        func f(a, b int) {}

        func g() {
        	f(
        		1<caret>,
        		2,
        	)
        }
        """,
        "Put arguments on one line",
        """
        package p

        func f(a, b int) {}

        func g() {
        	f(1, 2)
        }
        """,
    )

    fun testNoSplitOfASplitList() = assertNotOffered(
        """
        package p

        var xs = []int{
        	1,
        	<caret>2,
        }
        """,
        "Put elements on separate lines",
    )

    fun testNoJoinOfAListWithAComment() = assertNotOffered(
        """
        package p

        func f(a, b int) {}

        func g() {
        	f(
        		1<caret>, // one
        		2,
        	)
        }
        """,
        "Put arguments on one line",
    )

    fun testNoSplitInsideAFunctionLiteralBody() = assertNotOffered(
        """
        package p

        func run(name string, f func()) {}

        func g() {
        	run("x", func() {
        		<caret>println()
        	})
        }
        """,
        "Put arguments on separate lines",
    )

    // --- join concatenated string literals ---

    fun testJoinInterpretedLiterals() = doTest(
        """
        package p

        func f(name string) string {
        	return "Hello,\t" <caret>+ "\"world\"" + name + "!" + "\n"
        }
        """,
        "Join concatenated string literals",
        """
        package p

        func f(name string) string {
        	return "Hello,\t\"world\"" + name + "!\n"
        }
        """,
    )

    fun testJoinRawLiterals() = doTest(
        """
        package p

        var s = <caret>`a\b` + `c`
        """,
        "Join concatenated string literals",
        """
        package p

        var s = `a\bc`
        """,
    )

    fun testJoinMixedLiteralsQuotesTheRawOne() = doTest(
        """
        package p

        var s = <caret>"a\n" + `C:\go "x"`
        """,
        "Join concatenated string literals",
        """
        package p

        var s = "a\nC:\\go \"x\""
        """,
    )

    fun testNoJoinWithoutAdjacentLiterals() = assertNotOffered(
        """
        package p

        func f(name string) string {
        	return "a" <caret>+ name + "b"
        }
        """,
        "Join concatenated string literals",
    )
}
