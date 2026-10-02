package io.github.golangsupport.ide.inspections

import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.codeInspection.LocalInspectionTool
import io.github.golangsupport.ide.GoSemanticIdeTestBase

/** Quick fixes of the diagnostics inspections and Optimize Imports over `testData/inspections/fixes`. */
class GoQuickFixesTest : GoSemanticIdeTestBase() {
    override val testDataSubdir: String = "inspections/fixes"

    private fun doTest(name: String, fixText: String, vararg tools: LocalInspectionTool) {
        myFixture.enableInspections(*tools)
        myFixture.configureByFile("$name.go")
        myFixture.launchAction(myFixture.findSingleIntention(fixText))
        myFixture.checkResultByFile("${name}_after.go")
    }

    fun testAddImport() = doTest("addImport", "Import \"strings\"", GoUnresolvedReferenceInspection())

    fun testAddImportInTypePosition() = doTest("addImportType", "Import \"bytes\"", GoUnresolvedReferenceInspection())

    fun testAddImportOffersEveryPackageWithTheName() {
        myFixture.enableInspections(GoUnresolvedReferenceInspection())
        myFixture.configureByText("a.go", "package a\n\nfunc f() {\n\t_ = <caret>rand.Int()\n}\n")
        val names = myFixture.availableIntentions.map { it.text }.filter { it.startsWith("Import ") }
        assertTrue(names.toString(), names.containsAll(listOf("Import \"crypto/rand\"", "Import \"math/rand\"")))
    }

    fun testNoAddImportWhenThePackageLacksTheMember() {
        myFixture.enableInspections(GoUnresolvedReferenceInspection())
        myFixture.configureByText("a.go", "package a\n\nfunc f() {\n\t_ = <caret>strings.NoSuchFunction()\n}\n")
        assertEmpty(myFixture.availableIntentions.map { it.text }.filter { it.startsWith("Import ") })
    }

    fun testRemoveImport() = doTest("removeImport", "Remove unused import", GoUnusedImportInspection())

    fun testRemoveSingleImportDeclaration() = doTest("removeImportSingle", "Remove unused import", GoUnusedImportInspection())

    fun testOptimizeImportsFix() {
        myFixture.enableInspections(GoUnusedImportInspection())
        myFixture.configureByFile("optimizeImports.go")
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("\"os\"") + 1)
        myFixture.launchAction(myFixture.findSingleIntention("Optimize imports"))
        myFixture.checkResultByFile("optimizeImports_after.go")
    }

    fun testOptimizeImportsAction() {
        myFixture.configureByFile("optimizeImports.go")
        OptimizeImportsProcessor(project, myFixture.file).run()
        myFixture.checkResultByFile("optimizeImports_after.go")
    }

    fun testRemoveUnusedVariable() = doTest("removeVariable", "Remove variable 'x'", GoUnusedVariableInspection())

    fun testReplaceUnusedVariableWithBlankAssignment() = doTest("blankAssign", "Replace 'x' with '_ ='", GoUnusedVariableInspection())

    fun testRenameUnusedVariableToBlank() = doTest("renameBlank", "Rename 'b' to '_'", GoUnusedVariableInspection())

    fun testWrapInConversion() = doTest("wrapConversion", "Convert to 'Celsius'", GoTypeMismatchInspection())

    fun testWrapInConversionContexts() {
        myFixture.enableInspections(GoTypeMismatchInspection())
        myFixture.configureByFile("wrapConversion.go")
        val text = myFixture.file.text
        // Argument of another package's type: qualified with the import name.
        myFixture.editor.caretModel.moveToOffset(text.indexOf("takes(c, i)") + "takes(c, ".length)
        assertNotNull(myFixture.getAvailableIntention("Convert to 'time.Duration'"))
        // Function result.
        myFixture.editor.caretModel.moveToOffset(text.indexOf("return i") + "return ".length)
        assertNotNull(myFixture.getAvailableIntention("Convert to 'float64'"))
    }

    fun testNoConversionForUntypedConstantsOrIntToString() {
        myFixture.enableInspections(GoTypeMismatchInspection())
        myFixture.configureByText("a.go", "package a\n\nfunc f(i int) {\n\tvar s string = <caret>i\n\tvar t string = 1\n\t_, _ = s, t\n}\n")
        assertEmpty(myFixture.availableIntentions.map { it.text }.filter { it.startsWith("Convert to") })
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("= 1") + 2)
        assertEmpty(myFixture.availableIntentions.map { it.text }.filter { it.startsWith("Convert to") })
    }
}
