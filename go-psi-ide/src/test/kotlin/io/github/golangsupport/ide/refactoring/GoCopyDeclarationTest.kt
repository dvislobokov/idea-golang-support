package io.github.golangsupport.ide.refactoring

import com.intellij.refactoring.copy.CopyHandler
import io.github.golangsupport.ide.GoSemanticIdeTestBase

/** Copy / Clone of a top-level declaration through the platform's CopyHandler and the Go delegate (4-space indents, tabs in the file). */
class GoCopyDeclarationTest : GoSemanticIdeTestBase() {

    private fun go(text: String): String = text.trimIndent().replace("    ", "\t") + "\n"

    private fun withAnswer(options: GoCopyOptions, action: () -> Unit) {
        GoCopyDeclarationHandler.testOptions = options
        try {
            action()
        } finally {
            GoCopyDeclarationHandler.testOptions = null
        }
    }

    private fun atCaret() = myFixture.file.findElementAt(myFixture.caretOffset)!!

    fun testCloneFunctionRenamesRecursionAndDoc() {
        myFixture.configureByText("cp.go", go(
            """
            package cp

            // cpFact is n!.
            func <caret>cpFact(n int) int {
                if n == 0 {
                    return 1
                }
                return n * cpFact(n-1)
            }
            """,
        ))
        assertTrue(CopyHandler.canClone(arrayOf(atCaret())))
        withAnswer(GoCopyOptions("cpFact2")) { CopyHandler.doClone(atCaret()) }
        myFixture.checkResult(go(
            """
            package cp

            // cpFact is n!.
            func cpFact(n int) int {
                if n == 0 {
                    return 1
                }
                return n * cpFact(n-1)
            }

            // cpFact2 is n!.
            func cpFact2(n int) int {
                if n == 0 {
                    return 1
                }
                return n * cpFact2(n-1)
            }
            """,
        ))
    }

    fun testCopyTypeIntoAnotherFile() {
        myFixture.addFileToProject("cp2.go", go("package cp\n\nvar cpX = 1"))
        myFixture.configureByText("cp.go", go(
            """
            package cp

            import "strings"

            type (
                <caret>cpNode struct {
                    next *cpNode
                    b    strings.Builder
                }
                cpOther int
            )
            """,
        ))
        withAnswer(GoCopyOptions("cpList", "cp2.go")) { CopyHandler.doCopy(arrayOf(atCaret()), null) }
        val actual = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir("cp2.go"))!!.text
        assertEquals(go(
            """
            package cp

            import "strings"

            var cpX = 1

            type cpList struct {
                next *cpList
                b    strings.Builder
            }
            """,
        ), actual)
    }

    fun testCloneMethodCallingItself() {
        myFixture.configureByText("cm.go", go(
            """
            package cm

            type cmT struct{}

            func (t cmT) <caret>walk(n int) int {
                if n == 0 {
                    return 0
                }
                return t.walk(n - 1)
            }
            """,
        ))
        withAnswer(GoCopyOptions("walkBack")) { CopyHandler.doClone(atCaret()) }
        myFixture.checkResult(go(
            """
            package cm

            type cmT struct{}

            func (t cmT) walk(n int) int {
                if n == 0 {
                    return 0
                }
                return t.walk(n - 1)
            }

            func (t cmT) walkBack(n int) int {
                if n == 0 {
                    return 0
                }
                return t.walkBack(n - 1)
            }
            """,
        ))
    }

    fun testCloneVariable() {
        myFixture.configureByText("cv.go", go("package cv\n\nvar <caret>cvLimit = 10\n\nfunc cvF() int { return cvLimit }"))
        withAnswer(GoCopyOptions("cvMax")) { CopyHandler.doClone(atCaret()) }
        myFixture.checkResult(go("package cv\n\nvar cvLimit = 10\n\nvar cvMax = 10\n\nfunc cvF() int { return cvLimit }"))
    }

    fun testOnlyNamesOfTopLevelDeclarations() {
        myFixture.configureByText("cn.go", go("package cn\n\nfunc cnF() int {\n    <caret>x := 1\n    return x\n}"))
        assertFalse(CopyHandler.canCopy(arrayOf(atCaret())) && GoCopyDeclaration.declarationOf(atCaret()) != null)
        assertNull(GoCopyDeclaration.declarationOf(atCaret()))
        assertNull(GoCopyDeclaration.declarationOf(myFixture.file))
    }

    fun testNameClashIsRefused() {
        myFixture.configureByText("cc.go", go("package cc\n\nfunc <caret>ccA() {}\n\nfunc ccB() {}"))
        try {
            withAnswer(GoCopyOptions("ccB")) { CopyHandler.doClone(atCaret()) }
            fail("Expected a refusal")
        } catch (e: RuntimeException) {
            assertTrue(e.message, e.message!!.contains("already declared"))
        }
    }

    fun testIotaConstantIsRefused() {
        val decl = myFixture.configureByText("ci.go", go("package ci\n\nconst (\n    ciA = iota\n    ciB\n)")).let { GoCopyDeclaration.declarationOf(myFixture.findElementByText("ciA", io.github.golangsupport.lang.psi.GoConstDefinition::class.java)) }!!
        assertEquals("The constant uses iota", GoCopyDeclaration.problem(decl, "ciC", myFixture.file as io.github.golangsupport.lang.psi.GoFile))
    }
}
