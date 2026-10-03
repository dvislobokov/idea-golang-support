package io.github.golangsupport.templates

import com.intellij.facet.ui.ValidationResult
import com.intellij.ide.util.projectWizard.SettingsStep
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.ide.wizard.NewProjectWizardBaseStep
import com.intellij.ide.wizard.NewProjectWizardChainStep.Companion.nextStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.RootNewProjectWizardStep
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.DirectoryProjectGeneratorBase
import com.intellij.platform.ProjectGeneratorPeer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.PlatformUtils
import io.github.golangsupport.GoIcons
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.cli.GoEnvironment
import io.github.golangsupport.settings.GoSettings
import java.io.File
import java.io.IOException
import javax.swing.Icon
import javax.swing.JComponent

/**
 * "Go" in the New Project dialog of IntelliJ IDEA, which lists generator wizards rather than the directory generators the other IDEs
 * show ([GoProjectGenerator]), in the manner of GoLand's: name and location come from the base step, GOROOT and the module path below.
 * Finish runs `go mod init <path>` and, optionally, writes main.go.
 */
class GoNewProjectWizard : GeneratorNewProjectWizard {
    init {
        // GIGA IDE has the dialog of IntelliJ IDEA and lists ".NET" of the sibling plugin in it, but not "Go" (seen live): the line tells
        // whether the dialog has asked for the wizard at all
        LOG.info("New Project wizard of Go is created; the platform is ${PlatformUtils.getPlatformPrefix()}, enabled: ${isEnabled()}")
    }

    // not "Go": that is the id of the wizard the dialog advertises for the Go plugin of JetBrains, which a fork may hide
    override val id: String get() = "io.github.golangsupport.newProject"
    override val name: String get() = "Go"
    override val icon: Icon get() = GoIcons.File
    override val description: String get() = "A Go module: go.mod with the module path and, optionally, main.go"

    // the other IDEs show the directory generator; both at once would be two "Go" entries
    override fun isEnabled(): Boolean = PlatformUtils.isIntelliJ()

    override fun createStep(context: WizardContext): NewProjectWizardStep =
        RootNewProjectWizardStep(context)
            .nextStep(::NewProjectWizardBaseStep)
            .nextStep(::Step)

    /** GOROOT and the module path under the shared name and location fields. */
    private class Step(base: NewProjectWizardBaseStep) : AbstractNewProjectWizardStep(base) {
        init {
            // New Go projects default under ~/GigaIdeProjects, not the platform's ~/<Product>Projects
            base.path = File(System.getProperty("user.home"), "GigaIdeProjects").path
        }

        private val rows = GoProjectPanel(context.project)

        override fun setupUI(builder: Panel) = rows.addRows(builder)

        override fun setupProject(project: Project) {
            GoProjectCreator.create(project, project.basePath ?: return, rows.settings)
        }
    }

    private companion object {
        val LOG = logger<GoNewProjectWizard>()
    }
}

/** "Go" entry of the New Project dialog in the IDEs that use directory-based generators (PyCharm, WebStorm, ..., and the forks that do). */
class GoProjectGenerator : DirectoryProjectGeneratorBase<GoNewProjectSettings>() {
    override fun getName(): String = "Go"
    override fun getLogo(): Icon = GoIcons.File
    override fun createPeer(): ProjectGeneratorPeer<GoNewProjectSettings> = Peer()

    override fun validate(baseDirPath: String): ValidationResult =
        if (GoCli.findExecutable() == null) ValidationResult("The 'go' executable is not found. Install Go, or set its path in Settings | Go | GOROOT.")
        else ValidationResult.OK

    override fun generateProject(project: Project, baseDir: VirtualFile, settings: GoNewProjectSettings, module: Module) =
        GoProjectCreator.create(project, baseDir.path, settings)

    private class Peer : ProjectGeneratorPeer<GoNewProjectSettings> {
        private val rows = GoProjectPanel(null)
        private val settingsPanel: JComponent by lazy { panel { rows.addRows(this) } }

        override fun getComponent(myLocationField: TextFieldWithBrowseButton, checkValid: Runnable): JComponent = settingsPanel
        override fun buildUI(settingsStep: SettingsStep) = settingsStep.addSettingsComponent(settingsPanel)
        override fun getSettings(): GoNewProjectSettings = rows.settings
        override fun validate(): ValidationInfo? = null
        override fun isBackgroundJobRunning(): Boolean = false
    }
}

class GoNewProjectSettings(val goRoot: String, val modulePath: String, val sampleCode: Boolean)

/** The rows of both New Project dialogs: GOROOT, the module path, the sample. */
class GoProjectPanel(project: Project?) {
    private val goRoot = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("GOROOT"))
        text = detectGoRoot()
    }
    private val modulePath = JBTextField()
    private val sampleCode = JBCheckBox("Create main.go with func main", true)

    val settings: GoNewProjectSettings get() = GoNewProjectSettings(goRoot.text.trim(), modulePath.text.trim(), sampleCode.isSelected)

    fun addRows(builder: Panel) {
        with(builder) {
            // short lines: a comment wider than the dialog gives it a horizontal scroll bar and cuts the fields (seen live)
            row("GOROOT:") { cell(goRoot).align(AlignX.FILL).comment("Where Go is installed; empty: PATH and the default directories", COMMENT_WIDTH) }
            row("Module path:") { cell(modulePath).align(AlignX.FILL).comment("What other modules import this one by; empty: the project name", COMMENT_WIDTH) }
            row { cell(sampleCode) }
        }
    }

    private fun detectGoRoot(): String =
        GoEnvironment.quick().goRoot ?: GoCli.findExecutable()?.let { File(it).parentFile?.parent }.orEmpty()

    private companion object {
        const val COMMENT_WIDTH = 50
    }
}

/** What Finish of both dialogs does: `go mod init` in the directory of the project, then main.go. The plugin has one toolchain, in its settings. */
object GoProjectCreator {
    private const val TITLE = "New Go Project"
    private const val MAIN_GO = "package main\n\nimport \"fmt\"\n\nfunc main() {\n\tfmt.Println(\"Hello, world!\")\n}\n"

    fun create(project: Project, basePath: String, settings: GoNewProjectSettings) {
        rememberGoRoot(settings.goRoot)
        val module = settings.modulePath.ifEmpty { project.name.trim().ifEmpty { "myapp" } }
        val commands = GoCli.commandLinesOrNotify(project, TITLE) { listOf(GoCli.commandLine(basePath, "mod", "init", module)) } ?: return
        GoCli.runInBackground(project, TITLE, commands, refresh = listOf(File(basePath))) {
            if (settings.sampleCode) createMainFile(project, basePath)
        }
    }

    /** A GOROOT the user picked over the detected one becomes the plugin's `go`. */
    private fun rememberGoRoot(root: String) {
        if (root.isEmpty()) return
        val go = File(root, "bin/${GoCli.executableName("go")}")
        if (go.isFile && GoCli.findExecutable() != go.path) GoSettings.getInstance().goPath = go.path
    }

    /** On EDT after `go mod init` succeeded (the project directory exists): the entry point, unless the user already has one. */
    private fun createMainFile(project: Project, basePath: String) {
        val directory = LocalFileSystem.getInstance().refreshAndFindFileByPath(basePath) ?: return
        if (directory.findChild("main.go") != null) return
        val file = runCatching {
            WriteAction.compute<VirtualFile, IOException> {
                directory.createChildData(this, "main.go").also { VfsUtil.saveText(it, MAIN_GO) }
            }
        }.getOrNull() ?: return
        FileEditorManager.getInstance(project).openFile(file, true)
    }
}
