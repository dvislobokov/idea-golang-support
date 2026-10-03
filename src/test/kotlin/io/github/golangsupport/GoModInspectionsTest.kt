package io.github.golangsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.mod.GoDirState
import io.github.golangsupport.mod.GoModChecks
import io.github.golangsupport.mod.GoModEnvironment
import io.github.golangsupport.mod.GoModPathsInspection
import io.github.golangsupport.mod.GoModRequiresInspection
import io.github.golangsupport.mod.GoModRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure checks of go.mod / go.work: every rule, positive and negative. */
class GoModChecksTest {
    private fun check(text: String, work: Boolean = false, vendor: String? = null, dirs: Map<String, GoDirState> = emptyMap()) =
        GoModChecks.check(text, work, GoModEnvironment({ dirs[it] ?: GoDirState.MISSING }, vendor))

    @Test fun replaceToMissingDirectory() {
        val p = check("module m\nreplace example.com/x => ../x\nreplace (\n\texample.com/y v1.0.0 => ./y\n)").filter { it.rule == GoModRule.REPLACE_PATH }
        assertEquals(listOf(1, 3), p.map { it.line })
        assertEquals("../x", p[0].needle)
        assertTrue(p[0].error)
        assertTrue(p[0].message.contains("does not exist"))
    }

    @Test fun replaceToDirectoryWithoutGoMod() {
        val p = check("module m\nreplace example.com/x => ../x", dirs = mapOf("../x" to GoDirState.NO_GO_MOD))
        assertEquals(listOf("Replacement directory '../x' has no go.mod"), p.map { it.message })
    }

    @Test fun replaceOk() {
        assertTrue(check("module m\nreplace example.com/x => ../x\nreplace example.com/z => example.com/w v1.0.0", dirs = mapOf("../x" to GoDirState.OK)).isEmpty())
    }

    @Test fun localPaths() {
        assertTrue(listOf("./x", "../x", "/abs", "C:\\x", "C:/x", ".", "..").all(GoModChecks::isLocalPath))
        assertFalse(listOf("example.com/x", "x", "..x").any(GoModChecks::isLocalPath))
    }

    @Test fun duplicateRequireAcrossBlocks() {
        val p = check("module m\nrequire a.com/x v1.0.0\nrequire (\n\tb.com/y v1.0.0\n\ta.com/x v1.2.0\n)")
        assertEquals(listOf(4), p.filter { it.rule == GoModRule.DUPLICATE_REQUIRE }.map { it.line })
        assertEquals("a.com/x", p[0].duplicateOf)
        assertFalse(p[0].error)
    }

    @Test fun noDuplicates() = assertTrue(check("module m\nrequire (\n\ta.com/x v1.0.0\n\tb.com/y v1.0.0\n)").isEmpty())

    @Test fun selfReference() {
        val p = check("module a.com/m\nrequire a.com/m v1.0.0\nreplace a.com/m => ./m")
        assertEquals(listOf(1, 2), p.filter { it.rule == GoModRule.SELF_REFERENCE }.map { it.line })
        assertTrue(check("module a.com/m\nrequire a.com/other v1.0.0").none { it.rule == GoModRule.SELF_REFERENCE })
    }

    @Test fun malformedGoVersion() {
        for (bad in listOf("1", "1.x", "v1.21", "1.21.", "2.0")) assertEquals(bad, 1, check("module m\ngo $bad").count { it.rule == GoModRule.GO_VERSION })
        for (good in listOf("1.21", "1.21.3", "1.21rc1", "1.22.0beta2")) assertEquals(good, 0, check("module m\ngo $good").size)
        assertEquals(1, check("module m\ngo 1.x")[0].line)
    }

    @Test fun toolchainOlderThanGo() {
        assertEquals(1, check("module m\ngo 1.22.3\ntoolchain go1.22.1").count { it.rule == GoModRule.TOOLCHAIN })
        assertEquals(1, check("module m\ngo 1.21.0\ntoolchain go1.21rc1").count { it.rule == GoModRule.TOOLCHAIN })
        assertEquals(0, check("module m\ngo 1.21\ntoolchain go1.21rc1").size)
        assertEquals(0, check("module m\ngo 1.22\ntoolchain go1.22.0").size)
        assertEquals(0, check("module m\ngo 1.22\ntoolchain default").size)
    }

