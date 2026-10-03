package io.github.golangsupport.ide.refactoring

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.testFramework.replaceService
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.GoSemanticIdeTestBase

/** Inline Variable / Constant / Function: `<caret>` before, the text after (Go written with 4-space indents, tabs in the file). */
class GoInlineTest : GoSemanticIdeTestBase() {

    private fun go(text: String): String = text.trimIndent().replace("    ", "\t") + "\n"

    private fun target(): PsiElement =
        TargetElementUtil.findTargetElement(myFixture.editor, TargetElementUtil.ELEMENT_NAME_ACCEPTED or TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED)
            ?: error("no element at the caret")

    private fun inline(before: String, after: String) {
        myFixture.configureByText("n.go", go(before))
        GoInlineActionHandler().inlineElement(project, myFixture.editor, target())
        myFixture.checkResult(go(after))
    }

    private fun refused(text: String, reason: String) {
        myFixture.configureByText("nr.go", go(text))
        try {
            GoInlineActionHandler().inlineElement(project, myFixture.editor, target())
            fail("Expected the refactoring to be refused")
        } catch (e: CommonRefactoringUtil.RefactoringErrorHintException) {
            assertTrue(e.message, e.message!!.startsWith("Cannot perform refactoring"))
            assertTrue(e.message, e.message!!.contains(reason))
        }
    }

    // --- Inline Variable ---

    fun testVariableWithPrecedence() = inline(
        """
        package n

        func nvPrec(a, b int) int {
            <caret>x := a + b
            return x * 2
        }
        """,
        """
        package n

        func nvPrec(a, b int) int {
            return (a + b) * 2
        }
        """,
    )

    fun testVariableFromUseSeveralReads() = inline(
        """
        package n

        func nvShow(v ...int) {}

        func nvMany(a int) {
            k := a*2
            nvShow(k, <caret>k+1, -k)
        }
        """,
        """
        package n

        func nvShow(v ...int) {}

        func nvMany(a int) {
            nvShow(a*2, a*2+1, -(a*2))
        }
        """,
    )

    fun testVariableExplicitTypeConverts() = inline(
        """
        package n

        func nvTyped() float64 {
            var <caret>x float64 = 1
            return x / 2
        }
        """,
        """
        package n

        func nvTyped() float64 {
            return float64(1) / 2
        }
        """,
    )

    fun testVariableOneOfSeveralNames() = inline(
        """
        package n

        func nvPair() int {
            <caret>a, b := 1, 2
            return a + b
        }
        """,
        """
        package n

        func nvPair() int {
            b := 2
            return 1 + b
        }
        """,
    )

    fun testVariableCallUsedOnce() = inline(
        """
        package n

        func nvCompute() int { return 4 }

        func nvOnce() int {
            <caret>v := nvCompute()
            return v + 1
        }
        """,
        """
        package n

        func nvCompute() int { return 4 }

        func nvOnce() int {
            return nvCompute() + 1
        }
        """,
    )

    fun testVariableWrittenRefused() = refused(
        """
        package n

        func nvWritten() int {
            <caret>x := 1
            x = 2
            return x
        }
        """,
        "is written",
    )

    fun testVariableAddressTakenRefused() = refused(
        """
        package n

        func nvAddr() *int {
            <caret>x := 1
            return &x
        }
        """,
        "address",
    )

    fun testVariableSideEffectsTwoUsesRefused() = refused(
        """
        package n

        func nvGen() int { return 4 }

        func nvTwice() int {
            <caret>v := nvGen()
            return v + v
        }
        """,
        "side effects",
    )

    fun testVariableShadowedAtUseRefused() = refused(
        """
        package n

        func nvShadow(a int) int {
            <caret>x := a
            {
                a := 5
                return x + a
            }
        }
        """,
        "'a' means something else",
    )

    fun testVariableOperandChangesRefused() = refused(
        """
        package n

        func nvChanged() int {
            y := 1
            <caret>x := y
            y = 2
            return x + y
        }
        """,
        "'y' may change",
    )

    fun testVariableOperandChangedInLoopRefused() = refused(
        """
        package n

        func nvLoop(a int) int {
            s := 0
            <caret>x := a
            for i := 0; i < 3; i++ {
                a++
                s += x
            }
            return s
        }
        """,
        "'a' may change",
    )

    fun testConstantValueIntoClosure() = inline(
        """
        package n

        func nvClosure() func() int {
            <caret>x := 5
            return func() int { return x + 1 }
        }
        """,
        """
        package n

        func nvClosure() func() int {
            return func() int { return 5 + 1 }
        }
        """,
    )

    fun testVariableCallUsedInDeferredLiteralRefused() = refused(
        """
        package n

        func nvLater() int { return 4 }

        func nvDeferred() {
            <caret>v := nvLater()
            defer func() { println(v) }()
        }
        """,
        "another time",
    )

    fun testVariableCallAfterAnotherCallRefused() = refused(
        """
        package n

        func nvFirst() int { return 4 }
        func nvSecond() {}

        func nvOrder() int {
            <caret>v := nvFirst()
            nvSecond()
            return v
        }
        """,
        "Statements between",
    )

    // --- Inline Constant ---

    fun testPackageConstantWithPrecedence() = inline(
        """
        package n

        const <caret>ncLimit = 10 + 5

        func ncUse() int {
            return ncLimit * 2
        }
        """,
        """
        package n

        func ncUse() int {
            return (10 + 5) * 2
        }
        """,
    )

