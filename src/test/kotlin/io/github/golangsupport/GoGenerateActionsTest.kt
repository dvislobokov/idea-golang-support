package io.github.golangsupport

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.lang.GoGenerateMethodAction
import io.github.golangsupport.lang.GoGenerateTestsForPackageAction
import io.github.golangsupport.lang.GoUpdateCopyrightsProvider
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileTypes.FileTypeExtensionPoint

/** Alt+Insert | Method and Tests for Package, with their dialogs answered by the test hooks. */
class GoGenerateActionsTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            GoGenerateMethodAction.dialog = null
            GoGenerateTestsForPackageAction.chooser = null
        } finally {
            super.tearDown()
        }
    }

    fun testMethodAfterTheLastMethodWithTheReceiverOfTheType() {
        myFixture.configureByText(
            "server.go",
            "package store\n\ntype <caret>Server struct {\n\tport int\n}\n\nfunc (srv Server) Port() int { return srv.port }\n\nfunc other() {}\n",
        )
        var pointerDefault: Boolean? = null
        GoGenerateMethodAction.dialog = { _, pointer -> pointerDefault = pointer; GoGenerateMethodAction.Input("Get", true, "id string", "string, error") }
        myFixture.testAction(GoGenerateMethodAction())
        // the type's only method has a value receiver: the dialog starts with a value one
        assertEquals(false, pointerDefault)
        myFixture.checkResult(
            "package store\n\ntype Server struct {\n\tport int\n}\n\nfunc (srv Server) Port() int { return srv.port }\n\n" +
                "func (srv *Server) Get(id string) (string, error) {\n\tpanic(\"not implemented\")\n}\n\nfunc other() {}\n",
        )
    }

    /** Generate | Copyright of the Copyright plugin is enabled for files of a type with an updater: Go has one (go-copyright.xml). */
    fun testGoFilesHaveACopyrightUpdater() {
        val updaters = ExtensionPointName<FileTypeExtensionPoint<Any>>("com.intellij.copyright.updater").extensionList
        assertTrue(updaters.any { it.filetype == "Go" && it.implementationClass == GoUpdateCopyrightsProvider::class.java.name })
        assertFalse(GoUpdateCopyrightsProvider().defaultOptions.isBlock)
    }

    fun testTestsForPackageSkipTheTestedAndTheUnexported() {
        myFixture.addFileToProject("calc/calc_test.go", "package calc\n\nimport \"testing\"\n\nfunc TestSum(t *testing.T) {}\n")
        myFixture.addFileToProject("calc/server.go", "package calc\n\ntype Server struct{}\n\nfunc (s *Server) Start() error { return nil }\n")
        val file = myFixture.addFileToProject("calc/calc.go", "package calc\n\nfunc Sum(a, b int) int { return a + b }\n\nfunc Mul(a, b int) int { return a * b }\n\nfunc helper() {}\n")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        var offered: List<String> = emptyList()
        GoGenerateTestsForPackageAction.chooser = { candidates -> offered = candidates.map { it.function.name }; candidates }
        myFixture.testAction(GoGenerateTestsForPackageAction())
        assertEquals(listOf("Mul", "Start"), offered)
        val calcTest = FileDocumentManager.getInstance().getDocument(file.virtualFile.parent.findChild("calc_test.go")!!)!!.text
        assertTrue(calcTest, calcTest.startsWith("package calc\n\nimport \"testing\"\n\nfunc TestSum(t *testing.T) {}\n\nfunc TestMul(t *testing.T) {\n"))
        val serverTest = FileDocumentManager.getInstance().getDocument(file.virtualFile.parent.findChild("server_test.go")!!)!!.text
        assertTrue(serverTest, serverTest.startsWith("package calc\n\nimport \"testing\"\n\nfunc TestServer_Start(t *testing.T) {\n"))
    }
}
