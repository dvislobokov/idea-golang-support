package io.github.golangsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.mod.GoModChecks
import io.github.golangsupport.mod.GoModFile
import io.github.golangsupport.mod.GoModUnusedInspection
import io.github.golangsupport.mod.RemoveUnusedRequireFix
import io.github.golangsupport.settings.GoSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The matching of imports to requires, and the removal of a line or a block: pure, no platform. */
class GoModUnusedChecksTest {
    private fun unused(mod: String, vararg imports: String) = GoModChecks.unusedRequires(GoModFile.parse(mod).requires, imports.toSet(), GoModFile.parse(mod).tools).map { it.path }

    @Test fun importOfSubpackageUsesTheModule() = assertEquals(listOf("b.com/y"), unused("module m\nrequire (\n\ta.com/x v1.0.0\n\tb.com/y v1.0.0\n)", "a.com/x/pkg/deep"))
    @Test fun importEqualToModulePath() = assertEquals(emptyList<String>(), unused("module m\nrequire a.com/x v1.0.0", "a.com/x"))
    @Test fun prefixOfNameIsNotAMatch() = assertEquals(listOf("a.com/x"), unused("module m\nrequire a.com/x v1.0.0", "a.com/xy/z"))
    @Test fun longestModuleWins() = assertEquals(listOf("a.com/x"), unused("module m\nrequire a.com/x v1.0.0\nrequire a.com/x/y v1.0.0", "a.com/x/y/z"))
    @Test fun indirectIsNeverReported() = assertEquals(emptyList<String>(), unused("module m\nrequire a.com/x v1.0.0 // indirect"))
    @Test fun toolDirectiveCountsAsImport() = assertEquals(emptyList<String>(), unused("module m\ngo 1.24\nrequire a.com/x v1.0.0\ntool a.com/x/cmd/gen"))
    @Test fun majorVersionSuffixIsPartOfThePath() = assertEquals(emptyList<String>(), unused("module m\nrequire a.com/x/v2 v2.0.0", "a.com/x/v2/sub"))

    @Test fun removesLine() {
        val text = "module m\n\nrequire a.com/x v1.0.0\nrequire b.com/y v1.0.0\n"
        val r = RemoveUnusedRequireFix.removalRange(text, 2, "a.com/x")!!
        assertEquals("module m\n\nrequire b.com/y v1.0.0\n", text.removeRange(r.first, r.last))
    }

    @Test fun removesWholeBlockWhenLastLine() {
        val text = "module m\n\nrequire (\n\ta.com/x v1.0.0\n)\n\ngo 1.22\n"
        val r = RemoveUnusedRequireFix.removalRange(text, 3, "a.com/x")!!
        assertEquals("module m\n\n\ngo 1.22\n", text.removeRange(r.first, r.last))
    }

    @Test fun keepsBlockWithOtherLines() {
        val text = "require (\n\ta.com/x v1.0.0\n\tb.com/y v1.0.0\n)\n"
        val r = RemoveUnusedRequireFix.removalRange(text, 1, "a.com/x")!!
        assertEquals("require (\n\tb.com/y v1.0.0\n)\n", text.removeRange(r.first, r.last))
    }

    @Test fun staleLineIsRefused() = assertNull(RemoveUnusedRequireFix.removalRange("module m\n", 5, "a.com/x"))
}

class GoModUnusedFixtureTest : BasePlatformTestCase() {
    private val settings get() = GoSettings.getInstance()
    private var server = true

    override fun setUp() {
        super.setUp()
        server = settings.languageServerEnabled
        settings.languageServerEnabled = false
    }

    override fun tearDown() {
        try { settings.languageServerEnabled = server } finally { super.tearDown() }
    }

    fun testUnusedRequireIsReportedAndRemoved() {
        myFixture.enableInspections(GoModUnusedInspection::class.java)
        myFixture.addFileToProject("main.go", "package main\n\nimport \"a.com/x/sub\"\n\nfunc main() { sub.F() }\n")
        myFixture.addFileToProject("nested/go.mod", "module n\n")
        myFixture.addFileToProject("nested/n.go", "package n\n\nimport _ \"c.com/z\"\n")
        myFixture.configureByText("go.mod", "module m\n\nrequire (\n\ta.com/x v1.0.0\n\t<warning descr=\"'b.com/y' is required but no package of it is imported\">b.com/y</warning> v1.0.0\n\tc.com/z v1.0.0 // indirect\n)\n")
        myFixture.checkHighlighting()
        myFixture.launchAction(myFixture.getAllQuickFixes().first { it.text == "Remove unused require" })
        myFixture.checkResult("module m\n\nrequire (\n\ta.com/x v1.0.0\n\tc.com/z v1.0.0 // indirect\n)\n")
    }

    fun testImportInNestedModuleDoesNotCount() {
        myFixture.enableInspections(GoModUnusedInspection::class.java)
        myFixture.addFileToProject("nested/go.mod", "module n\n")
        myFixture.addFileToProject("nested/n.go", "package n\n\nimport _ \"c.com/z\"\n")
        myFixture.configureByText("go.mod", "module m\n\nrequire <warning descr=\"'c.com/z' is required but no package of it is imported\">c.com/z</warning> v1.0.0\n")
        myFixture.checkHighlighting()
    }
}

class GoPlatformChoicesTest {
    @org.junit.Test fun textWithAndWithoutTags() {
        assertEquals("linux/amd64", io.github.golangsupport.settings.GoPlatformChoices.text("linux", "amd64", emptyList()))
        assertEquals("js/wasm · a,b", io.github.golangsupport.settings.GoPlatformChoices.text("js", "wasm", listOf("a", "b")))
    }

    @org.junit.Test fun commonPairsComeFirstAndAreValid() {
        val ordered = io.github.golangsupport.settings.GoPlatformChoices.ordered()
        assertEquals(io.github.golangsupport.settings.GoPlatformChoices.COMMON_PAIRS, ordered.take(6))
        assertEquals(io.github.golangsupport.settings.GoPlatformChoices.ALL_PAIRS.toSet(), ordered.toSet())
        assertEquals(ordered.size, ordered.toSet().size)
    }

    @org.junit.Test fun split() {
        assertEquals("wasip1" to "wasm", io.github.golangsupport.settings.GoPlatformChoices.split("wasip1/wasm"))
        assertNull(io.github.golangsupport.settings.GoPlatformChoices.split("Host default"))
    }
}
