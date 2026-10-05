package io.github.golangsupport.ide.intentions

import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.TestInputDialog
import io.github.golangsupport.ide.inspections.GoPrintfInspection
import io.github.golangsupport.ide.inspections.printf.GoPrintfFunctions

/** G4 printf: Add format string argument, Exclude / Mark as string formatting function (GoPrintfFunctions). */
class GoFormatIntentionsTest : GoIntentionTestSupport() {

    override fun setUp() {
        super.setUp()
        GoPrintfFunctions.getInstance().loadState(GoPrintfFunctions.State())
    }

    override fun tearDown() {
        try {
            GoPrintfFunctions.getInstance().loadState(GoPrintfFunctions.State())
            TestDialogManager.setTestInputDialog(TestInputDialog.DEFAULT)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun answer(expression: String) = TestDialogManager.setTestInputDialog { expression }

    fun testAddFormatStringArgumentAtTheEnd() {
        answer("len(items)")
        doTest(
            """
            package p

            import "fmt"

            func f(items []string, name string) {
            	fmt.Printf("%s has <caret> items\n", name)
            }
            """,
            "Add format string argument",
            """
            package p

            import "fmt"

            func f(items []string, name string) {
            	fmt.Printf("%s has %v items\n", name, len(items))
            }
            """,
        )
    }

    fun testAddFormatStringArgumentBeforeTheNextVerb() {
        answer("n")
        doTest(
            """
            package p

            import "fmt"

            func f(n int, name string) string {
            	return fmt.Sprintf("<caret> of %s", name)
            }
            """,
            "Add format string argument",
            """
            package p

            import "fmt"

            func f(n int, name string) string {
            	return fmt.Sprintf("%v of %s", n, name)
            }
            """,
        )
    }

    // G10, seen live on GoLand 2026.2.3: offered with the caret on a verb that has no argument; the argument is added for it, no new verb
    fun testAddFormatStringArgumentForAVerbWithoutOne() {
        answer("name")
        doTest(
            """
            package p

            import "fmt"

            func f(n int, name string) string {
            	return fmt.Sprintf("%d %<caret>s", n)
            }
            """,
            "Add format string argument",
            """
            package p

            import "fmt"

            func f(n int, name string) string {
            	return fmt.Sprintf("%d %s", n, name)
            }
            """,
        )
    }

    fun testPutArgumentsOnSeparateLinesInAFormatString() = assertOffered(
        """
        package p

        import "fmt"

        func f(n int) string {
        	return fmt.Sprintf("%d %<caret>s", n)
        }
        """,
        "Put arguments on separate lines",
    )

    fun testNoFormatArgumentOutsideAFormatString() = assertNotOffered(
        """
        package p

        import "fmt"

        func f(name string) {
        	fmt.Printf("%s\n", na<caret>me)
        }
        """,
        "Add format string argument",
    )

    fun testNoFormatArgumentWithIndexes() = assertNotOffered(
        """
        package p

        import "fmt"

        func f(name string) {
        	fmt.Printf("%[1]s <caret>\n", name)
        }
        """,
        "Add format string argument",
    )

    fun testNoFormatArgumentInAPrintCall() = assertNotOffered(
        """
        package p

        import "fmt"

        func f() {
        	fmt.Println("a <caret>b")
        }
        """,
        "Add format string argument",
    )

    fun testExcludeStringFormattingFunction() {
        myFixture.enableInspections(GoPrintfInspection())
        val text = """
            package p

            import "fmt"

            func f() {
            	fmt.Printf("<caret>%d items\n", "many")
            }
            """.trimIndent() + "\n"
        myFixture.configureByText("a.go", text)
        assertTrue(myFixture.doHighlighting().any { it.description?.contains("fmt.Printf format %d") == true })
        myFixture.launchAction(myFixture.findSingleIntention("Exclude string formatting function"))
        assertTrue(GoPrintfFunctions.getInstance().isExcluded("fmt.Printf"))
        assertFalse(myFixture.doHighlighting().any { it.description?.contains("fmt.Printf format") == true })
        assertEmpty(myFixture.filterAvailableIntentions("Exclude string formatting function"))
        // the inverse is offered on the excluded call and restores the check
        myFixture.launchAction(myFixture.findSingleIntention("Mark as string formatting function"))
        assertFalse(GoPrintfFunctions.getInstance().isExcluded("fmt.Printf"))
        assertTrue(myFixture.doHighlighting().any { it.description?.contains("fmt.Printf format %d") == true })
    }

    fun testMarkAsStringFormattingFunction() {
        myFixture.enableInspections(GoPrintfInspection())
        myFixture.configureByText(
            "a.go",
            """
            package p

            func report(format string, args ...any) {}

            func f() {
            	rep<caret>ort("%d items", "many")
            }
            """.trimIndent() + "\n",
        )
        assertFalse(myFixture.doHighlighting().any { it.description?.contains("format %d") == true })
        myFixture.launchAction(myFixture.findSingleIntention("Mark as string formatting function"))
        val name = GoPrintfFunctions.getInstance().state.extra.single()
        assertTrue(name, name.endsWith(".report"))
        assertTrue(myFixture.doHighlighting().any { it.description?.contains("report format %d") == true })
        assertNotEmpty(myFixture.filterAvailableIntentions("Exclude string formatting function"))
    }

    fun testNoMarkWithoutVariadicAny() = assertNotOffered(
        """
        package p

        func report(format string, n int) {}

        func f() {
        	rep<caret>ort("%d items", 1)
        }
        """,
        "Mark as string formatting function",
    )
}
