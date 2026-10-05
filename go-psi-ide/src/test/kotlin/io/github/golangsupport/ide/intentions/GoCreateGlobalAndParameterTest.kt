package io.github.golangsupport.ide.intentions

import io.github.golangsupport.ide.GoSemanticIdeTestBase

/** GoLand parity G4, unresolved names: Create global variable 'x', Create parameter 'x'. */
class GoCreateGlobalAndParameterTest : GoSemanticIdeTestBase() {

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

    // --- global variable ---

    fun testCreateGlobalVariableAfterTheImports() = doTest(
        """
        package p

        import "time"

        func f() time.Duration {
        	return <caret>timeout
        }
        """,
        "Create global variable 'timeout'",
        """
        package p

        import "time"

        var timeout time.Duration

        func f() time.Duration {
        	return timeout
        }
        """,
    )

    fun testCreateGlobalVariableOfAFunctionTypeForACall() = doTest(
        """
        package p

        func f(items []string) {
        	<caret>missing(items)
        }
        """,
        "Create global variable 'missing'",
        """
        package p

        var missing func(items []string)

        func f(items []string) {
        	missing(items)
        }
        """,
    )

    fun testNoGlobalVariableAtPackageLevelOrWhenResolved() {
        assertNotOffered("package p\n\nvar x = <caret>y\n", "Create global variable")
        assertNotOffered("package p\n\nvar y int\n\nfunc f() int { return <caret>y }\n", "Create global variable")
    }

    // --- parameter ---

    fun testCreateParameterUpdatesTheCalls() {
        val other = myFixture.addFileToProject("b.go", "package p\n\nfunc g() {\n\t_ = f(1)\n}\n")
        doTest(
            """
            package p

            func f(n int) int {
            	return n + <caret>limit
            }
            """,
            "Create parameter 'limit'",
            """
            package p

            func f(n int, limit int) int {
            	return n + limit
            }
            """,
        )
        assertEquals("package p\n\nfunc g() {\n\t_ = f(1, 0)\n}\n", other.text)
    }

    fun testCreateParameterBeforeAVariadicOneOfALiteral() = doTest(
        """
        package p

        var f = func(xs ...int) string {
        	return <caret>prefix
        }
        """,
        "Create parameter 'prefix'",
        """
        package p

        var f = func(prefix string, xs ...int) string {
        	return prefix
        }
        """,
    )

    fun testCreateParameterInAnEmptyList() = doTest(
        """
        package p

        func f() {
        	<caret>done = true
        }
        """,
        "Create parameter 'done'",
        """
        package p

        func f(done bool) {
        	done = true
        }
        """,
    )

    fun testNoParameterAtPackageLevel() = assertNotOffered("package p\n\nvar x = <caret>y\n", "Create parameter")
}