    fun testTypedConstantConverts() = inline(
        """
        package n

        const ncRate float64 = 2

        func ncRateUse() float64 {
            return <caret>ncRate / 4
        }
        """,
        """
        package n

        func ncRateUse() float64 {
            return float64(2) / 4
        }
        """,
    )

    fun testLocalConstantInGroup() = inline(
        """
        package n

        func ncLocal() int {
            const (
                <caret>ka = 3
                kb = 4
            )
            return ka * kb
        }
        """,
        """
        package n

        func ncLocal() int {
            const (
                kb = 4
            )
            return 3 * kb
        }
        """,
    )

    fun testIotaConstantRefused() = refused(
        """
        package n

        const (
            <caret>ncA = iota
            ncB
        )

        func ncIota() int { return ncA + ncB }
        """,
        "iota",
    )

    // --- Inline Function ---

    fun testFunctionCallWithPrecedence() = inline(
        """
        package n

        func nfAdd(a, b int) int { return a + b }

        func nfUse(x int) int {
            return <caret>nfAdd(x, 1) * 2
        }
        """,
        """
        package n

        func nfAdd(a, b int) int { return a + b }

        func nfUse(x int) int {
            return (x + 1) * 2
        }
        """,
    )

    fun testFunctionArgumentParenthesised() = inline(
        """
        package n

        func nfDouble(a int) int { return a * 2 }

        func nfUse2(x, y int) int {
            return <caret>nfDouble(x + y)
        }
        """,
        """
        package n

        func nfDouble(a int) int { return a * 2 }

        func nfUse2(x, y int) int {
            return (x + y) * 2
        }
        """,
    )

    fun testFunctionUntypedArgumentConverts() = inline(
        """
        package n

        func nfHalf(x float64) float64 { return x / 2 }

        func nfUse3() float64 {
            return <caret>nfHalf(1)
        }
        """,
        """
        package n

        func nfHalf(x float64) float64 { return x / 2 }

        func nfUse3() float64 {
            return float64(1) / 2
        }
        """,
    )

    fun testFunctionWithoutResult() = inline(
        """
        package n

        func nfLog(s string) { println("log:", s) }

        func nfUse4() {
            <caret>nfLog("x")
        }
        """,
        """
        package n

        func nfLog(s string) { println("log:", s) }

        func nfUse4() {
            println("log:", "x")
        }
        """,
    )

    fun testMethodCall() = inline(
        """
        package n

        type nfPoint struct{ X, Y int }

        func (p nfPoint) Sum() int { return p.X + p.Y }

        func nfUse5(pt nfPoint) int {
            return pt.<caret>Sum()
        }
        """,
        """
        package n

        type nfPoint struct{ X, Y int }

        func (p nfPoint) Sum() int { return p.X + p.Y }

        func nfUse5(pt nfPoint) int {
            return pt.X + pt.Y
        }
        """,
    )

    fun testAllCallsAndDeclaration() = inline(
        """
        package n

        func <caret>nfSquare(v int) int { return v * v }

        func nfUse6(a int) int {
            return nfSquare(a) + nfSquare(3)
        }
        """,
        """
        package n

        func nfUse6(a int) int {
            return a * a + 3 * 3
        }
        """,
    )

    fun testRecursiveFunctionRefused() = refused(
        """
        package n

        func nfFact(n int) int { return n * nfFact(n-1) }

        func nfUse7() int { return <caret>nfFact(3) }
        """,
        "recursive",
    )

    fun testMultiStatementBodyRefused() = refused(
        """
        package n

        func nfTwo(a int) int {
            b := a + 1
            return b
        }

        func nfUse8() int { return <caret>nfTwo(3) }
        """,
        "single statement",
    )

    fun testSideEffectArgumentRefused() = refused(
        """
        package n

        func nfId(a int) int { return a }
        func nfNext() int { return 1 }

        func nfUse9() int { return <caret>nfId(nfNext()) }
        """,
        "side effects",
    )

    fun testArgumentUsedTwiceRefused() = refused(
        """
        package n

        func nfSq(v int) int { return v * v }

        func nfUse10(a, b int) int { return <caret>nfSq(a + b) }
        """,
        "used 2 times",
    )

    // --- the platform action and the gate ---

    fun testThroughTheAction() {
        myFixture.configureByText(
            "na.go",
            go(
                """
                package n

                func naUse(a int) int {
                    <caret>t := a - 1
                    return t * 3
                }
                """,
            ),
        )
        myFixture.performEditorAction("Inline")
        myFixture.checkResult(
            go(
                """
                package n

                func naUse(a int) int {
                    return (a - 1) * 3
                }
                """,
            ),
        )
    }

    fun testGateOff() {
        ApplicationManager.getApplication().replaceService(GoIdeFeatureGate::class.java, object : GoIdeFeatureGate {
            override fun enabled(feature: GoIdeFeature, project: Project): Boolean = feature != GoIdeFeature.RENAME
        }, testRootDisposable)
        val text = go(
            """
            package n

            func ngUse(a int) int {
                <caret>t := a - 1
                return t * 3
            }
            """,
        )
        myFixture.configureByText("ng.go", text)
        val element = target()
        val handler = GoInlineActionHandler()
        assertFalse(handler.canInlineElement(element))
        handler.inlineElement(project, myFixture.editor, element)
        assertEquals(text.replace("<caret>", ""), myFixture.editor.document.text)
    }
}
