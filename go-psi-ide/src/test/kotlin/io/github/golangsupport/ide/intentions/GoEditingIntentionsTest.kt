package io.github.golangsupport.ide.intentions

import io.github.golangsupport.ide.GoSemanticIdeTestBase

/** The editing intentions of `ide.intentions` (FEATURES.md section 11, wave 2 G): change quote, `if` / `switch`, declarations. */
class GoEditingIntentionsTest : GoSemanticIdeTestBase() {

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

    // --- change quote ---

    fun testInterpretedToRaw() = doTest(
        """
        package p

        var s = <caret>"C:\\go\tbin \"x\" \u00e9\x41"
        """,
        "Convert to raw string literal",
        """
        package p

        var s = `C:\go	bin "x" éA`
        """,
    )

    fun testRawToInterpreted() = doTest(
        """
        package p

        var s = `a "b"<caret>
        c\d`
        """,
        "Convert to interpreted string literal",
        """
        package p

        var s = "a \"b\"\nc\\d"
        """,
    )

    fun testNoRawStringWithABackquote() = assertNotOffered(
        """
        package p

        var s = <caret>"a`b"
        """,
        "Convert to raw string literal",
    )

    fun testNoRawStringWithANewline() = assertNotOffered(
        """
        package p

        var s = <caret>"a\nb"
        """,
        "Convert to raw string literal",
    )

    // --- if ---

    fun testInvertIfKeepsComments() = doTest(
        """
        package p

        func f(n int, ok bool) {
        	<caret>if n > 0 && ok {
        		// positive
        		use(n)
        	} else {
        		skip() // nothing
        	}
        }

        func use(int) {}
        func skip()   {}
        """,
        "Invert 'if' condition",
        """
        package p

        func f(n int, ok bool) {
        	if n <= 0 || !ok {
        		skip() // nothing
        	} else {
        		// positive
        		use(n)
        	}
        }

        func use(int) {}
        func skip()   {}
        """,
    )

    fun testInvertIfKeepsFloatOrderingNegated() = doTest(
        """
        package p

        func f(x float64) int {
        	if x < 1<caret> {
        		return 1
        	} else {
        		return 2
        	}
        }
        """,
        "Invert 'if' condition",
        """
        package p

        func f(x float64) int {
        	if !(x < 1) {
        		return 2
        	} else {
        		return 1
        	}
        }
        """,
    )

    fun testInvertIfFromTheElseKeyword() = doTest(
        """
        package p

        func f(a, b bool) {
        	if !a || (b && a) {
        		println(1)
        	} el<caret>se {
        		println(2)
        	}
        }
        """,
        "Invert 'if' condition",
        """
        package p

        func f(a, b bool) {
        	if a && !(b && a) {
        		println(2)
        	} else {
        		println(1)
        	}
        }
        """,
    )

    fun testInvertIfWithEarlyReturn() = doTest(
        """
        package p

        func f(ok bool) {
        	println(0)
        	<caret>if ok {
        		x := 1
        		println(x)
        	}
        }
        """,
        "Invert 'if' with early return",
        """
        package p

        func f(ok bool) {
        	println(0)
        	if !ok {
        		return
        	}
        	x := 1
        	println(x)
        }
        """,
    )

    fun testInvertIfWithEarlyReturnOfAOneLineBody() = doTest(
        """
        package p

        func f(ok bool) {
        	if<caret> ok { println(1) }
        }
        """,
        "Invert 'if' with early return",
        """
        package p

        func f(ok bool) {
        	if !ok {
        		return
        	}
        	println(1)
        }
        """,
    )

    fun testInvertIfWithEarlyContinue() = doTest(
        """
        package p

        func f(xs []int) {
        	for _, x := range xs {
        		if x != 0<caret> {
        			println(x)
        		}
        	}
        }
        """,
        "Invert 'if' with early continue",
        """
        package p

        func f(xs []int) {
        	for _, x := range xs {
        		if x == 0 {
        			continue
        		}
        		println(x)
        	}
        }
        """,
    )

    fun testNoEarlyReturnWithResultsOrAClash() {
        assertNotOffered(
            """
            package p

            func f(ok bool) int {
            	<caret>if ok {
            		println(1)
            	}
            	return 0
            }
            """,
            "Invert 'if' with early return",
        )
        assertNotOffered(
            """
            package p

            func f(x int, ok bool) {
            	<caret>if ok {
            		x := 2
            		println(x)
            	}
            }
            """,
            "Invert 'if' with early return",
        )
    }

    fun testMergeNestedIf() = doTest(
        """
        package p

        func f(a, b, c bool) {
        	<caret>if a || b {
        		if c {
        			println(1)
        		}
        	}
        }
        """,
        "Merge nested 'if'",
        """
        package p

        func f(a, b, c bool) {
        	if (a || b) && c {
        		println(1)
        	}
        }
        """,
    )

    fun testNoMergeWithElse() = assertNotOffered(
        """
        package p

        func f(a, c bool) {
        	<caret>if a {
        		if c {
        			println(1)
        		}
        	} else {
        		println(2)
        	}
        }
        """,
        "Merge nested 'if'",
    )

