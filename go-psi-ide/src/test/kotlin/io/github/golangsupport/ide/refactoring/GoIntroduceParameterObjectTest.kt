package io.github.golangsupport.ide.refactoring

import com.intellij.refactoring.introduceParameterObject.IntroduceParameterObjectDelegate
import com.intellij.refactoring.util.CommonRefactoringUtil
import io.github.golangsupport.ide.GoSemanticIdeTestBase

/** Introduce Parameter Object: `<caret>` on a function, the struct, the function and the calls after (4-space indents, tabs in the file). */
class GoIntroduceParameterObjectTest : GoSemanticIdeTestBase() {

    private fun go(text: String): String = text.trimIndent().replace("    ", "\t") + "\n"

    private fun doTest(before: String, after: String, options: GoParameterObjectOptions = GoParameterObjectOptions(), others: Map<String, Pair<String, String>> = emptyMap()) {
        for ((file, content) in others) myFixture.addFileToProject(file, go(content.first))
        myFixture.configureByText("po.go", go(before))
        GoIntroduceParameterObjectHandler(options).invoke(project, myFixture.editor, myFixture.file, null)
        assertEquals(go(after).replace(ALIGN, "    "), myFixture.editor.document.text)
        for ((file, content) in others) myFixture.checkResult(file, go(content.second), true)
    }

    private fun refused(text: String, others: Map<String, String> = emptyMap()): String {
        for ((file, content) in others) myFixture.addFileToProject(file, go(content))
        myFixture.configureByText("pq.go", go(text))
        try {
            GoIntroduceParameterObjectHandler(GoParameterObjectOptions()).invoke(project, myFixture.editor, myFixture.file, null)
        } catch (e: CommonRefactoringUtil.RefactoringErrorHintException) {
            return e.message!!
        }
        fail("Expected the refactoring to be refused")
        return ""
    }

    fun testChosenParametersMoveIntoStruct() = doTest(
        """
        package po

        func <caret>poDraw(x int, y int, label string) string {
            return label + string(rune(x+y))
        }

        func poUse() string { return poDraw(1, 2, "a") }
        """,
        """
        package po

        type poDrawParams struct {
            x int
            y int
        }

        func poDraw(p poDrawParams, label string) string {
            return label + string(rune(p.x+p.y))
        }

        func poUse() string { return poDraw(poDrawParams{x: 1, y: 2}, "a") }
        """,
        GoParameterObjectOptions(included = listOf("x", "y")),
    )

    fun testExportedFunctionAllParametersAndRecursion() = doTest(
        """
        package po

        // PoSum adds.
        func PoSum(<caret>a, b int, name string) int {
            if a == 0 {
                return b
            }
            return PoSum(a-1, b+1, name)
        }
        """,
        """
        package po

        type PoTotal struct {
            A~int
            B~int
            Name string
        }

        // PoSum adds.
        func PoSum(p PoTotal) int {
            if p.A == 0 {
                return p.B
            }
            return PoSum(PoTotal{A: p.A-1, B: p.B+1, Name: p.Name})
        }
        """,
        GoParameterObjectOptions(structName = "PoTotal"),
        others = mapOf("po2.go" to ("package po\n\nvar poV = PoSum(1, 2, \"x\")" to "package po\n\nvar poV = PoSum(PoTotal{A: 1, B: 2, Name: \"x\"})")),
    )

    fun testVariadicStaysOut() = doTest(
        """
        package po

        func <caret>poLog(level int, prefix string, args ...any) int {
            return level + len(prefix) + len(args)
        }

        var poL = poLog(1, "p", 2, 3)
        """,
        """
        package po

        type poLogParams struct {
            level  int
            prefix string
        }

        func poLog(p poLogParams, args ...any) int {
            return p.level + len(p.prefix) + len(args)
        }

        var poL = poLog(poLogParams{level: 1, prefix: "p"}, 2, 3)
        """,
    )

    fun testOneParameterIsRefused() {
        val message = refused("package pq\n\nfunc <caret>pqOne(a int) int { return a }")
        assertTrue(message, message.contains("at least two"))
    }

    fun testFunctionValueIsRefused() {
        val message = refused("package pq\n\nfunc <caret>pqTwo(a, b int) int { return a + b }\n\nvar pqF = pqTwo")
        assertTrue(message, message.contains("used as a value"))
    }

    fun testDelegateRegistered() {
        myFixture.configureByText("pd.go", go("package pd\n\nfunc <caret>pdF(a, b int) {}"))
        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val delegate = IntroduceParameterObjectDelegate.findDelegate<com.intellij.psi.PsiNamedElement, com.intellij.refactoring.changeSignature.ParameterInfo, Nothing>(element)
        assertNotNull(delegate)
        assertTrue(delegate!!.getHandler(element) is GoIntroduceParameterObjectHandler)
    }

    private companion object {
        /** Spaces of a struct's field alignment: the 4-space indents of the fixtures become tabs. */
        const val ALIGN = "~"
    }
}
