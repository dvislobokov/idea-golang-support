package io.github.golangsupport.ide.refactoring

import com.intellij.lang.LanguageRefactoringSupport
import com.intellij.refactoring.util.CommonRefactoringUtil
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.lang.GoLanguage

/** Introduce Parameter: `<selection>` in a function body, the new signature and the calls after (Go written with 4-space indents, tabs in the file). */
class GoIntroduceParameterTest : GoSemanticIdeTestBase() {

    private fun go(text: String): String = text.trimIndent().replace("    ", "\t") + "\n"

    private fun doTest(before: String, after: String, replaceAll: Boolean = false, name: String? = null, others: Map<String, Pair<String, String>> = emptyMap()) {
        for ((file, content) in others) myFixture.addFileToProject(file, go(content.first))
        myFixture.configureByText("ip.go", go(before))
        GoIntroduceParameterHandler(GoIntroduceOptions(replaceAll, name)).invoke(project, myFixture.editor, myFixture.file, null)
        assertEquals(go(after), myFixture.editor.document.text)
        for ((file, content) in others) myFixture.checkResult(file, go(content.second), true)
    }

    private fun refused(text: String): String {
        myFixture.configureByText("iq.go", go(text))
        try {
            GoIntroduceParameterHandler(GoIntroduceOptions()).invoke(project, myFixture.editor, myFixture.file, null)
        } catch (e: CommonRefactoringUtil.RefactoringErrorHintException) {
            return e.message!!
        }
        fail("Expected the refactoring to be refused")
        return ""
    }

    fun testLiteralBecomesParameterAndArgument() = doTest(
        """
        package ip

        func ipGreet(name string) string {
            return name + <selection>"!"</selection>
        }

        func ipUse() string { return ipGreet("a") + ipGreet("b") }
        """,
        """
        package ip

        func ipGreet(name string, s string) string {
            return name + s
        }

        func ipUse() string { return ipGreet("a", "!") + ipGreet("b", "!") }
        """,
    )

    fun testReplaceAllOccurrencesWithName() = doTest(
        """
        package ip

        func ipScale(x int) int {
            return x*<selection>10</selection> + 10
        }

        var ipV = ipScale(2)
        """,
        """
        package ip

        func ipScale(x int, factor int) int {
            return x*factor + factor
        }

        var ipV = ipScale(2, 10)
        """,
        replaceAll = true, name = "factor",
    )

    fun testCallInAnotherFile() = doTest(
        """
        package ip

        const ipLimit = 5

        func ipCheck(n int) bool {
            return n < <selection>ipLimit * 2</selection>
        }
        """,
        """
        package ip

        const ipLimit = 5

        func ipCheck(n int, max int) bool {
            return n < max
        }
        """,
        name = "max",
        others = mapOf("ip2.go" to ("package ip\n\nfunc ipOther() bool { return ipCheck(1) }" to "package ip\n\nfunc ipOther() bool { return ipCheck(1, ipLimit * 2) }")),
    )

    fun testNewParameterGoesBeforeVariadic() = doTest(
        """
        package ip

        func ipJoin(sep string, parts ...string) string {
            return <selection>"["</selection> + sep
        }

        var ipJ = ipJoin(",", "a", "b")
        """,
        """
        package ip

        func ipJoin(sep string, s string, parts ...string) string {
            return s + sep
        }

        var ipJ = ipJoin(",", "[", "a", "b")
        """,
    )

    fun testMethodCalls() = doTest(
        """
        package ip

        type ipCounter struct{ n int }

        func (c ipCounter) next() int {
            return c.n + <selection>1</selection>
        }

        func ipUseCounter(c ipCounter) int { return c.next() }
        """,
        """
        package ip

        type ipCounter struct{ n int }

        func (c ipCounter) next(step int) int {
            return c.n + step
        }

        func ipUseCounter(c ipCounter) int { return c.next(1) }
        """,
        name = "step",
    )

    fun testExpressionUsingParameterIsRefused() {
        val message = refused(
            """
            package iq

            func iqDouble(x int) int {
                return <selection>x * 2</selection>
            }
            """,
        )
        assertTrue(message, message.contains("Expression depends on local variables"))
    }

    fun testExpressionUsingLocalIsRefused() {
        val message = refused(
            """
            package iq

            func iqLocal() int {
                y := 3
                return <selection>y + 1</selection>
            }
            """,
        )
        assertTrue(message, message.contains("Expression depends on local variables"))
    }

    fun testOutsideFunctionIsRefused() {
        val message = refused("package iq\n\nvar iqX = <selection>1 + 2</selection>")
        assertTrue(message, message.contains("inside the body of a function"))
    }

    fun testRegisteredInRefactoringSupport() {
        val provider = LanguageRefactoringSupport.getInstance().forLanguage(GoLanguage)!!
        assertTrue(provider.introduceParameterHandler is GoIntroduceParameterHandler)
        assertTrue(provider.introduceFieldHandler is GoIntroduceFieldHandler)
    }
}
