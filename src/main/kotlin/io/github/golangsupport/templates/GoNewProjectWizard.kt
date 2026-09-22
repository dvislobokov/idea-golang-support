package io.github.golangsupport.templates

import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.ide.wizard.NewProjectWizardBaseStep
import com.intellij.ide.wizard.NewProjectWizardChainStep.Companion.nextStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.RootNewProjectWizardStep
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import io.github.golangsupport.GoIcons
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.cli.GoEnvironment
import io.github.golangsupport.settings.GoSettings
import java.io.File
import java.io.IOException
import javax.swing.Icon

/**
 * "Go" entry of the module-based New Project wizard (IntelliJ IDEA family, including forks such as GIGA IDE), in the manner of GoLand's:
 * name and location come from the base step, GOROOT and the module path below. Finish runs `go mod init <path>` and, optionally, writes main.go.
 * The plugin has no per-project SDK, so a GOROOT other than the detected one is remembered in Settings | Tools | Go — the single toolchain.
 */
class GoNewProjectWizard : GeneratorNewProjectWizard {
    override val id: String = "Go"
    override val name: String = "Go"
    override val icon: Icon = GoIcons.File

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

        private val goRoot = TextFieldWithBrowseButton().apply {
            addBrowseFolderListener(context.project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("GOROOT"))
            text = detectGoRoot()
        }
        private val modulePath = JBTextField()
        private val sampleCode = JBCheckBox("Create main.go with func main", true)

        override fun setupUI(builder: Panel) {
            with(builder) {
                row("GOROOT:") { cell(goRoot).align(AlignX.FILL).comment("Where Go is installed; empty falls back to PATH and the default directories") }
                row("Module path:") { cell(modulePath).align(AlignX.FILL).comment("The go.mod module line: what other modules import this one by. Empty uses the project name") }
                row { cell(sampleCode) }
            }
        }

        override fun setupProject(project: Project) {
            rememberGoRoot()
            val basePath = project.basePath ?: return
            val module = modulePath.text.trim().ifEmpty { project.name.trim().ifEmpty { "myapp" } }
            val commands = GoCli.commandLinesOrNotify(project, "New Go Project") {
                listOf(GoCli.commandLine(basePath, "mod", "init", module))
            } ?: return
            GoCli.runInBackground(project, "New Go Project", commands, refresh = listOf(File(basePath))) {
                if (sampleCode.isSelected) createMainFile(project, basePath)
            }
        }

        /** A GOROOT the user picked over the detected one becomes the plugin's `go`: there is one toolchain, kept in the settings. */
        private fun rememberGoRoot() {
            val root = goRoot.text.trim().ifEmpty { return }
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

        private fun detectGoRoot(): String =
            GoEnvironment.quick().goRoot ?: GoCli.findExecutable()?.let { File(it).parentFile?.parent }.orEmpty()

        private companion object {
            const val MAIN_GO = "package main\n\nimport \"fmt\"\n\nfunc main() {\n\tfmt.Println(\"Hello, world!\")\n}\n"
        }
    }
}
