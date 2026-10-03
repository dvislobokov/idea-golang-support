package io.github.golangsupport.ide.refactoring

import com.intellij.lang.LanguageRefactoringSupport
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.testFramework.replaceService
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.lang.GoLanguage

/** Change Signature driven through [GoChangeSignatureProcessor] with options built from the declaration at `<caret>` (no dialog). */
class GoChangeSignatureTest : GoSemanticIdeTestBase() {

    private fun go(text: String): String = text.trimIndent().replace("    ", "\t") + "\n"

    private fun target(name: String, before: String): PsiElement {
        myFixture.configureByText(name, go(before))
        val handler = LanguageRefactoringSupport.getInstance().forLanguage(GoLanguage)!!.changeSignatureHandler!!
        return handler.findTargetMember(myFixture.file, myFixture.editor)!!
    }

    private fun change(target: PsiElement, edit: (GoChangeSignatureOptions) -> GoChangeSignatureOptions) =
        GoChangeSignatureProcessor(project, target, edit(GoChangeSignature.initial(target))).run()

    private fun doTest(before: String, after: String, others: Map<String, Pair<String, String>> = emptyMap(), edit: (GoChangeSignatureOptions) -> GoChangeSignatureOptions) {
        for ((name, content) in others) myFixture.addFileToProject(name, go(content.first))
        change(target("cs.go", before), edit)
        assertEquals(go(after), myFixture.editor.document.text)
        for ((name, content) in others) myFixture.checkResult(name, go(content.second), true)
    }

    private fun conflicts(before: String, others: Map<String, String> = emptyMap(), edit: (GoChangeSignatureOptions) -> GoChangeSignatureOptions): String {
        for ((name, content) in others) myFixture.addFileToProject(name, go(content))
        try {
            change(target("cq.go", before), edit)
        } catch (e: BaseRefactoringProcessor.ConflictsInTestsException) {
            return e.messages.sorted().joinToString("\n")
        }
        fail("Expected conflicts")
        return ""
    }

    private fun refused(before: String, edit: (GoChangeSignatureOptions) -> GoChangeSignatureOptions): String {
        try {
            change(target("cr.go", before), edit)
        } catch (e: RuntimeException) { // CommonRefactoringUtil.showErrorMessage throws in tests
            if (e is BaseRefactoringProcessor.ConflictsInTestsException) throw e
            return e.message!!
        }
        fail("Expected a refusal")
        return ""
    }

    private fun GoChangeSignatureOptions.order(vararg slots: Int) = copy(parameters = slots.map { parameters.first { p -> p.oldIndex == it } })

    fun testRenameWithCallsInTwoFiles() = doTest(
        """
        package cs

        // csOld adds.
        func <caret>csOld(a, b int) int { return a + b }

        func csUse() int { return csOld(1, 2) + csOld(csOld(3, 4), 5) }
        """,
        """
        package cs

        // csNew adds.
        func csNew(a, b int) int { return a + b }

        func csUse() int { return csNew(1, 2) + csNew(csNew(3, 4), 5) }
        """,
        mapOf("cs2.go" to ("package cs\n\nvar csF = csOld\n\nfunc csOther() int { return csOld(0, 0) }" to "package cs\n\nvar csF = csNew\n\nfunc csOther() int { return csNew(0, 0) }")),
    ) { it.copy(name = "csNew") }

    fun testAddParameterWithDefault() = doTest(
        """
        package cs

        func <caret>csAdd(a int) int { return a }

        func csUse() int { return csAdd(1) + csAdd(csAdd(2)) }
        """,
        """
        package cs

        func csAdd(a int, verbose bool) int { return a }

        func csUse() int { return csAdd(1, false) + csAdd(csAdd(2, false), false) }
        """,
    ) { it.copy(parameters = it.parameters + GoChangeParameter("verbose", "bool", -1, "false")) }

    fun testAddParameterToEmptyList() = doTest(
        """
        package cs

        func <caret>csNone() {}

        func csUse() { csNone() }
        """,
        """
        package cs

        func csNone(n int) {}

        func csUse() { csNone(42) }
        """,
    ) { it.copy(parameters = listOf(GoChangeParameter("n", "int", -1, "42"))) }

