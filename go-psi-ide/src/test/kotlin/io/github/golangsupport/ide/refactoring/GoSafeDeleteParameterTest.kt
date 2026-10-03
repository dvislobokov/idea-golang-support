package io.github.golangsupport.ide.refactoring

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.lang.LanguageRefactoringSupport
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.safeDelete.SafeDeleteHandler
import com.intellij.testFramework.replaceService
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.lang.psi.GoParamDefinition

/** Safe Delete of the parameter at `<caret>`: the signature and every call rewritten, or the conflicts it reports. */
class GoSafeDeleteParameterTest : GoSemanticIdeTestBase() {

    private fun go(text: String): String = text.trimIndent().replace("    ", "\t") + "\n"

    private fun target(name: String, before: String): PsiElement {
        myFixture.configureByText(name, go(before))
        return TargetElementUtil.findTargetElement(myFixture.editor, TargetElementUtil.ELEMENT_NAME_ACCEPTED or TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED)!!
    }

    private fun delete(name: String, before: String) {
        val element = target(name, before)
        assertTrue(element.toString(), element is GoParamDefinition)
        SafeDeleteHandler.invoke(project, arrayOf(element), true)
    }

    private fun doTest(before: String, after: String, others: Map<String, Pair<String, String>> = emptyMap()) {
        for ((name, content) in others) myFixture.addFileToProject(name, go(content.first))
        delete("sp.go", before)
        assertEquals(go(after), myFixture.editor.document.text)
        for ((name, content) in others) myFixture.checkResult(name, go(content.second), true)
    }

    private fun conflicts(before: String, others: Map<String, String> = emptyMap()): String {
        for ((name, content) in others) myFixture.addFileToProject(name, go(content))
        try {
            delete("sq.go", before)
        } catch (e: BaseRefactoringProcessor.ConflictsInTestsException) {
            return e.messages.joinToString("\n")
        }
        fail("Expected conflicts")
        return ""
    }

    /** Refactor Anyway: the conflicts are ignored and the deletion goes ahead. */
    private fun anyway(before: String, after: String) {
        BaseRefactoringProcessor.ConflictsInTestsException.withIgnoredConflicts<RuntimeException> { delete("sr.go", before) }
        assertEquals(go(after), myFixture.editor.document.text)
    }

    fun testSimpleRemovalAcrossTwoFiles() = doTest(
        """
        package sp

        func spSum(a int, <caret>b int) int {
            return a
        }

        func spUse() int { return spSum(1, 2) + spSum(3, 4) }
        """,
        """
        package sp

        func spSum(a int) int {
            return a
        }

        func spUse() int { return spSum(1) + spSum(3) }
        """,
        mapOf("sp2.go" to ("package sp\n\nfunc spOther(x int) int { return spSum(x, x+1) }" to "package sp\n\nfunc spOther(x int) int { return spSum(x) }")),
    )

    fun testOnlyParameter() = doTest(
        """
        package sp

        func spOnly(<caret>d int) {
            println()
        }

        func spUse() { spOnly(5) }
        """,
        """
        package sp

        func spOnly() {
            println()
        }

        func spUse() { spOnly() }
        """,
    )

    fun testFirstOfGroup() = doTest(
        """
        package sp

        func spGroup(<caret>a, b int, s string) int {
            return b
        }

        func spUse() int { return spGroup(1, 2, "x") }
        """,
        """
        package sp

        func spGroup(b int, s string) int {
            return b
        }

        func spUse() int { return spGroup(2, "x") }
        """,
    )

    fun testLastOfGroup() = doTest(
        """
        package sp

        func spGroupLast(a, <caret>b int) int {
            return a
        }

        func spUse() int { return spGroupLast(1, 2) }
        """,
        """
        package sp

        func spGroupLast(a int) int {
            return a
        }

        func spUse() int { return spGroupLast(1) }
        """,
    )

    fun testVariadic() = doTest(
        """
        package sp

        func spVar(a int, <caret>xs ...int) int {
            return a
        }

        func spUse(s []int) int { return spVar(1, 2, 3) + spVar(1) + spVar(1, s...) }
        """,
        """
        package sp

        func spVar(a int) int {
            return a
        }

        func spUse(s []int) int { return spVar(1) + spVar(1) + spVar(1) }
        """,
    )

    // the outer argument is a call (a side effect, so a conflict); on Refactor Anyway it goes whole, the inner call with it
    fun testNestedCallsRefactorAnyway() = anyway(
        """
        package sr

        func srNest(a, <caret>b int) int {
            return a
        }

        func srUse() int { return srNest(srNest(1, 2), srNest(3, 4)) }
        """,
        """
        package sr

        func srNest(a int) int {
            return a
        }

        func srUse() int { return srNest(srNest(1)) }
        """,
    )

