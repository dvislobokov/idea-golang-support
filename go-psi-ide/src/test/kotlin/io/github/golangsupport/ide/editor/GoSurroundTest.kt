package io.github.golangsupport.ide.editor

import com.intellij.codeInsight.generation.surroundWith.SurroundWithHandler
import com.intellij.lang.LanguageSurrounders
import com.intellij.lang.surroundWith.SurroundDescriptor
import com.intellij.lang.surroundWith.Surrounder
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.lang.GoLanguage

/** Surround With over the PSI: statements grow to whole statements, expressions are the exact selection. */
class GoSurroundTest : GoSemanticIdeTestBase() {

    private fun descriptors(): List<SurroundDescriptor> = LanguageSurrounders.INSTANCE.allForLanguage(GoLanguage)

    /** The surrounders offered for the selection of the configured file, by title. */
    private fun offered(): Map<String, Surrounder> {
        val editor = myFixture.editor
        val selection = editor.selectionModel
        val result = LinkedHashMap<String, Surrounder>()
        for (descriptor in descriptors()) {
            val elements = descriptor.getElementsToSurround(myFixture.file, selection.selectionStart, selection.selectionEnd)
            if (elements.isEmpty()) continue
            for (surrounder in descriptor.surrounders) if (surrounder.isApplicable(elements)) result[surrounder.templateDescription] = surrounder
        }
        return result
    }

    private fun doTest(before: String, title: String, after: String) {
        myFixture.configureByText("a.go", go(before))
        val offered = offered()
        val surrounder = offered[title] ?: error("$title not in ${offered.keys}")
        SurroundWithHandler.invoke(project, myFixture.editor, myFixture.file, surrounder)
        myFixture.checkResult(go(after))
    }

    private fun assertNotOffered(text: String, title: String) {
        myFixture.configureByText("a.go", go(text))
        assertFalse("$title offered", title in offered().keys)
    }

    fun testRegistered() {
        val classes = descriptors().map { it.javaClass }
        assertTrue(classes.any { it == GoStatementSurroundDescriptor::class.java })
        assertTrue(classes.any { it == GoExpressionSurroundDescriptor::class.java })
    }

    fun testIfAroundStatementsGrownToWholeLines() = doTest(
        """
        package p

        func f() int {
            x := <selection>1
            y :=</selection> 2
            return x + y
        }
        """,
        "if",
        """
        package p

        func f() int {
            if <caret> {
                x := 1
                y := 2
            }
            return x + y
        }
        """,
    )

    fun testIfElse() = doTest(
        """
        package p

        func f() {
            <selection>g()</selection>
        }

        func g() {}
        """,
        "if / else",
        """
        package p

        func f() {
            if <caret> {
                g()
            } else {
            }
        }

        func g() {}
        """,
    )

    fun testForWithTrailingCommentAndBlankLine() = doTest(
        """
        package p

        func f() {
            <selection>g() // first

            g()</selection>
        }

        func g() {}
        """,
        "for",
        """
        package p

        func f() {
            for <caret> {
                g() // first

                g()
            }
        }

        func g() {}
        """,
    )

    fun testFunctionLiterals() {
        val before = """
            package p

            func f() {
                <selection>g()</selection>
            }

            func g() {}
            """
        for ((title, open) in listOf("func() { ... }()" to "func() {", "go func() { ... }()" to "go func() {", "defer func() { ... }()" to "defer func() {")) {
            doTest(before, title, """
                package p

                func f() {
                    $open
                        g()
                    }()<caret>
                }

                func g() {}
                """)
        }
    }

    fun testBlockKeepsRawStringLines() = doTest(
        """
        package p

        func f() string {
            <selection>s := `a
        b`</selection>
            return s
        }
        """,
        "{ ... }",
        """
        package p

        func f() string {
            {
                s := `a
        b`
            }<caret>
            return s
        }
        """,
    )

    fun testInsideCaseBody() = doTest(
        """
        package p

        func f(x int) {
            switch x {
            case 1:
                <selection>g()</selection>
            }
        }

        func g() {}
        """,
        "if",
        """
        package p

        func f(x int) {
            switch x {
            case 1:
                if <caret> {
                    g()
                }
            }
        }

        func g() {}
        """,
    )

    fun testPieceOfLineIsNotAStatement() {
        assertNotOffered(
            """
            package p

            func f(a, b int) int {
                return <selection>a + b</selection>
            }
            """,
            "if",
        )
        assertTrue("(expr)" in offered().keys)
    }

    fun testParentheses() = doTest(
        """
        package p

        func f(a, b int) bool {
            return <selection>a + b</selection> > 2
        }
        """,
        "(expr)",
        """
        package p

        func f(a, b int) bool {
            return (a + b)<caret> > 2
        }
        """,
    )

    fun testNegation() = doTest(
        """
        package p

        func f(a, b int) bool {
            return <selection>a < b</selection>
        }
        """,
        "!(expr)",
        """
        package p

        func f(a, b int) bool {
            return !(a < b)<caret>
        }
        """,
    )

    fun testNegationOnlyForBooleans() = assertNotOffered(
        """
        package p

        func f(a, b int) int {
            return <selection>a + b</selection>
        }
        """,
        "!(expr)",
    )

    fun testIfErrForCallStandingAlone() = doTest(
        """
        package p

        func load() (int, error) { return 0, nil }

        func f() (string, error) {
            <selection>load()</selection>
            return "", nil
        }
        """,
        "if err != nil { ... }",
        """
        package p

        func load() (int, error) { return 0, nil }

        func f() (string, error) {
            v, err := load()
            if err != nil {
                return "", err
            }<caret>
            return "", nil
        }
        """,
    )

    fun testIfErrForErrorOnly() = doTest(
        """
        package p

        func save() error { return nil }

        func f() {
            <selection>save()</selection>
        }
        """,
        "if err != nil { ... }",
        """
        package p

        func save() error { return nil }

        func f() {
            if err := save(); err != nil {
                return
            }<caret>
        }
        """,
    )

    fun testIfErrForNestedCall() = doTest(
        """
        package p

        type T struct{ A int }

        func load() (int, error) { return 0, nil }

        func use(int) {}

        func f() (T, *T, error) {
            v := 1
            use(<selection>load()</selection>)
            return T{}, nil, nil
        }
        """,
        "if err != nil { ... }",
        """
        package p

        type T struct{ A int }

        func load() (int, error) { return 0, nil }

        func use(int) {}

        func f() (T, *T, error) {
            v := 1
            v1, err := load()
            if err != nil {
                return T{}, nil, err
            }<caret>
            use(v1)
            return T{}, nil, nil
        }
        """,
    )

    fun testIfErrNotForCallsWithoutError() = assertNotOffered(
        """
        package p

        func n() int { return 0 }

        func f() {
            <selection>n()</selection>
        }
        """,
        "if err != nil { ... }",
    )

    fun testForRangeBySliceMapChannelAndInt() {
        val cases = listOf("[]string" to "_, v", "map[string]int" to "k, v", "chan int" to "v", "int" to "i", "string" to "_, r")
        for ((type, variables) in cases) doTest(
            """
            package p

            func f(xs $type) {
                <selection>xs</selection>
            }
            """,
            "for range",
            """
            package p

            func f(xs $type) {
                for $variables := range xs {
                    <caret>
                }
            }
            """,
        )
    }

    fun testForRangeNotForStructs() = assertNotOffered(
        """
        package p

        type T struct{}

        func f(t T) {
            <selection>t</selection>
        }
        """,
        "for range",
    )
}
