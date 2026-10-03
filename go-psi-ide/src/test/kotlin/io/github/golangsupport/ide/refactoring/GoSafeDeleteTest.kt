package io.github.golangsupport.ide.refactoring

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.safeDelete.SafeDeleteHandler
import io.github.golangsupport.ide.GoSemanticIdeTestBase

/** Safe Delete of the declaration at `<caret>`: the text after, or the conflicts it reports. */
class GoSafeDeleteTest : GoSemanticIdeTestBase() {

    private fun go(text: String): String = text.trimIndent().replace("    ", "\t") + "\n"

    private fun delete(name: String, before: String) {
        myFixture.configureByText(name, go(before))
        val element = TargetElementUtil.findTargetElement(myFixture.editor, TargetElementUtil.ELEMENT_NAME_ACCEPTED or TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED)!!
        SafeDeleteHandler.invoke(project, arrayOf(element), true)
    }

    private fun doTest(before: String, after: String) {
        delete("sd.go", before)
        assertEquals(go(after), myFixture.editor.document.text)
    }

    private fun conflicts(before: String): String {
        try {
            delete("sc.go", before)
        } catch (e: BaseRefactoringProcessor.ConflictsInTestsException) {
            return e.messages.joinToString("\n")
        }
        fail("Expected conflicts")
        return ""
    }

    fun testUnusedFunctionWithDocComment() = doTest(
        """
        package sd

        // helper does nothing.
        // It is never called.
        func hel<caret>per() int {
            return 1
        }

        func main() {}
        """,
        """
        package sd

        func main() {}
        """,
    )

    fun testLastFunctionLeavesNoBlankLine() = doTest(
        """
        package sd

        func main() {}

        func tr<caret>ailing() {}
        """,
        """
        package sd

        func main() {}
        """,
    )

    fun testUsedFunctionIsConflict() {
        val message = conflicts(
            """
            package sd

            func us<caret>ed() int { return 1 }

            func main() { _ = used() }
            """,
        )
        assertTrue(message, message.contains("used"))
    }

    fun testUnusedField() = doTest(
        """
        package sd

        type Point struct {
            X int
            // Y is the second coordinate.
            <caret>Y int // unused
            Z int
        }
        """,
        """
        package sd

        type Point struct {
            X int
            Z int
        }
        """,
    )

    fun testFieldOfSharedDeclaration() = doTest(
        """
        package sd

        type Size struct {
            W, <caret>H int
        }
        """,
        """
        package sd

        type Size struct {
            W int
        }
        """,
    )

    fun testUsedFieldIsConflict() {
        val message = conflicts(
            """
            package sd

            type Pair struct {
                <caret>A int
                B int
            }

            func sum(p Pair) int { return p.A + p.B }
            """,
        )
        assertTrue(message, message.isNotEmpty())
    }

    fun testFieldInUnkeyedLiteralIsConflict() {
        val message = conflicts(
            """
            package sd

            type Duo struct {
                L int
                <caret>R int
            }

            var d = Duo{1, 2}
            """,
        )
        assertTrue(message, message.contains("unkeyed literal of Duo"))
    }

    fun testUnusedMethod() = doTest(
        """
        package sd

        type Box struct{}

        // Open opens the box.
        func (b Box) Op<caret>en() {}

        func (b Box) Close() {}
        """,
        """
        package sd

        type Box struct{}

        func (b Box) Close() {}
        """,
    )

    fun testMethodCalledThroughInterfaceIsConflict() {
        val message = conflicts(
            """
            package sd

            type Runner interface {
                Run()
            }

            type Job struct{}

            func (j Job) R<caret>un() {}

            func start(r Runner) { r.Run() }

            var _ Runner = Job{}
            """,
        )
        assertTrue(message, message.contains("Method Job.Run implements Runner.Run, which is called through the interface here"))
    }

    fun testOneOfSeveralVariables() = doTest(
        """
        package sd

        var <caret>a, b = 1, 2
        """,
        """
        package sd

        var b = 2
        """,
    )

    fun testTypeInGroup() = doTest(
        """
        package sd

        type (
            Keep int
            // Drop is not used.
            Dr<caret>op string
        )
        """,
        """
        package sd

        type (
            Keep int
        )
        """,
    )

    fun testConstantRepeatedByNextIsConflict() {
        val message = conflicts(
            """
            package sd

            const (
                <caret>A = iota
                B
            )
            """,
        )
        assertTrue(message, message.contains("repeat its expression"))
    }

    fun testUnusedConstant() = doTest(
        """
        package sd

        const lim<caret>it = 10

        func main() {}
        """,
        """
        package sd

        func main() {}
        """,
    )
}
