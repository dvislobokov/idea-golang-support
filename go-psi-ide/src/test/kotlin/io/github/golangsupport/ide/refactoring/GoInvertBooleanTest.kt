package io.github.golangsupport.ide.refactoring

import com.intellij.psi.PsiNamedElement
import com.intellij.refactoring.invertBoolean.InvertBooleanDelegate
import com.intellij.refactoring.invertBoolean.InvertBooleanProcessor
import com.intellij.refactoring.util.CommonRefactoringUtil
import io.github.golangsupport.ide.GoSemanticIdeTestBase

/** Invert Boolean through the platform's processor and the Go delegate: `<caret>` on the declaration or a use (4-space indents, tabs in the file). */
class GoInvertBooleanTest : GoSemanticIdeTestBase() {

    private fun go(text: String): String = text.trimIndent().replace("    ", "\t") + "\n"

    private fun doTest(before: String, after: String, name: String, others: Map<String, Pair<String, String>> = emptyMap()) {
        for ((file, content) in others) myFixture.addFileToProject(file, go(content.first))
        myFixture.configureByText("ib.go", go(before))
        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val delegate = InvertBooleanDelegate.findInvertBooleanDelegate(element)
        assertTrue(delegate is GoInvertBooleanDelegate)
        val target = delegate!!.adjustElement(element, project, myFixture.editor)!!
        InvertBooleanProcessor(target as PsiNamedElement, name).run()
        assertEquals(go(after), myFixture.editor.document.text)
        for ((file, content) in others) myFixture.checkResult(file, go(content.second), true)
    }

    private fun refused(text: String): String {
        myFixture.configureByText("iq.go", go(text))
        try {
            GoInvertBooleanDelegate().adjustElement(myFixture.file.findElementAt(myFixture.caretOffset)!!, project, myFixture.editor)
        } catch (e: CommonRefactoringUtil.RefactoringErrorHintException) {
            return e.message!!
        }
        fail("Expected the refactoring to be refused")
        return ""
    }

    fun testLocalVariable() = doTest(
        """
        package ib

        func ibF() int {
            <caret>ready := true
            if ready {
                return 1
            }
            if !ready {
                return 2
            }
            ready = 1 > 2
            return 0
        }
        """,
        """
        package ib

        func ibF() int {
            notReady := false
            if !notReady {
                return 1
            }
            if notReady {
                return 2
            }
            notReady = 1 <= 2
            return 0
        }
        """,
        "notReady",
    )

    fun testFunctionReturnsAndCalls() = doTest(
        """
        package ib

        func <caret>ibEmpty(s string) bool {
            if s == "" {
                return true
            }
            return len(s) == 0 || s == " "
        }

        func ibUse(s string) bool { return ibEmpty(s) && s != "x" }
        """,
        """
        package ib

        func ibFilled(s string) bool {
            if s == "" {
                return false
            }
            return len(s) != 0 && s != " "
        }

        func ibUse(s string) bool { return !ibFilled(s) && s != "x" }
        """,
        "ibFilled",
        others = mapOf("ib2.go" to ("package ib\n\nvar ibE = !ibEmpty(\"\")" to "package ib\n\nvar ibE = ibFilled(\"\")")),
    )

    fun testParameterAndArguments() = doTest(
        """
        package ib

        func ibRun(<caret>verbose bool) int {
            if verbose {
                return 1
            }
            return 0
        }

        var ibR = ibRun(true) + ibRun(1 < 2)
        """,
        """
        package ib

        func ibRun(quiet bool) int {
            if !quiet {
                return 1
            }
            return 0
        }

        var ibR = ibRun(false) + ibRun(1 >= 2)
        """,
        "quiet",
    )

    fun testFieldKeyedLiteralAndComparison() = doTest(
        """
        package ib

        type ibOpt struct {
            <caret>on bool
        }

        func ibOpts() bool {
            o := ibOpt{on: true}
            o.on = false
            return o.on == true
        }
        """,
        """
        package ib

        type ibOpt struct {
            off bool
        }

        func ibOpts() bool {
            o := ibOpt{off: false}
            o.off = true
            return o.off == false
        }
        """,
        "off",
    )

    fun testVarWithoutValueGetsTrue() = doTest(
        """
        package ib

        var <caret>ibDebug bool

        func ibLog() bool { return ibDebug }
        """,
        """
        package ib

        var ibNoDebug bool = true

        func ibLog() bool { return !ibNoDebug }
        """,
        "ibNoDebug",
    )

    fun testFromUseInAnotherExpression() = doTest(
        """
        package ib

        func ibCheck(a, b int) bool {
            same := a == b
            return !<caret>same || a > 0
        }
        """,
        """
        package ib

        func ibCheck(a, b int) bool {
            differ := a != b
            return differ || a > 0
        }
        """,
        "differ",
    )

    fun testNotBooleanIsRefused() {
        val message = refused("package iq\n\nvar <caret>iqN = 3")
        assertTrue(message, message.contains("of type bool"))
    }

    fun testFunctionNotReturningBoolIsRefused() {
        val message = refused("package iq\n\nfunc <caret>iqF() int { return 1 }")
        assertTrue(message, message.contains("returning one bool"))
    }

    fun testInvertedNames() {
        assertEquals("isDisabled", GoInvertBoolean.invertedName("isEnabled"))
        assertEquals("notOk", GoInvertBoolean.invertedName("ok"))
        assertEquals("lacksItems", GoInvertBoolean.invertedName("hasItems"))
        assertEquals("hasItems", GoInvertBoolean.invertedName("lacksItems"))
        assertEquals("done", GoInvertBoolean.invertedName("notDone"))
        assertEquals("NotDone", GoInvertBoolean.invertedName("Done"))
        assertEquals("isNotReady", GoInvertBoolean.invertedName("isReady"))
        assertEquals("visible", GoInvertBoolean.invertedName("hidden"))
    }
}
