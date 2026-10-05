package io.github.golangsupport.ide.cleanup

import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.codeInspection.ex.GlobalInspectionContextBase
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.testFramework.enableInspectionTool
import io.github.golangsupport.ide.GoIdeTestBase

/** Code | Code Cleanup over Go: the inspections marked `cleanupTool` in go-psi-ide-inspections.xml apply their fixes in one pass. */
class GoCodeCleanupTest : GoIdeTestBase() {
    private fun wrapper(shortName: String): LocalInspectionToolWrapper =
        LocalInspectionToolWrapper(LocalInspectionEP.LOCAL_INSPECTION.extensionList.single { it.language == "Go" && it.shortName == shortName })

    fun testTheFormattingLikeInspectionsAreCleanupTools() {
        for (id in listOf("GoRedundantParens", "GoRedundantSemicolon", "GoRedundantComma", "GoUnsortedImport", "GoFixAny")) assertTrue(id, wrapper(id).isCleanupTool)
        assertFalse("omitzero changes what encoding/json writes", wrapper("GoFixOmitZero").isCleanupTool)
    }

    fun testCodeCleanupAppliesTheirFixes() {
        for (id in listOf("GoRedundantParens", "GoRedundantSemicolon")) enableInspectionTool(project, wrapper(id), testRootDisposable)
        myFixture.configureByText("c.go", "package p\n\nfunc f(x int) int {\n\ty := 1;\n\treturn (x + y)\n}\n")
        GlobalInspectionContextBase.cleanupElements(project, null, myFixture.file)
        assertEquals("package p\n\nfunc f(x int) int {\n\ty := 1\n\treturn x + y\n}\n", myFixture.editor.document.text)
    }
}