    fun testSplitIfConditionAtTheCaret() = doTest(
        """
        package p

        func f(a, b, c bool) {
        	if a <caret>&& b && c {
        		println(1)
        	}
        }
        """,
        "Split 'if' condition",
        """
        package p

        func f(a, b, c bool) {
        	if a {
        		if b && c {
        			println(1)
        		}
        	}
        }
        """,
    )

    fun testIfToSwitch() = doTest(
        """
        package p

        func mode() int { return 0 }

        func f() {
        	<caret>if m := mode(); m == 1 {
        		println("fast")
        	} else if m == 2 || 3 == m {
        		println("slow")
        	} else {
        		println("none")
        	}
        }
        """,
        "Convert 'if' to 'switch'",
        """
        package p

        func mode() int { return 0 }

        func f() {
        	switch m := mode(); m {
        	case 1:
        		println("fast")
        	case 2, 3:
        		println("slow")
        	default:
        		println("none")
        	}
        }
        """,
    )

    fun testNoIfToSwitchForAnotherVariableOrOperator() {
        assertNotOffered(
            """
            package p

            func f(x, y int) {
            	<caret>if x == 1 {
            		println(1)
            	} else if y == 2 {
            		println(2)
            	}
            }
            """,
            "Convert 'if' to 'switch'",
        )
        assertNotOffered(
            """
            package p

            func f(x int) {
            	<caret>if x == 1 {
            		println(1)
            	} else if x < 2 {
            		println(2)
            	}
            }
            """,
            "Convert 'if' to 'switch'",
        )
    }

    fun testSwitchToIfMovesDefaultLast() = doTest(
        """
        package p

        func f(x int) {
        	<caret>switch x {
        	default:
        		println("other")
        	case 1, 2:
        		println("small")
        	case 3:
        	}
        }
        """,
        "Convert 'switch' to 'if'",
        """
        package p

        func f(x int) {
        	if x == 1 || x == 2 {
        		println("small")
        	} else if x == 3 {
        	} else {
        		println("other")
        	}
        }
        """,
    )

    fun testNoSwitchToIfWithFallthrough() = assertNotOffered(
        """
        package p

        func f(x int) {
        	<caret>switch x {
        	case 1:
        		fallthrough
        	case 2:
        		println(2)
        	}
        }
        """,
        "Convert 'switch' to 'if'",
    )

    // --- declarations ---

    fun testSplitNames() = doTest(
        """
        package p

        func f() {
        	var <caret>a, b = 1, "x"
        	println(a, b)
        }
        """,
        "Split into separate declarations",
        """
        package p

        func f() {
        	var a = 1
        	var b = "x"
        	println(a, b)
        }
        """,
    )

    fun testSplitGroup() = doTest(
        """
        package p

        var (<caret>
        	a int
        	b = 2
        )
        """,
        "Split into separate declarations",
        """
        package p

        var a int
        var b = 2
        """,
    )

    fun testNoSplitOfAnIotaGroup() = assertNotOffered(
        """
        package p

        const (<caret>
        	A = iota
        	B
        )
        """,
        "Split into separate declarations",
    )

    fun testGroupDeclarations() = doTest(
        """
        package p

        func f() {
        	var name string
        	var <caret>count = 1
        	println(name, count)
        }
        """,
        "Group declarations",
        """
        package p

        func f() {
        	var (
        		name string
        		count = 1
        	)
        	println(name, count)
        }
        """,
    )

    fun testJoinDeclarationAndAssignment() = doTest(
        """
        package p

        func count() int { return 0 }

        func f() {
        	var <caret>n int
        	n = count()
        	println(n)
        }
        """,
        "Join declaration and assignment",
        """
        package p

        func count() int { return 0 }

        func f() {
        	n := count()
        	println(n)
        }
        """,
    )

    fun testJoinKeepsTheTypeWhenTheValueDiffers() = doTest(
        """
        package p

        func f() {
        	var <caret>x float64
        	x = 1
        	println(x)
        }
        """,
        "Join declaration and assignment",
        """
        package p

        func f() {
        	var x float64 = 1
        	println(x)
        }
        """,
    )

    fun testShortVarToVar() = doTest(
        """
        package p

        type T struct{}

        func mk() *T { return nil }

        func f() {
        	<caret>t := mk()
        	n := 1
        	println(t, n)
        }
        """,
        "Convert to 'var' declaration",
        """
        package p

        type T struct{}

        func mk() *T { return nil }

        func f() {
        	var t *T = mk()
        	n := 1
        	println(t, n)
        }
        """,
    )

    fun testVarToShortVar() = doTest(
        """
        package p

        func f(xs []int) {
        	var <caret>n int = len(xs)
        	println(n)
        }
        """,
        "Convert to short variable declaration",
        """
        package p

        func f(xs []int) {
        	n := len(xs)
        	println(n)
        }
        """,
    )

    fun testNoShortVarWhenTheTypesDiffer() = assertNotOffered(
        """
        package p

        func f() {
        	var <caret>x float64 = 1
        	println(x)
        }
        """,
        "Convert to short variable declaration",
    )
}
