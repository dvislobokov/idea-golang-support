package io.github.golangsupport.ide.completion

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.util.ThreeState

/** Printf verbs in the format string of printf-like calls ([GoFormatVerbProvider]). */
class GoFormatVerbCompletionTest : GoCompletionTestBase() {

    fun testVerbsAfterPercent() {
        val items = lookups(
            """
            package main

            import "fmt"

            func f(x any) {
                fmt.Printf("x=%<caret>", x)
            }
            """,
        )
        assertContainsAll(items, "%v", "%+v", "%#v", "%T", "%d", "%s", "%q", "%x", "%t", "%f", "%.2f", "%p", "%%")
        // `%w` only in Errorf.
        assertContainsNone(items, "%w")
    }

    fun testRankedByTheNextArgumentType() {
        val items = lookups(
            """
            package main

            import "fmt"

            func f(name string, n int, ratio float64) {
                fmt.Printf("%s %<caret>", name, n, ratio)
            }
            """,
        )
        assertEquals("%d", items.first())
        val floats = lookups(
            """
            package main

            import "fmt"

            func f(name string, n int, ratio float64) {
                fmt.Printf("%s %d %<caret>", name, n, ratio)
            }
            """,
        )
        assertEquals("%f", floats.first())
    }

    fun testErrorfOffersWrap() {
        val items = lookups(
            """
            package main

            import "fmt"

            func f(err error) error {
                return fmt.Errorf("read: %<caret>", err)
            }
            """,
        )
        assertEquals("%w", items.first())
    }

    fun testFlagsAndWidthKept() {
        val items = lookups(
            """
            package main

            import "fmt"

            func f(n int) {
                fmt.Printf("%-8<caret>", n)
            }
            """,
        )
        assertContainsAll(items, "%-8d", "%-8v", "%-8s")
        assertContainsNone(items, "%+v", "%%")
        assertEquals("%-8d", items.first())
    }

    fun testInsertReplacesTheTypedPrefix() = checkInsert(
        """
        package main

        import "fmt"

        func f(n int) {
            fmt.Printf("n=%<caret>\n", n)
        }
        """,
        "%d",
        """
        package main

        import "fmt"

        func f(n int) {
            fmt.Printf("n=%d<caret>\n", n)
        }
        """,
    )

    fun testNothingOutsideFormatStrings() {
        // Not a printf-like call, an escaped percent, and a Println argument.
        for (code in listOf("_ = strings.Repeat(\"%<caret>\", 2)", "fmt.Printf(\"100%%<caret>\")", "fmt.Println(\"%<caret>\")")) {
            val items = complete(
                """
                package main

                import (
                    "fmt"
                    "strings"
                )

                func f() {
                    $code
                }
                """,
            )
            assertTrue("$code: ${items?.map { it.lookupString }}", items == null || items.none { it.lookupString.startsWith("%") })
        }
    }

    fun testUserWrapper() {
        val items = lookups(
            """
            package main

            import "fmt"

            func logf(format string, args ...any) {
                fmt.Printf(format, args...)
            }

            func f(ok bool) {
                logf("ok=%<caret>", ok)
            }
            """,
        )
        assertEquals("%t", items.first())
    }

    fun testDirectivePrefix() {
        assertEquals("%", GoFormatVerbCompletion.directivePrefix("x=%"))
        assertEquals("%-8.2", GoFormatVerbCompletion.directivePrefix("%-8.2"))
        assertEquals("%[2]*", GoFormatVerbCompletion.directivePrefix("a %[2]*"))
        assertEquals(null, GoFormatVerbCompletion.directivePrefix("100%%"))
        assertEquals("%", GoFormatVerbCompletion.directivePrefix("100%%%"))
        assertEquals(null, GoFormatVerbCompletion.directivePrefix("%d"))
        assertEquals(null, GoFormatVerbCompletion.directivePrefix("plain"))
    }

    fun testPercentOpensThePopup() {
        myFixture.configureByText("main.go", go(
            """
            package main

            import "fmt"

            func f(n int) {
                fmt.Printf("n=<caret>", n)
                fmt.Printf("100%<caret>", n)
                x := n % 2
            }
            """,
        ))
        val handler = GoFormatVerbTypedHandler()
        val carets = myFixture.editor.caretModel.allCarets.map { it.offset }
        val results = carets.map { offset ->
            myFixture.editor.caretModel.removeSecondaryCarets()
            myFixture.editor.caretModel.moveToOffset(offset)
            handler.checkAutoPopup('%', project, myFixture.editor, myFixture.file)
        }
        assertEquals(TypedHandlerDelegate.Result.STOP, results[0])
        // `%%`: the typed `%` escapes the one before it.
        assertEquals(TypedHandlerDelegate.Result.CONTINUE, results[1])
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("% 2"))
        assertEquals(TypedHandlerDelegate.Result.CONTINUE, handler.checkAutoPopup('%', project, myFixture.editor, myFixture.file))
    }

    fun testConfidenceLetsTheDirectivePopUp() {
        myFixture.configureByText("main.go", go(
            """
            package main

            import "fmt"

            func f(n int) {
                fmt.Printf("n=%", n)
                _ = "a%"
            }
            """,
        ))
        val text = myFixture.file.text
        val confidence = GoCompletionConfidence()
        fun at(offset: Int) = confidence.shouldSkipAutopopup(myFixture.editor, myFixture.file.findElementAt(offset - 1)!!, myFixture.file, offset)
        assertEquals(ThreeState.NO, at(text.indexOf("%\", n") + 1))
        assertEquals(ThreeState.YES, at(text.indexOf("a%") + 2))
    }
}