    fun testMethodAndMethodExpression() = doTest(
        """
        package sp

        type SpS struct{}

        func (SpS) spMe(a, <caret>b int) int { return a }

        func spUse(s SpS) int { return SpS.spMe(s, 1, 2) + s.spMe(3, 4) + (*SpS).spMe(&s, 5, 6) }
        """,
        """
        package sp

        type SpS struct{}

        func (SpS) spMe(a int) int { return a }

        func spUse(s SpS) int { return SpS.spMe(s, 1) + s.spMe(3) + (*SpS).spMe(&s, 5) }
        """,
    )

    fun testUsedInBodyIsConflict() {
        val message = conflicts(
            """
            package sq

            func sqBody(a, <caret>b int) int {
                return a + b*b
            }
            """,
        )
        assertEquals("Parameter b is used in the body (2 times); the references stay and no longer resolve", message)
    }

    fun testUsedInBodyRefactorAnyway() = anyway(
        """
        package sr

        func srBody(a, <caret>b int) int {
            return a + b
        }

        func srUse() int { return srBody(1, 2) }
        """,
        """
        package sr

        func srBody(a int) int {
            return a + b
        }

        func srUse() int { return srBody(1) }
        """,
    )

    fun testFunctionValueIsConflict() {
        val message = conflicts(
            """
            package sq

            func sqCb(a, <caret>b int) int { return a }
            """,
            mapOf("sq2.go" to "package sq\n\nfunc sqRun(f func(int, int) int) int { return f(1, 2) }\n\nvar _ = sqRun(sqCb)"),
        )
        assertEquals("Function sqCb is used as a value here; its type changes", message)
    }

    fun testImplementingMethodIsConflict() {
        val message = conflicts(
            """
            package sq

            type SqRunner interface {
                SqRun(n int)
            }

            type SqJob struct{}

            func (SqJob) SqRun(<caret>n int) {}

            var _ SqRunner = SqJob{}
            """,
        )
        assertEquals("Method SqJob.SqRun implements SqRunner.SqRun; without parameter n it no longer does", message)
    }

    fun testInterfaceMethodSpecIsConflict() {
        val message = conflicts(
            """
            package sq

            type SqShape interface {
                SqScale(<caret>k float64)
            }
            """,
        )
        assertEquals("SqShape.SqScale is an interface method: delete the parameter in its implementations first; only the interface signature changes", message)
    }

    fun testSideEffectArgumentIsConflictAndRemovedAnyway() {
        val before = """
            package sr

            func srNext() int { return 0 }

            func srSide(a, <caret>b int) int { return a }

            func srUse() int { return srSide(1, srNext()) }
            """
        val message = conflicts(before.replace("package sr", "package sq").replace("sr", "sq"))
        assertEquals("The argument for b in this call of sqSide has side effects; it will be removed", message)
        anyway(
            before,
            """
            package sr

            func srNext() int { return 0 }

            func srSide(a int) int { return a }

            func srUse() int { return srSide(1) }
            """,
        )
    }

    fun testMultiValueArgumentIsConflictAndLeftAsIs() {
        val before = """
            package sr

            func srPair() (int, int) { return 1, 2 }

            func srMulti(a, <caret>b int) int { return a }

            func srUse() int { return srMulti(srPair()) }
            """
        val message = conflicts(before.replace("package sr", "package sq").replace("sr", "sq"))
        assertEquals("The arguments of this call of sqMulti do not map one to one onto its parameters; the call is left as is", message)
        anyway(
            before,
            """
            package sr

            func srPair() (int, int) { return 1, 2 }

            func srMulti(a int) int { return a }

            func srUse() int { return srMulti(srPair()) }
            """,
        )
    }

    fun testNotForLiteralResultOrReceiver() {
        myFixture.configureByText(
            "sn.go",
            go(
                """
                package sn

                type SnT struct{}

                func (recv SnT) snM() (res int) {
                    f := func(lit int) {}
                    f(1)
                    return 0
                }
                """,
            ),
        )
        for (name in listOf("lit", "res")) {
            val def = myFixture.findElementByText(name, GoParamDefinition::class.java)
            assertFalse(name, GoSafeDeleteProcessor.isSupported(def))
        }
        val recv = myFixture.file.findElementAt(myFixture.file.text.indexOf("recv"))!!.parent
        assertFalse(GoSafeDeleteProcessor.isSupported(recv))
    }

    fun testGateOff() {
        ApplicationManager.getApplication().replaceService(GoIdeFeatureGate::class.java, object : GoIdeFeatureGate {
            override fun enabled(feature: GoIdeFeature, project: Project): Boolean = feature != GoIdeFeature.RENAME
        }, testRootDisposable)
        val def = target("sg.go", "package sg\n\nfunc sgOff(a, <caret>b int) int { return a }")
        assertTrue(GoSafeDeleteProcessor.isSupported(def))
        assertFalse(GoSafeDeleteProcessor().handlesElement(def))
        assertFalse(LanguageRefactoringSupport.getInstance().forLanguage(GoLanguage)!!.isSafeDeleteAvailable(def))
    }
}
