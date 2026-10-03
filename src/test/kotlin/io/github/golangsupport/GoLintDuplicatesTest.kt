package io.github.golangsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.ide.inspections.GoUnusedVariableInspection
import io.github.golangsupport.ide.inspections.lint.GoUncheckedErrorInspection
import io.github.golangsupport.lint.GoLintDuplicates
import io.github.golangsupport.settings.GoFeatureSource
import io.github.golangsupport.settings.GoSettings

/**
 * The Unchecked error inspection runs with Language features = gopls (gopls has no errcheck), and the errcheck findings of golangci-lint
 * on the calls it reports are dropped while it is on.
 */
class GoLintDuplicatesTest : BasePlatformTestCase() {
    private val settings get() = GoSettings.getInstance()
    private var server = true
    private var source = GoFeatureSource.GOPLS

    override fun setUp() {
        super.setUp()
        server = settings.languageServerEnabled
        source = settings.languageFeaturesSource
        settings.languageServerEnabled = true
        settings.languageFeaturesSource = GoFeatureSource.GOPLS
    }

    override fun tearDown() {
        try {
            settings.languageServerEnabled = server
            settings.languageFeaturesSource = source
        } finally {
            super.tearDown()
        }
    }

    private val text = """
        package ue9k

        func ue9Fail() error { return nil }

        func ue9Host() {
        	<warning descr="Error return value of `ue9Fail` is not checked">ue9Fail()</warning>
        	defer ue9Fail()
        }
    """.trimIndent() + "\n"

    fun testReportedWhileTypingInGoplsMode() {
        myFixture.enableInspections(GoUncheckedErrorInspection())
        myFixture.configureByText("ue9host.go", text)
        myFixture.checkHighlighting(true, false, true)
    }

    fun testErrcheckFindingsAtReportedCallsAreDropped() {
        myFixture.enableInspections(GoUncheckedErrorInspection())
        myFixture.configureByText("ue9host2.go", text.replace(Regex("</?warning[^>]*>"), ""))
        val content = myFixture.file.text
        val call = content.indexOf("\tue9Fail()") + 1
        val deferred = content.indexOf("ue9Fail()", content.indexOf("defer"))
        assertTrue(GoLintDuplicates.reportedNatively(myFixture.file, call, "errcheck"))
        // the inspection leaves `defer` to golangci-lint, so its finding stays
        assertFalse(GoLintDuplicates.reportedNatively(myFixture.file, deferred, "errcheck"))
        assertFalse(GoLintDuplicates.reportedNatively(myFixture.file, call, "govet"))
    }

    /** The families other than errcheck are matched by a highlight of their inspection over the range of the finding. */
    fun testUnusedFindingsUnderANativeHighlightAreDropped() {
        // no language server: its inlay refresh restarts the daemon in the middle of doHighlighting
        settings.languageServerEnabled = false
        settings.languageFeaturesSource = GoFeatureSource.NATIVE
        myFixture.enableInspections(GoUnusedVariableInspection())
        myFixture.configureByText("uv9host.go", "package uv9k\n\nfunc uv9Host() {\n\tuv9x := 1\n\tuv9y := 2\n\t_ = uv9y\n}\n")
        val highlights = myFixture.doHighlighting()
        val content = myFixture.file.text
        val unused = content.indexOf("uv9x")
        val used = content.indexOf("uv9y")
        assertTrue(highlights.toString(), highlights.any { it.inspectionToolId == "GoUnusedVariable" })
        assertTrue(GoLintDuplicates.reportedNatively(myFixture.file, unused, "unused", "var uv9x is unused", unused + 4))
        assertFalse(GoLintDuplicates.reportedNatively(myFixture.file, used, "unused", "var uv9y is unused", used + 4))
        // another family at the same place stays
        assertFalse(GoLintDuplicates.reportedNatively(myFixture.file, unused, "mylinter", "uv9x", unused + 4))
    }

    fun testErrcheckFindingsStayWhenTheInspectionIsOff() {
        myFixture.configureByText("ue9host3.go", text.replace(Regex("</?warning[^>]*>"), ""))
        val call = myFixture.file.text.indexOf("\tue9Fail()") + 1
        assertFalse(GoLintDuplicates.reportedNatively(myFixture.file, call, "errcheck"))
    }
}
