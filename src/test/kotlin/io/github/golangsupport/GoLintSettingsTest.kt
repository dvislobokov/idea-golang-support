package io.github.golangsupport

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.lint.GoCustomLintAnnotator
import io.github.golangsupport.lint.GoLintAnnotator
import io.github.golangsupport.settings.GoCustomLinter
import io.github.golangsupport.settings.GoFormatter
import io.github.golangsupport.settings.GoLinterDirectory
import io.github.golangsupport.settings.GoLinterTrigger
import io.github.golangsupport.settings.GoSettings
import java.io.File

/** golangci-lint is optional and off by default; custom linters run only from their table. The annotators are asked on a file of the local disk, as in the IDE. */
class GoLintSettingsTest : BasePlatformTestCase() {
    private val settings get() = GoSettings.getInstance()
    private var golangci = false
    private var formatter = GoFormatter.GOFMT
    private var linters = emptyList<GoCustomLinter>()
    private lateinit var directory: File

    override fun setUp() {
        super.setUp()
        golangci = settings.golangciLint
        formatter = settings.state.formatter
        linters = settings.customLinters
        directory = FileUtil.createTempDirectory("lint", null)
    }

    override fun tearDown() {
        try {
            settings.golangciLint = golangci
            settings.state.formatter = formatter
            settings.customLinters = linters
            settings.setToolPath("golangci-lint", "")
            FileUtil.delete(directory)
        } finally {
            super.tearDown()
        }
    }

    private fun goFile(): PsiFile {
        val io = File(directory, "store/order.go").apply { parentFile.mkdirs(); writeText("package store\n") }
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(io)!!
        return PsiManager.getInstance(project).findFile(virtualFile)!!
    }

    fun testOffByDefault() {
        assertFalse(GoSettings.Settings().golangciLint)
        assertEquals(90, GoSettings.Settings().lintTimeoutSeconds)
    }

    fun testTheGolangciAnnotatorDoesNotRunWhenOff() {
        val file = goFile()
        // an "installed" golangci-lint: any file will do, nothing is run by collectInformation
        settings.setToolPath("golangci-lint", file.virtualFile.path)
        settings.golangciLint = false
        assertNull(GoLintAnnotator().collectInformation(file))
        settings.golangciLint = true
        assertNotNull(GoLintAnnotator().collectInformation(file))
    }

    fun testGolangciFmtFallsBackToGofmtWhenOff() {
        settings.formatter = GoFormatter.GOLANGCI_LINT_FMT
        settings.golangciLint = false
        assertEquals(GoFormatter.GOFMT, settings.formatter)
        settings.golangciLint = true
        assertEquals(GoFormatter.GOLANGCI_LINT_FMT, settings.formatter)
    }

    fun testCustomLintersRunFromTheTableWithTheMacrosExpanded() {
        val file = goFile()
        settings.customLinters = emptyList()
        assertNull(GoCustomLintAnnotator().collectInformation(file))
        settings.customLinters = listOf(GoCustomLinter("probe", "probe -json \$FilePath\$ \$Package\$", directory = GoLinterDirectory.FILE_DIRECTORY))
        val request = GoCustomLintAnnotator().collectInformation(file)!!
        val path = FileUtil.toSystemDependentName(file.virtualFile.path)
        assertEquals(listOf("probe", "-json", path, "."), request.runs.single().arguments)
        assertEquals(File(path).parent, request.runs.single().workDirectory)
        settings.customLinters = listOf(GoCustomLinter("probe", "probe", enabled = false))
        assertNull(GoCustomLintAnnotator().collectInformation(file))
    }

    fun testOnSaveLintersWaitForASave() {
        val file = goFile()
        settings.customLinters = listOf(GoCustomLinter("probe", "probe", trigger = GoLinterTrigger.ON_SAVE))
        assertEquals(emptyList<Any>(), GoCustomLintAnnotator().collectInformation(file)!!.runs)
    }

    fun testTheTableIsKeptAsCopies() {
        settings.customLinters = listOf(GoCustomLinter("a", "a"))
        settings.customLinters.single().name = "changed outside"
        assertEquals("a", settings.customLinters.single().name)
    }
}