    @Test fun versionOrder() {
        assertTrue(GoModChecks.compareVersions("1.21", "1.21rc1") < 0)
        assertTrue(GoModChecks.compareVersions("1.21rc1", "1.21.0") < 0)
        assertTrue(GoModChecks.compareVersions("1.9", "1.10") < 0)
        assertEquals(0, GoModChecks.compareVersions("1.22.1", "1.22.1"))
    }

    private val mod = "module m\ngo 1.22\nrequire (\n\ta.com/x v1.0.0\n\tb.com/y v2.0.0 // indirect\n)"

    @Test fun vendorInSync() {
        val v = "# a.com/x v1.0.0\n## explicit; go 1.20\na.com/x\n# b.com/y v2.0.0\n## explicit\nb.com/y\n"
        assertTrue(check(mod, vendor = v).isEmpty())
    }

    @Test fun vendorMissingAndDifferent() {
        val p = check(mod, vendor = "# a.com/x v0.9.0\n## explicit\na.com/x\n")
        assertEquals(listOf(3, 4), p.map { it.line })
        assertTrue(p[0].message.contains("vendored at v0.9.0") && p[0].message.contains("go mod vendor"))
        assertTrue(p[1].message.contains("missing"))
    }

    @Test fun vendorNotExplicit() {
        val v = "# a.com/x v1.0.0\na.com/x\n# b.com/y v2.0.0\n## explicit\nb.com/y\n"
        assertEquals(listOf(3), check(mod, vendor = v).map { it.line })
        assertTrue(check(mod.replace("go 1.22", "go 1.13"), vendor = v).isEmpty())
    }

    @Test fun noVendorDirectoryNoCheck() = assertTrue(check(mod, vendor = null).isEmpty())

    @Test fun workUseDirectories() {
        val p = check("go 1.22\nuse (\n\t./a\n\t./b\n\t./c\n)", work = true, dirs = mapOf("./a" to GoDirState.OK, "./b" to GoDirState.NO_GO_MOD))
        assertEquals(listOf(3, 4), p.map { it.line })
        assertTrue(p.all { it.error && it.rule == GoModRule.USE_DIRECTORY })
        assertTrue(p[0].message.contains("no go.mod") && p[1].message.contains("does not exist"))
    }

    @Test fun useIgnoredInGoMod() = assertTrue(check("module m\nuse ./a").isEmpty())
}

/** Highlighting and the duplicate-require fix on a real go.mod in the light fixture. */
class GoModInspectionsFixtureTest : BasePlatformTestCase() {
    private val settings get() = io.github.golangsupport.settings.GoSettings.getInstance()
    private var server = true

    // an open go.mod starts gopls, where it is installed, and the server refreshes the daemon in the middle of checkHighlighting
    override fun setUp() {
        super.setUp()
        server = settings.languageServerEnabled
        settings.languageServerEnabled = false
    }

    override fun tearDown() {
        try { settings.languageServerEnabled = server } finally { super.tearDown() }
    }

    fun testDuplicateRequireIsReportedAndRemoved() {
        myFixture.enableInspections(GoModRequiresInspection::class.java)
        myFixture.configureByText("go.mod", "module m\n\ngo 1.22\n\nrequire a.com/x v1.0.0\nrequire <warning descr=\"Duplicate require of 'a.com/x'\">a.com/x</warning> v1.1.0\n")
        myFixture.checkHighlighting()
        myFixture.launchAction(myFixture.getAllQuickFixes().first { it.text == "Remove duplicate require" })
        myFixture.checkResult("module m\n\ngo 1.22\n\nrequire a.com/x v1.0.0\n")
    }

    fun testMissingReplaceDirectoryIsAnError() {
        myFixture.enableInspections(GoModPathsInspection::class.java)
        myFixture.addFileToProject("ok/readme.txt", "no module here")
        myFixture.configureByText("go.mod", "module m\n\nreplace a.com/x => <error descr=\"Replacement directory './nope' does not exist\">./nope</error>\nreplace a.com/y => <error descr=\"Replacement directory './ok' has no go.mod\">./ok</error>\n")
        myFixture.checkHighlighting()
    }
}