    fun testRemoveParameter() = doTest(
        """
        package cs

        func <caret>csRem(a int, b string, c bool) int { return a }

        func csUse() int { return csRem(1, "x", true) }
        """,
        """
        package cs

        func csRem(a int, c bool) int { return a }

        func csUse() int { return csRem(1, true) }
        """,
    ) { it.order(0, 2) }

    fun testReorderPureArguments() = doTest(
        """
        package cs

        func <caret>csOrd(a int, b string) string { return b }

        func csUse(x int) string { return csOrd(x+1, "s") }
        """,
        """
        package cs

        func csOrd(b string, a int) string { return b }

        func csUse(x int) string { return csOrd("s", x+1) }
        """,
    ) { it.order(1, 0) }

    fun testChangeTypeAndRenameParameter() = doTest(
        """
        package cs

        func <caret>csTy(a, b int) int {
            return a * b
        }

        func csUse() int { return csTy(1, 2) }
        """,
        """
        package cs

        func csTy(left int64, b int) int {
            return left * b
        }

        func csUse() int { return csTy(1, 2) }
        """,
    ) { it.copy(parameters = listOf(it.parameters[0].copy(name = "left", type = "int64"), it.parameters[1])) }

    fun testResultsKeepParameterGroups() = doTest(
        """
        package cs

        func <caret>csRes(a, b int) {
        }

        func csUse() { csRes(1, 2) }
        """,
        """
        package cs

        func csRes(a, b int) (int, error) {
        }

        func csUse() { csRes(1, 2) }
        """,
    ) { it.copy(results = GoChangeSignature.parseResults("(int, error)")) }

    fun testMethodAndMethodExpression() = doTest(
        """
        package cs

        type CsS struct{}

        func (s CsS) <caret>csMe(a int, b string) {}

        func csUse(s CsS) {
            s.csMe(1, "x")
            CsS.csMe(s, 2, "y")
            (*CsS).csMe(&s, 3, "z")
        }
        """,
        """
        package cs

        type CsS struct{}

        func (s CsS) csMe2(b string, a int, ok bool) {}

        func csUse(s CsS) {
            s.csMe2("x", 1, true)
            CsS.csMe2(s, "y", 2, true)
            (*CsS).csMe2(&s, "z", 3, true)
        }
        """,
    ) { it.order(1, 0).let { o -> o.copy(name = "csMe2", parameters = o.parameters + GoChangeParameter("ok", "bool", -1, "true")) } }

    fun testVariadicStaysLast() = doTest(
        """
        package cs

        func <caret>csVar(a int, b string, xs ...int) {}

        func csUse(ys []int) {
            csVar(1, "a", 2, 3)
            csVar(1, "b")
            csVar(1, "c", ys...)
        }
        """,
        """
        package cs

        func csVar(b string, a int, xs ...int) {}

        func csUse(ys []int) {
            csVar("a", 1, 2, 3)
            csVar("b", 1)
            csVar("c", 1, ys...)
        }
        """,
    ) { it.order(1, 0, 2) }

    fun testVariadicMovedAwayFromEndIsRefused() {
        val message = refused("package cr\n\nfunc <caret>crVar(a int, xs ...int) {}") { it.order(1, 0) }
        assertEquals("Only the last parameter can be variadic", message)
    }

    fun testNewParameterWithoutDefaultIsRefused() {
        val message = refused("package cr\n\nfunc <caret>crDef(a int) {}") { it.copy(parameters = it.parameters + GoChangeParameter("b", "int")) }
        assertEquals("New parameter b needs a default value for the calls", message)
    }

    fun testSideEffectReorderIsConflict() {
        val message = conflicts(
            """
            package cq

            func cqNext() int { return 0 }

            func <caret>cqSide(a, b int) int { return a }

            func cqUse() int { return cqSide(cqNext(), 2) }
            """,
        ) { it.order(1, 0) }
        assertEquals("The arguments of this call of cqSide have side effects; their evaluation order changes", message)
    }

    fun testSideEffectReorderRefactorAnyway() {
        BaseRefactoringProcessor.ConflictsInTestsException.withIgnoredConflicts<RuntimeException> {
            change(target("cr.go", "package cr\n\nfunc crNext() int { return 0 }\n\nfunc <caret>crAny(a, b int) int { return a }\n\nvar _ = crAny(crNext(), 2)")) { it.order(1, 0) }
        }
        assertEquals(go("package cr\n\nfunc crNext() int { return 0 }\n\nfunc crAny(b int, a int) int { return a }\n\nvar _ = crAny(2, crNext())"), myFixture.editor.document.text)
    }

