package io.github.golangsupport.ide.editor

import com.intellij.openapi.actionSystem.IdeActions
import io.github.golangsupport.ide.GoIdeTestBase

/** Move Statement Up/Down by whole Go elements, stopping at the edge of their list. */
class GoStatementMoverTest : GoIdeTestBase() {

    private fun doTest(before: String, down: Boolean, after: String) {
        myFixture.configureByText("a.go", go(before))
        myFixture.performEditorAction(if (down) IdeActions.ACTION_MOVE_STATEMENT_DOWN_ACTION else IdeActions.ACTION_MOVE_STATEMENT_UP_ACTION)
        myFixture.checkResult(go(after))
    }

    fun testStatementDownOverMultiLineStatement() = doTest(
        """
        package p

        func f() {
            <caret>a := 1
            b := []int{
                1,
                2,
            }
            _, _ = a, b
        }
        """,
        true,
        """
        package p

        func f() {
            b := []int{
                1,
                2,
            }
            <caret>a := 1
            _, _ = a, b
        }
        """,
    )

    fun testMultiLineStatementUpFromInnerLineWithComment() = doTest(
        """
        package p

        func f() {
            a := 1
            // the values
            b := []int{
                <caret>1,
            }
            _, _ = a, b
        }
        """,
        false,
        """
        package p

        func f() {
            // the values
            b := []int{
                <caret>1,
            }
            a := 1
            _, _ = a, b
        }
        """,
    )

    fun testStatementStopsAtBlockEdge() = doTest(
        """
        package p

        func f(x bool) {
            if x {
                <caret>g()
            }
        }

        func g() {}
        """,
        false,
        """
        package p

        func f(x bool) {
            if x {
                <caret>g()
            }
        }

        func g() {}
        """,
    )

    fun testCaseClauses() = doTest(
        """
        package p

        func f(x int) {
            switch x {
            <caret>case 1:
                g()
            case 2:
                g()
                g()
            }
        }

        func g() {}
        """,
        true,
        """
        package p

        func f(x int) {
            switch x {
            case 2:
                g()
                g()
            <caret>case 1:
                g()
            }
        }

        func g() {}
        """,
    )

    fun testStructFieldWithDocComment() = doTest(
        """
        package p

        type T struct {
            A int
            // B is documented
            <caret>B string
        }
        """,
        false,
        """
        package p

        type T struct {
            // B is documented
            <caret>B string
            A int
        }
        """,
    )

    fun testInterfaceMethod() = doTest(
        """
        package p

        type I interface {
            <caret>M()
            N() int
        }
        """,
        true,
        """
        package p

        type I interface {
            N() int
            <caret>M()
        }
        """,
    )

    fun testTopLevelDeclarationsKeepBlankLines() = doTest(
        """
        package p

        // A does a.
        func A() {
            g()
        }

        // B does b.
        func <caret>B() {}

        var x = 1
        """,
        false,
        """
        package p

        // B does b.
        func <caret>B() {}

        // A does a.
        func A() {
            g()
        }

        var x = 1
        """,
    )

    fun testTopLevelDeclarationDownAcrossBlankLine() = doTest(
        """
        package p

        <caret>type T int

        const C = 1
        """,
        true,
        """
        package p

        const C = 1

        <caret>type T int
        """,
    )

    fun testSpecInGroup() = doTest(
        """
        package p

        const (
            A = 1
            <caret>B = 2
        )
        """,
        false,
        """
        package p

        const (
            <caret>B = 2
            A = 1
        )
        """,
    )

    fun testFirstDeclarationStops() = doTest(
        """
        package p

        <caret>var x = 1
        """,
        false,
        """
        package p

        <caret>var x = 1
        """,
    )
}
