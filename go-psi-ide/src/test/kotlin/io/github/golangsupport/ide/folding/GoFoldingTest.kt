package io.github.golangsupport.ide.folding

import com.intellij.codeInsight.folding.CodeFoldingSettings
import com.intellij.lang.folding.LanguageFolding
import io.github.golangsupport.ide.GoIdeTestBase

class GoFoldingTest : GoIdeTestBase() {

    override val testDataSubdir: String = "folding"

    fun testFolding() {
        myFixture.testFolding("$testDataPath/Folding.go")
    }

    fun testImportsCollapsedBySetting() {
        myFixture.configureByText("a.go", "package p\n\nimport (\n\t\"fmt\"\n)\n\nfunc f() {\n\tfmt.Println()\n}\n")
        val builder = GoFoldingBuilder()
        val descriptors = LanguageFolding.buildFoldingDescriptors(builder, myFixture.file, myFixture.editor.document, false)
        val imports = descriptors.single { it.placeholderText == "(...)" }
        val body = descriptors.single { it.placeholderText == "{...}" }
        val settings = CodeFoldingSettings.getInstance()
        val saved = settings.COLLAPSE_IMPORTS
        try {
            for (collapse in listOf(true, false)) {
                settings.COLLAPSE_IMPORTS = collapse
                assertEquals(collapse, builder.isCollapsedByDefault(imports))
                assertFalse(builder.isCollapsedByDefault(body))
            }
        } finally {
            settings.COLLAPSE_IMPORTS = saved
        }
    }
}
