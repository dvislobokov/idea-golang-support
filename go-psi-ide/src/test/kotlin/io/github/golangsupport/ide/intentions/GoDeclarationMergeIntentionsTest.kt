package io.github.golangsupport.ide.intentions

import io.github.golangsupport.ide.GoSemanticIdeTestBase

/** GoLand parity G4, declarations: Merge declaration up, Merge declaration up via comma, Split declarations into two groups. */
class GoDeclarationMergeIntentionsTest : GoSemanticIdeTestBase() {

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

    // --- merge up ---

    fun testMergeDeclarationUp() = doTest(
        """
        package p

        var a string
        var <caret>b int
        """,
        "Merge declaration up",
        """
        package p

        var (
        	a string
        	b int
        )
        """,
    )

    fun testMergeDeclarationUpIntoAGroup() = doTest(
        """
        package p

        func f() {
        	const (
        		a = 1
        		b = 2
        	)
        	const <caret>c = 3
        	println(a, b, c)
        }
        """,
        "Merge declaration up",
        """
        package p

        func f() {
        	const (
        		a = 1
        		b = 2
        		c = 3
        	)
        	println(a, b, c)
        }
        """,
    )

    fun testNoMergeUpOfIotaOrAcrossAComment() {
        assertNotOffered("package p\n\nconst (\n\ta = iota\n)\nconst <caret>b = iota\n", "Merge declaration up")
        assertNotOffered("package p\n\nvar a int\n\n// b is documented\nvar <caret>b int\n", "Merge declaration up")
        assertNotOffered("package p\n\nvar a int\nconst <caret>b = 1\n", "Merge declaration up")
    }

    // --- merge via comma ---

    fun testMergeViaCommaWithType() = doTest(
        """
        package p

        var a int
        var <caret>b int
        """,
        "Merge declaration up via comma",
        """
        package p

        var a, b int
        """,
    )

    fun testMergeViaCommaWithValuesInAGroup() = doTest(
        """
        package p

        var (
        	a = 1
        	<caret>b = "s"
        )
        """,
        "Merge declaration up via comma",
        """
        package p

        var (
        	a, b = 1, "s"
        )
        """,
    )

    fun testMergeShortVarDeclarations() = doTest(
        """
        package p

        func f() {
        	a := 1
        	<caret>b := "s"
        	println(a, b)
        }
        """,
        "Merge declaration up via comma",
        """
        package p

        func f() {
        	a, b := 1, "s"
        	println(a, b)
        }
        """,
    )

    fun testNoMergeViaComma() {
        assertNotOffered("package p\n\nvar a int\nvar <caret>b string\n", "Merge declaration up via comma")
        assertNotOffered("package p\n\nfunc f() {\n\ta := 1\n\t<caret>b := a\n\tprintln(a, b)\n}\n", "Merge declaration up via comma")
        assertNotOffered("package p\n\nconst (\n\ta = iota\n\t<caret>b\n)\n", "Merge declaration up via comma")
        assertNotOffered("package p\n\nfunc g() (int, int) { return 1, 2 }\n\nfunc f() {\n\ta, c := g()\n\t<caret>b := 1\n\tprintln(a, b, c)\n}\n", "Merge declaration up via comma")
    }

    // --- split into two groups ---

    fun testSplitIntoTwoGroups() = doTest(
        """
        package p

        var (
        	a string
        	b int<caret>
        	c int
        )
        """,
        "Split declarations into two groups",
        """
        package p

        var a string
        var (
        	b int
        	c int
        )
        """,
    )

    fun testSplitIntoTwoGroupsAtTheLastSpec() = doTest(
        """
        package p

        const (
        	a = 1
        	b = 2
        	<caret>c = 3
        )
        """,
        "Split declarations into two groups",
        """
        package p

        const (
        	a = 1
        	b = 2
        )
        const c = 3
        """,
    )

    fun testNoSplitIntoTwoGroupsAtTheFirstSpecOrWithIota() {
        assertNotOffered("package p\n\nvar (\n\t<caret>a int\n\tb int\n)\n", "Split declarations into two groups")
        assertNotOffered("package p\n\nconst (\n\ta = iota\n\t<caret>b\n)\n", "Split declarations into two groups")
    }
}
