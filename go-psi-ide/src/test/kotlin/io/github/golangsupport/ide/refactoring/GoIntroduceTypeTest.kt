package io.github.golangsupport.ide.refactoring

import com.intellij.refactoring.util.CommonRefactoringUtil
import io.github.golangsupport.ide.GoSemanticIdeTestBase

/** Introduce Type: `<caret>` or `<selection>` before, the text after (Go written with 4-space indents, tabs in the file). */
class GoIntroduceTypeTest : GoSemanticIdeTestBase() {

    private fun go(text: String): String = text.trimIndent().replace("    ", "\t") + "\n"

    private fun doTest(before: String, after: String, options: GoIntroduceOptions? = null) {
        myFixture.configureByText("it.go", go(before))
        GoIntroduceTypeHandler(options).invoke(project, myFixture.editor, myFixture.file, null)
        myFixture.checkResult(go(after))
    }

    private fun unavailable(text: String) {
        myFixture.configureByText("iu.go", go(text))
        try {
            GoIntroduceTypeHandler().invoke(project, myFixture.editor, myFixture.file, null)
            fail("Expected the refactoring to be refused")
        } catch (e: CommonRefactoringUtil.RefactoringErrorHintException) {
            assertTrue(e.message, e.message!!.startsWith("Cannot perform refactoring"))
        }
    }

    fun testCompositeLiteralStructNamedAfterTheVariable() = doTest(
        """
        package it

        // run runs.
        func run() {
            cfg := <caret>struct {
                Host string
                Port int
            }{"localhost", 80}
            _ = cfg
        }
        """,
        """
        package it

        type Cfg struct {
            Host string
            Port int
        }

        // run runs.
        func run() {
            cfg := Cfg{"localhost", 80}
            _ = cfg
        }
        """,
    )

    fun testFuncTypeOfAParameterReplacesEveryOccurrence() = doTest(
        """
        package it

        func Handle(path string, handler <caret>func(string) error) {}

        func Wrap(h func(string) error) func(string) error { return h }
        """,
        """
        package it

        type Handler func(string) error

        func Handle(path string, handler Handler) {}

        func Wrap(h Handler) Handler { return h }
        """,
    )

    fun testOnlyTheChosenOccurrenceWithAGivenName() = doTest(
        """
        package it

        var index <selection>map[string][]int</selection>

        var other map[string][]int
        """,
        """
        package it

        type Positions map[string][]int

        var index Positions

        var other map[string][]int
        """,
        GoIntroduceOptions(replaceAll = false, name = "Positions"),
    )

    fun testSliceOfAFieldInsideAStructDeclaration() = doTest(
        """
        package it

        type Server struct {
            routes <caret>[]string
        }
        """,
        """
        package it

        type Routes []string

        type Server struct {
            routes Routes
        }
        """,
    )

    fun testNameAvoidsTheNamesOfThePackage() = doTest(
        """
        package it

        type Cfg int

        func run() {
            cfg := <caret>struct{ A int }{1}
            _ = cfg
        }
        """,
        """
        package it

        type Cfg int

        type Cfg1 struct{ A int }

        func run() {
            cfg := Cfg1{1}
            _ = cfg
        }
        """,
    )

    fun testNotOnANamedType() = unavailable(
        """
        package it

        type Item struct{}

        func get() <caret>Item { return Item{} }
        """,
    )

    fun testNotOnATypeUsingATypeParameter() = unavailable(
        """
        package it

        func Keys[K comparable, V any](m map[K]V) <caret>[]K { return nil }
        """,
    )

    fun testNotOnTheTypeOfATypeSpec() = unavailable(
        """
        package it

        type Point <caret>struct{ X, Y int }
        """,
    )

    fun testReplaceAllKeepsTypeAssertions() = doTest(
        """
        package it

        var names <caret>map[string]int

        func get(v any) {
            m, ok := v.(map[string]int)
            _, _ = m, ok
        }
        """,
        """
        package it

        type Names map[string]int

        var names Names

        func get(v any) {
            m, ok := v.(map[string]int)
            _, _ = m, ok
        }
        """,
    )

    fun testReplaceAllKeepsTypeSwitchCases() = doTest(
        """
        package it

        var names <caret>[]string

        func kind(v any) int {
            switch v.(type) {
            case []string:
                return 1
            }
            return 0
        }
        """,
        """
        package it

        type Names []string

        var names Names

        func kind(v any) int {
            switch v.(type) {
            case []string:
                return 1
            }
            return 0
        }
        """,
    )

    fun testReplaceAllKeepsMethodSignatures() = doTest(
        """
        package it

        type W struct{}

        func (W) Write(p []byte) (int, error) { return len(p), nil }

        var buf <caret>[]byte
        """,
        """
        package it

        type W struct{}

        func (W) Write(p []byte) (int, error) { return len(p), nil }

        type Buf []byte

        var buf Buf
        """,
    )
}
