package io.github.golangsupport.ide.imports

import com.intellij.codeInsight.intention.IntentionActionDelegate
import com.intellij.codeInspection.HintAction
import com.intellij.codeInspection.ex.QuickFixWrapper
import io.github.golangsupport.ide.GoIdeOptions
import io.github.golangsupport.ide.GoImportExclusions
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.ide.inspections.GoUnresolvedReferenceInspection
import io.github.golangsupport.ide.inspections.GoUnusedImportInspection

/** PLAN.md G8: unambiguous imports and optimize imports on the fly, the import popup, the excluded paths. */
class GoAutoImportTest : GoSemanticIdeTestBase() {
    private val options get() = GoIdeOptions.getInstance()

    override fun tearDown() {
        try {
            options.importUnambiguousOnTheFly = true
            options.importOptimizeOnTheFly = false
            options.importShowPopup = true
            options.importExcluded = emptyList()
        } finally {
            super.tearDown()
        }
    }

    private fun configure(text: String) {
        myFixture.enableInspections(GoUnresolvedReferenceInspection(), GoUnusedImportInspection())
        myFixture.configureByText("a.go", text)
    }

    private fun importAt(marker: String, allowCaretNear: Boolean = false): Boolean {
        val offset = myFixture.file.text.indexOf(marker)
        val action = GoReferenceImporter().computeAutoImportAtOffset(myFixture.editor, myFixture.file, offset, allowCaretNear) ?: return false
        return action.asBoolean
    }

    fun testUnambiguousPackageIsImported() {
        configure("package a\n\nfunc f() string {\n\treturn strings.ToUpper(\"x\")\n}\n<caret>")
        assertTrue(GoReferenceImporter().isAddUnambiguousImportsOnTheFlyEnabled(myFixture.file))
        assertTrue(importAt("strings."))
        myFixture.checkResult("package a\n\nimport \"strings\"\n\nfunc f() string {\n\treturn strings.ToUpper(\"x\")\n}\n")
    }

    fun testAmbiguousPackageIsNotImported() {
        configure("package a\n\nfunc f() int {\n\treturn rand.Int()\n}\n<caret>")
        assertFalse(importAt("rand."))
    }

    fun testNothingWhileTheCaretIsOnTheName() {
        configure("package a\n\nfunc f() string {\n\treturn strings<caret>.ToUpper(\"x\")\n}\n")
        assertFalse(importAt("strings."))
        assertTrue("the platform may allow it (Auto-import at the caret)", importAt("strings.", allowCaretNear = true))
    }

    fun testSwitchedOff() {
        options.importUnambiguousOnTheFly = false
        configure("package a\n")
        assertFalse(GoReferenceImporter().isAddUnambiguousImportsOnTheFlyEnabled(myFixture.file))
    }

    fun testExcludedPackageIsNeitherImportedNorOffered() {
        options.importExcluded = listOf("strings")
        configure("package a\n\nfunc f() string {\n\treturn <caret>strings.ToUpper(\"x\")\n}\n")
        assertEmpty(myFixture.availableIntentions.map { it.text }.filter { it.startsWith("Import ") })
        assertFalse(importAt("strings.", allowCaretNear = true))
    }

    fun testTheImportFixIsTheHintOfThePopupAndRespectsTheSwitch() {
        configure("package a\n\nfunc f() string {\n\treturn <caret>strings.ToUpper(\"x\")\n}\n")
        val intention = myFixture.findSingleIntention("Import \"strings\"")
        val fix = IntentionActionDelegate.unwrap(intention).let { QuickFixWrapper.unwrap(it) ?: it }
        assertTrue(fix.javaClass.name, fix is HintAction)
        options.importShowPopup = false
        assertFalse((fix as HintAction).showHint(myFixture.editor))
    }

    fun testExclusionPatterns() {
        val patterns = listOf("github.com/foo/...", "example.com/bar/*", "golang.org/x/exp", "corp.io/int*")
        assertTrue(GoImportExclusions.excluded("github.com/foo", patterns))
        assertTrue(GoImportExclusions.excluded("github.com/foo/sub/pkg", patterns))
        assertFalse(GoImportExclusions.excluded("github.com/foobar", patterns))
        assertTrue(GoImportExclusions.excluded("example.com/bar/x", patterns))
        assertTrue(GoImportExclusions.excluded("golang.org/x/exp", patterns))
        assertFalse(GoImportExclusions.excluded("golang.org/x/exp/slices", patterns))
        assertTrue(GoImportExclusions.excluded("corp.io/internal/x", patterns))
        assertFalse(GoImportExclusions.excluded("fmt", listOf("", " ")))
    }

    fun testUnusedImportsAreRemovedOnTheFly() {
        options.importOptimizeOnTheFly = true
        configure("package a\n\nimport (\n\t\"fmt\"\n\t\"os\"\n)\n\nfunc f() {\n\tfmt.Println()<caret>\n}\n")
        myFixture.doHighlighting()
        assertTrue(GoOptimizeImportsOnTheFly.readyToOptimize(project, myFixture.editor))
        GoOptimizeImportsOnTheFly.optimize(project, myFixture.editor)
        myFixture.checkResult("package a\n\nimport (\n\t\"fmt\"\n)\n\nfunc f() {\n\tfmt.Println()\n}\n")
    }

    fun testNotOnTheFlyWhileTheFileHasErrorsOrTheOptionIsOff() {
        options.importOptimizeOnTheFly = true
        configure("package a\n\nimport (\n\t\"fmt\"\n\t\"os\"\n)\n\nfunc f() {\n\tfmt.Println(undefinedName)<caret>\n}\n")
        myFixture.doHighlighting()
        assertFalse(GoOptimizeImportsOnTheFly.readyToOptimize(project, myFixture.editor))
        options.importOptimizeOnTheFly = false
        configure("package a\n\nimport \"os\"\n\nfunc f() {<caret>}\n")
        myFixture.doHighlighting()
        assertFalse(GoOptimizeImportsOnTheFly.readyToOptimize(project, myFixture.editor))
    }

    fun testNotOnTheFlyWithTheCaretInTheImports() {
        options.importOptimizeOnTheFly = true
        configure("package a\n\nimport (\n\t\"fmt\"\n\t\"os<caret>\"\n)\n\nfunc f() {\n\tfmt.Println()\n}\n")
        myFixture.doHighlighting()
        assertFalse(GoOptimizeImportsOnTheFly.readyToOptimize(project, myFixture.editor))
    }
}
