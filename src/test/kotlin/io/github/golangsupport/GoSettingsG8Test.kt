package io.github.golangsupport

import com.intellij.codeInsight.actions.onSave.OptimizeImportsOnSaveOptions
import com.intellij.ide.actionsOnSave.ActionOnSaveInfoProvider
import com.intellij.openapi.editor.Document
import com.intellij.xdebugger.settings.DebuggerSettingsCategory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.debugger.GoDebuggerSettings
import io.github.golangsupport.debugger.GoIntegerFormat
import io.github.golangsupport.lang.GoOptimizeImportsOnSave
import io.github.golangsupport.lang.GoOptimizeImportsOnSaveInfoProvider
import io.github.golangsupport.mod.GoModDownloadChoice
import io.github.golangsupport.mod.GoModDownloads
import io.github.golangsupport.settings.GoSettings
import io.github.golangsupport.settings.GoVendoring
import java.nio.file.Files

/** GoLand parity G8: Optimize imports on save, the Go Modules settings in the go commands and in the project, Data Views | Go. */
class GoSettingsG8Test : BasePlatformTestCase() {
    private val settings get() = GoSettings.getInstance()
    private var optimize = false
    private var vendoring = GoVendoring.AUTO
    private var environment = ""
    private var download = true

    override fun setUp() {
        super.setUp()
        optimize = settings.optimizeImportsOnSave
        vendoring = settings.vendoring
        environment = settings.modulesEnvironment
        download = settings.downloadDependencies
    }

    override fun tearDown() {
        try {
            settings.optimizeImportsOnSave = optimize
            settings.vendoring = vendoring
            settings.modulesEnvironment = environment
            GoModDownloads.set(project, GoModDownloadChoice.of(download, false))
            OptimizeImportsOnSaveOptions.getInstance(project).setRunOnSaveEnabled(false)
        } finally {
            super.tearDown()
        }
    }

    private fun goDocument(text: String): Document {
        myFixture.configureByText("main.go", text)
        return myFixture.editor.document
    }

    fun testOptimizeImportsOnSaveRemovesTheUnusedOnes() {
        val document = goDocument("package main\n\nimport (\n\t\"os\"\n\t\"fmt\"\n)\n\nfunc main() { fmt.Println() }\n")
        val action = GoOptimizeImportsOnSave()
        assertFalse("off by default", action.isEnabledForProject(project))
        settings.optimizeImportsOnSave = true
        assertTrue(action.isEnabledForProject(project))
        action.processDocuments(project, arrayOf(document))
        assertFalse(document.text, document.text.contains("\"os\""))
        assertTrue(document.text.contains("\"fmt\""))
    }

    fun testThePlatformOptimizeImportsOnSaveIsLeftToItself() {
        settings.optimizeImportsOnSave = true
        OptimizeImportsOnSaveOptions.getInstance(project).setRunOnSaveEnabled(true)
        // all file types by default: the platform's action already optimizes the Go files of the project
        assertTrue(GoOptimizeImportsOnSave.platformCovers(project))
        assertFalse(GoOptimizeImportsOnSave().isEnabledForProject(project))
    }

    fun testTheRowOfActionsOnSaveIsRegistered() {
        assertTrue(ActionOnSaveInfoProvider.EP_NAME.extensionList.any { it is GoOptimizeImportsOnSaveInfoProvider })
    }

    fun testTheGoCommandsGetTheEnvironmentAndTheVendorFlag() {
        val root = Files.createTempDirectory("go-g8").toFile()
        try {
            root.resolve("go.mod").writeText("module example.com/app\n\ngo 1.22\n")
            root.resolve("vendor").mkdirs()
            root.resolve("vendor/modules.txt").writeText("")
            settings.modulesEnvironment = "GOPROXY=off"
            settings.vendoring = GoVendoring.NEVER
            val environment = GoCli.buildEnvironment("build", root.path)
            assertEquals("off", environment["GOPROXY"])
            if (System.getenv("GOFLAGS").isNullOrBlank()) assertEquals("-mod=mod", environment["GOFLAGS"])
            // go env shows the defaults: nothing of the settings
            assertEquals(emptyMap<String, String>(), GoCli.buildEnvironment("env", root.path))
            settings.vendoring = GoVendoring.AUTO
            assertNull(GoCli.buildEnvironment("build", root.path)["GOFLAGS"].takeIf { System.getenv("GOFLAGS").isNullOrBlank() })
        } finally {
            root.deleteRecursively()
        }
    }

    fun testTheDownloadChoiceKeepsTheProjectAsTheException() {
        GoModDownloads.set(project, GoModDownloadChoice.ONLY_THIS)
        assertFalse(settings.downloadDependencies)
        assertEquals(GoModDownloadChoice.ONLY_THIS, GoModDownloads.choice(project))
        assertTrue(GoModDownloads.isEnabled(project))
        GoModDownloads.set(project, GoModDownloadChoice.ALL_BUT_THIS)
        assertTrue(settings.downloadDependencies)
        assertFalse(GoModDownloads.isEnabled(project))
        GoModDownloads.set(project, GoModDownloadChoice.ALL)
        assertTrue(GoModDownloads.isEnabled(project))
    }

    fun testDataViewsHaveAGoTab() {
        // found among the registered xdebugger.settings
        val debuggerSettings = GoDebuggerSettings.getInstance()
        assertEquals("go", debuggerSettings.id)
        val tabs = debuggerSettings.createConfigurables(DebuggerSettingsCategory.DATA_VIEWS)
        assertEquals(listOf("Go"), tabs.map { it.displayName })
        assertTrue(debuggerSettings.createConfigurables(DebuggerSettingsCategory.STEPPING).isEmpty())
        // GoLand's defaults
        val defaults = GoDebuggerSettings.State()
        assertEquals(GoIntegerFormat.DECIMAL, defaults.integerFormat)
        assertTrue(defaults.showPointerAddresses)
        assertFalse(defaults.stringView)
    }
}