    fun testMultiValueArgumentIsConflict() {
        val message = conflicts(
            """
            package cq

            func cqPair() (int, int) { return 1, 2 }

            func <caret>cqTwo(a, b int) {}

            func cqUse() { cqTwo(cqPair()) }
            """,
        ) { it.order(1, 0) }
        assertEquals("The arguments of this call of cqTwo do not map one to one onto its parameters; only its name changes", message)
    }

    fun testFunctionValueIsConflict() {
        val message = conflicts(
            "package cq\n\nfunc <caret>cqCb(a int) int { return a }",
            mapOf("cq2.go" to "package cq\n\nfunc cqRun(f func(int) int) int { return f(1) }\n\nvar _ = cqRun(cqCb)"),
        ) { it.copy(parameters = it.parameters + GoChangeParameter("b", "int", -1, "0")) }
        assertEquals("Function cqCb is used as a value here; its type changes", message)
    }

    fun testImplementingMethodIsConflict() {
        val message = conflicts(
            """
            package cq

            type CqRunner interface {
                CqRun(n int)
            }

            type CqJob struct{}

            func (CqJob) <caret>CqRun(n int) {}

            var _ CqRunner = CqJob{}
            """,
        ) { it.copy(parameters = listOf(it.parameters[0].copy(type = "int64"))) }
        assertEquals("Method CqJob.CqRun implements CqRunner.CqRun; after the change it no longer does", message)
    }

    fun testInterfaceMethodIsConflict() {
        val message = conflicts(
            """
            package cq

            type CqShape interface {
                <caret>CqScale(k float64)
            }
            """,
        ) { it.copy(name = "CqResize") }
        assertEquals("CqShape.CqScale is an interface method: its implementations and calls through other interfaces are not changed", message)
    }

    fun testRemovedParameterUsedInBodyAndResultsUsed() {
        val message = conflicts(
            """
            package cq

            func <caret>cqBody(a, b int) int { return a + b }

            func cqUse() int {
                cqBody(1, 2)
                return cqBody(3, 4)
            }
            """,
        ) { it.order(0).copy(results = emptyList()) }
        assertEquals("1 call site uses the results of cqBody; the calls are not rewritten\nParameter b is used in the body; the references stay and no longer resolve", message)
    }

    fun testNameClashIsConflict() {
        val message = conflicts("package cq\n\nfunc <caret>cqA() {}\n\nfunc cqB() {}") { it.copy(name = "cqB") }
        assertEquals("Package cq already declares cqB", message)
    }

    fun testTargetFromCallArguments() {
        myFixture.configureByText("ct.go", go("package ct\n\nfunc ctF(a int) int { return a }\n\nvar _ = ctF(<caret>1)"))
        val handler = GoChangeSignatureHandler()
        assertEquals("ctF", GoChangeSignature.nameOf(handler.findTargetMember(myFixture.file, myFixture.editor)!!))
    }

    fun testParseResults() {
        assertEquals(listOf(GoChangeResult("", "int")), GoChangeSignature.parseResults("int"))
        assertEquals(listOf(GoChangeResult("", "chan int"), GoChangeResult("", "func() error")), GoChangeSignature.parseResults("(chan int, func() error)"))
        assertEquals(listOf(GoChangeResult("n", "int"), GoChangeResult("err", "error")), GoChangeSignature.parseResults("n int, err error"))
        assertEquals(" (n int, err error)", GoChangeSignature.resultsText(GoChangeSignature.parseResults("(n int, err error)")))
    }

    fun testGateOff() {
        ApplicationManager.getApplication().replaceService(GoIdeFeatureGate::class.java, object : GoIdeFeatureGate {
            override fun enabled(feature: GoIdeFeature, project: Project): Boolean = feature != GoIdeFeature.RENAME
        }, testRootDisposable)
        myFixture.configureByText("cg.go", go("package cg\n\nfunc <caret>cgOff(a int) {}"))
        assertNull(GoChangeSignatureHandler().findTargetMember(myFixture.file, myFixture.editor))
    }
}
