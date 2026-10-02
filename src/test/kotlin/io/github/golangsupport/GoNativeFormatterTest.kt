package io.github.golangsupport

import com.intellij.formatting.service.CoreFormattingService
import com.intellij.formatting.service.FormattingService
import com.intellij.formatting.service.FormattingServiceUtil
import com.intellij.lang.LanguageFormatting
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.format.GoFormatOnSave
import io.github.golangsupport.format.GoFormattingService
import io.github.golangsupport.ide.formatter.GoFormattingModelBuilder
import io.github.golangsupport.lang.GoFeature
import io.github.golangsupport.lang.GoFeatures
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.settings.GoFeatureSource
import io.github.golangsupport.settings.GoFormatter
import io.github.golangsupport.settings.GoSettings

/**
 * The Built-in formatter (MIGRATION.md step 8j): with [GoFormatter.NATIVE] the gofmt port of go-psi-ide formats through the platform
 * engine and no process runs; with a tool chosen the plugin's [GoFormattingService] claims the file first, and the engine, the last
 * service in line, never reaches the port. No real gofmt runs here: the tool branch is asserted through the service lookup only.
 */
class GoNativeFormatterTest : BasePlatformTestCase() {
    private val settings get() = GoSettings.getInstance()
    private var formatter = GoFormatter.GOFMT
    private var formatOnSave = true
    private var server = true

    override fun setUp() {
        super.setUp()
        formatter = settings.formatter
        formatOnSave = settings.formatOnSave
        server = settings.languageServerEnabled
    }

    override fun tearDown() {
        try {
            settings.formatter = formatter
            settings.formatOnSave = formatOnSave
            settings.languageServerEnabled = server
        } finally {
            super.tearDown()
        }
    }

    fun testTheFormatterOfThePortIsRegisteredForGo() {
        assertInstanceOf(LanguageFormatting.INSTANCE.forLanguage(GoLanguage), GoFormattingModelBuilder::class.java)
    }

    fun testBuiltInReformatsLikeGofmt() {
        settings.formatter = GoFormatter.NATIVE
        myFixture.configureByText("a.go", UNFORMATTED)
        WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformat(myFixture.file) }
        assertEquals(FORMATTED, myFixture.editor.document.text)
    }

    fun testBuiltInLeavesTheFileToTheEngine() {
        settings.formatter = GoFormatter.NATIVE
        myFixture.configureByText("a.go", UNFORMATTED)
        assertFalse(GoFormattingService().canFormat(myFixture.file))
        assertInstanceOf(wholeFileService(), CoreFormattingService::class.java)
    }

    fun testAToolClaimsTheFileBeforeTheEngine() {
        for (tool in listOf(GoFormatter.GOFMT, GoFormatter.GOIMPORTS, GoFormatter.GOLANGCI_LINT_FMT)) {
            settings.formatter = tool
            myFixture.configureByText("a.go", UNFORMATTED)
            assertTrue(tool.name, GoFormattingService().canFormat(myFixture.file))
            assertInstanceOf(wholeFileService(), GoFormattingService::class.java)
        }
    }

    fun testNoneLeavesTheFileToNobodyOfThePlugin() {
        settings.formatter = GoFormatter.NONE
        myFixture.configureByText("a.go", UNFORMATTED)
        assertFalse(GoFormattingService().canFormat(myFixture.file))
        assertFalse(GoFormatOnSave().isEnabledForProject(project))
    }

    fun testOnlyNoneLeavesFormattingToTheServer() {
        settings.languageServerEnabled = true
        for (choice in GoFormatter.entries) {
            settings.formatter = choice
            assertEquals(choice.name, choice == GoFormatter.NONE, settings.featureSource(GoFeature.FORMATTING) == GoFeatureSource.GOPLS)
            assertEquals(choice.name, choice != GoFormatter.NONE, GoFeatures.configuredNative(GoFeature.FORMATTING))
        }
    }

    fun testBuiltInFormatOnSaveRunsNoProcess() {
        settings.formatter = GoFormatter.NATIVE
        settings.formatOnSave = true
        myFixture.configureByText("a.go", UNFORMATTED)
        val onSave = GoFormatOnSave()
        assertTrue(onSave.isEnabledForProject(project))
        // the platform calls it on the EDT before the save; in a test the reformat processor runs synchronously
        onSave.processDocuments(project, arrayOf(myFixture.editor.document))
        assertEquals(FORMATTED, myFixture.editor.document.text)
    }

    fun testTheSettingsFileKeepsTheTitle() {
        assertEquals("Built-in", GoFormatter.NATIVE.toString())
        assertEquals(GoFormatter.NATIVE, GoFormatter.entries.last())
        assertFalse(GoFormatter.NATIVE.isExternalTool)
        assertFalse(GoFormatter.NONE.isExternalTool)
        assertTrue(GoFormatter.GOFMT.isExternalTool)
    }

    /** The service of an explicit Reformat Code over the whole file: the first in line that claims it, the engine last. */
    private fun wholeFileService(): FormattingService = FormattingServiceUtil.findService(myFixture.file, true, true)

    private companion object {
        const val UNFORMATTED = "package p\n\nimport \"fmt\"\n\nfunc f( ) {\na:=1\nfmt.Println( a )\n}\n"
        const val FORMATTED = "package p\n\nimport \"fmt\"\n\nfunc f() {\n\ta := 1\n\tfmt.Println(a)\n}\n"
    }
}
