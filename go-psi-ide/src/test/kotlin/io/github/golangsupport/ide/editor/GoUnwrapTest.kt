package io.github.golangsupport.ide.editor

import com.intellij.codeInsight.unwrap.UnwrapDescriptor
import com.intellij.codeInsight.unwrap.LanguageUnwrappers
import com.intellij.openapi.command.WriteCommandAction
import io.github.golangsupport.ide.GoIdeTestBase
import io.github.golangsupport.lang.GoLanguage

/** Unwrap / Remove over the PSI: the option with [option] as its description, at the caret. */
class GoUnwrapTest : GoIdeTestBase() {

    private fun descriptor(): UnwrapDescriptor = LanguageUnwrappers.INSTANCE.forLanguage(GoLanguage)

    private fun options(): List<String> = descriptor().collectUnwrappers(project, myFixture.editor, myFixture.file).map { it.second.getDescription(it.first) }

    private fun doTest(before: String, option: String, after: String) {
        myFixture.configureByText("a.go", go(before))
        val options = descriptor().collectUnwrappers(project, myFixture.editor, myFixture.file)
        val pair = options.firstOrNull { it.second.getDescription(it.first) == option } ?: error("$option not in ${options()}")
        val element = pair.first
        val unwrapper = pair.second
        WriteCommandAction.runWriteCommandAction(project) { unwrapper.unwrap(myFixture.editor, element) }
        myFixture.checkResult(go(after), true)
    }

    fun testRegistered() = assertInstanceOf(descriptor(), GoUnwrapDescriptor::class.java)

    fun testUnwrapIfKeepsInitAndDropsElse() = doTest(
        """
        package p

        func f() int {
            if x := g(); x > 0 {
                <caret>a := x
                return a
            } else {
                return 0
            }
        }

        func g() int { return 1 }
        """,
        "Unwrap 'if...'",
        """
        package p

        func f() int {
            x := g()
            a := x
            return a
        }

        func g() int { return 1 }
        """,
    )

    fun testUnwrapElse() = doTest(
        """
        package p

        func f(a bool) {
            if a {
                g(1)
            } else if !a {
                g(2)
            } else {
                <caret>g(3)
            }
        }

        func g(int) {}
        """,
        "Unwrap 'else...'",
        """
        package p

        func f(a bool) {
            g(3)
        }

        func g(int) {}
        """,
    )

    fun testRemoveElse() = doTest(
        """
        package p

        func f(a bool) {
            if a {
                g(1)
            } else {
                <caret>g(2)
            }
        }

        func g(int) {}
        """,
        "Remove 'else...'",
        """
        package p

        func f(a bool) {
            if a {
                g(1)
            }
        }

        func g(int) {}
        """,
    )

    fun testUnwrapForKeepsRawStringAndBlankLine() = doTest(
        """
        package p

        func f(xs []string) {
            for _, x := range xs {
                <caret>s := `a
            b`

                g(x + s)
            }
        }

        func g(string) {}
        """,
        "Unwrap 'for...'",
        """
        package p

        func f(xs []string) {
            s := `a
            b`

            g(x + s)
        }

        func g(string) {}
        """,
    )

    fun testUnwrapThreeClauseForKeepsInit() = doTest(
        """
        package p

        func f() {
            for i := 0; i < 3; i++ {
                <caret>g(i)
            }
        }

        func g(int) {}
        """,
        "Unwrap 'for...'",
        """
        package p

        func f() {
            i := 0
            g(i)
        }

        func g(int) {}
        """,
    )

    fun testUnwrapFunctionLiteral() = doTest(
        """
        package p

        func f() {
            defer func() {
                <caret>g()
                g()
            }()
        }

        func g() {}
        """,
        "Unwrap 'func() {...}()'",
        """
        package p

        func f() {
            g()
            g()
        }

        func g() {}
        """,
    )

    fun testRemoveDeferAndGo() {
        doTest(
            """
            package p

            func f() {
                <caret>defer g()
            }

            func g() {}
            """,
            "Remove 'defer'",
            """
            package p

            func f() {
                g()
            }

            func g() {}
            """,
        )
        doTest(
            """
            package p

            func f() {
                go <caret>g()
            }

            func g() {}
            """,
            "Remove 'go'",
            """
            package p

            func f() {
                g()
            }

            func g() {}
            """,
        )
    }

    fun testUnwrapSwitchCase() = doTest(
        """
        package p

        func f(x int) {
            switch x {
            case 1:
                <caret>g(1)
                g(2)
            default:
                g(3)
            }
        }

        func g(int) {}
        """,
        "Unwrap 'case...'",
        """
        package p

        func f(x int) {
            g(1)
            g(2)
        }

        func g(int) {}
        """,
    )

    fun testUnwrapSelectCase() = doTest(
        """
        package p

        func f(c chan int) {
            select {
            case v := <-c:
                <caret>g(v)
            }
        }

        func g(int) {}
        """,
        "Unwrap 'case...'",
        """
        package p

        func f(c chan int) {
            g(v)
        }

        func g(int) {}
        """,
    )

    fun testUnwrapBraces() = doTest(
        """
        package p

        func f() {
            {
                <caret>g()
            }
            g()
        }

        func g() {}
        """,
        "Unwrap braces",
        """
        package p

        func f() {
            g()
            g()
        }

        func g() {}
        """,
    )

    fun testUnwrapEmptyIfRemovesItsLines() = doTest(
        """
        package p

        func f(a bool) {
            g()
            if a {<caret>
            }
            g()
        }

        func g() {}
        """,
        "Unwrap 'if...'",
        """
        package p

        func f(a bool) {
            g()
            g()
        }

        func g() {}
        """,
    )

    fun testOptionsAtNestedCaret() {
        myFixture.configureByText("a.go", go(
            """
            package p

            func f(a bool) {
                for {
                    if a {
                        <caret>g()
                    }
                }
            }

            func g() {}
            """,
        ))
        assertEquals(listOf("Unwrap 'if...'", "Unwrap 'for...'"), options())
    }
}
