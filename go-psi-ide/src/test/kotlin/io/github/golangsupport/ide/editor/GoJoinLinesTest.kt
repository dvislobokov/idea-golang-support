package io.github.golangsupport.ide.editor

import com.intellij.openapi.actionSystem.IdeActions
import io.github.golangsupport.ide.GoSemanticIdeTestBase

/** Join Lines rules for Go; everything else is the platform's. */
class GoJoinLinesTest : GoSemanticIdeTestBase() {

    private fun doTest(before: String, after: String) {
        myFixture.configureByText("a.go", go(before))
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_JOIN_LINES)
        myFixture.checkResult(go(after))
    }

    fun testDeclarationAndAssignmentBecomeShortDeclaration() = doTest(
        """
        package p

        func f() string {
            <caret>var s string
            s = "x"
            return s
        }
        """,
        """
        package p

        func f() string {
            s := <caret>"x"
            return s
        }
        """,
    )

    fun testDeclarationKeepsTypeWhenTheValueWouldChangeIt() = doTest(
        """
        package p

        func f() int64 {
            <caret>var n int64
            n = 5
            return n
        }
        """,
        """
        package p

        func f() int64 {
            var n int64 = <caret>5
            return n
        }
        """,
    )

    /** Falls back to the platform: the text stays a declaration and an assignment on one line. */
    private fun assertDefaultJoin(before: String, notExpected: String) {
        myFixture.configureByText("a.go", go(before))
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_JOIN_LINES)
        val text = myFixture.editor.document.text
        assertFalse(text, text.contains(notExpected))
        assertEquals(text, go(before).replace("<caret>", "").lines().size - 1, text.lines().size)
    }

    fun testAssignmentToAnotherVariableIsNotJoined() = assertDefaultJoin(
        """
        package p

        func f() {
            var a, b int
            <caret>var s string
            a = 1
            _, _, _ = a, b, s
        }
        """,
        ":=",
    )

    fun testStringConcatenation() = doTest(
        """
        package p

        var s = <caret>"hello, " +
            "world" + "!"
        """,
        """
        package p

        var s = "hello, <caret>world" + "!"
        """,
    )

    fun testConcatenationAfterAnotherOperand() = doTest(
        """
        package p

        func f(x string) string {
            return <caret>x + "a" +
                "b"
        }
        """,
        """
        package p

        func f(x string) string {
            return x + "a<caret>b"
        }
        """,
    )

    fun testRawAndInterpretedAreNotMerged() = assertDefaultJoin(
        """
        package p

        var s = <caret>`a` +
            "b"
        """,
        "ab",
    )

    fun testCallArguments() {
        myFixture.configureByText("a.go", go(
            """
            package p

            func g(a, b int) {}

            func f() {
                <selection>g(
                    1,
                    2,
                )</selection>
            }
            """,
        ))
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_JOIN_LINES)
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("\tg(1, 2)\n"))
    }

    fun testCompositeLiteral() {
        myFixture.configureByText("a.go", go(
            """
            package p

            type T struct{ A, B int }

            var t = <selection>T{
                A: 1,
                B: 2,
            }</selection>
            """,
        ))
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_JOIN_LINES)
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("var t = T{A: 1, B: 2}\n"))
    }
}
