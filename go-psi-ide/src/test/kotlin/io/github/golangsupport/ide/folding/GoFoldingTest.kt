package io.github.golangsupport.ide.folding

import com.intellij.codeInsight.folding.CodeFoldingSettings
import com.intellij.lang.folding.LanguageFolding
import io.github.golangsupport.ide.GoIdeTestBase

class GoFoldingTest : GoIdeTestBase() {

    override val testDataSubdir: String = "folding"

    fun testFolding() {
        myFixture.testFolding("$testDataPath/Folding.go")
    }

    fun testNestedBlocksFoldOnce() {
        myFixture.configureByText(
            "a.go",
            "package p\n\nfunc f(x int) {\n\tif x > 0 {\n\t\tfor x > 0 {\n\t\t\tx--\n\t\t}\n\t}\n\tg := func() {\n\t\tx++\n\t}\n\tg()\n\t// one\n}\n",
        )
        val descriptors = LanguageFolding.buildFoldingDescriptors(GoFoldingBuilder(), myFixture.file, myFixture.editor.document, false)
        val ranges = descriptors.map { it.range }
        assertEquals(ranges.toSet().size, ranges.size)
        assertEquals(4, descriptors.count { it.placeholderText == "{...}" })
        assertEmpty(descriptors.filter { it.placeholderText == "//..." })
    }

    /** The five GoLand one-liners (error-handling `if`, single `return`, `case`, empty function, empty type), collapsed by default. */
    fun testOneLineRegions() {
        myFixture.testFoldingWithCollapseStatus("$testDataPath/OneLine.go")
    }

    fun testOneLinerReplacesBlockRegionOfSameRange() {
        myFixture.configureByText("a.go", "package p\n\nfunc f() error {\n\treturn nil\n}\n\ntype T struct {\n}\n")
        val descriptors = LanguageFolding.buildFoldingDescriptors(GoFoldingBuilder(), myFixture.file, myFixture.editor.document, false)
        assertEquals(listOf("{ return nil }", "{}"), descriptors.map { it.placeholderText })
    }

    fun testOneLineRegionsNotCollapsedWhenOptionsOff() {
        myFixture.configureByText(
            "a.go",
            "package p\n\ntype E struct {\n}\n\nfunc empty() {\n}\n\nfunc one() int {\n\treturn 1\n}\n\n" +
                "func f(err error, x int) error {\n\tif err != nil {\n\t\treturn err\n\t}\n\tswitch x {\n\tcase 1:\n\t\tx++\n\t}\n\treturn nil\n}\n",
        )
        val builder = GoFoldingBuilder()
        val byPlaceholder = LanguageFolding.buildFoldingDescriptors(builder, myFixture.file, myFixture.editor.document, false)
            .associateBy { it.placeholderText }
        val settings = GoFoldingSettings.getInstance()
        val options = listOf(settings::collapseEmptyTypes, settings::collapseEmptyFunctions, settings::collapseSingleReturnFunctions,
            settings::collapseErrorHandlingIf, settings::collapseCaseClauses)
        val saved = options.map { it.get() }
        try {
            // each option drives its own kind only: the empty function and the empty struct share the placeholder "{}"
            val regions = listOf("{ return 1 }" to settings::collapseSingleReturnFunctions, "{ return err }" to settings::collapseErrorHandlingIf,
                " x++" to settings::collapseCaseClauses)
            for ((placeholder, option) in regions) {
                val descriptor = byPlaceholder.getValue(placeholder)
                assertTrue(placeholder, builder.isCollapsedByDefault(descriptor))
                option.set(false)
                assertFalse(placeholder, builder.isCollapsedByDefault(descriptor))
                assertTrue(options.filter { it != option }.all { it.get() })
                option.set(true)
            }
            val empties = LanguageFolding.buildFoldingDescriptors(builder, myFixture.file, myFixture.editor.document, false).filter { it.placeholderText == "{}" }
            assertEquals(2, empties.size)
            assertTrue(empties.all { builder.isCollapsedByDefault(it) })
            settings.collapseEmptyTypes = false
            assertEquals(listOf(false, true), empties.map { builder.isCollapsedByDefault(it) })
            settings.collapseEmptyFunctions = false
            assertEquals(listOf(false, false), empties.map { builder.isCollapsedByDefault(it) })
            // the outer function body is a plain block region, never collapsed
            assertFalse(builder.isCollapsedByDefault(byPlaceholder.getValue("{...}")))
        } finally {
            options.zip(saved).forEach { (option, value) -> option.set(value) }
        }
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
