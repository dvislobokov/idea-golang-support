package io.github.golangsupport.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.cli.GoEnvironment
import io.github.golangsupport.cli.GoTool
import javax.swing.JButton

/** What the part of the plugin with the language server does when the settings it was started with change. Implemented where the LSP API of the platform is. */
interface GoLanguageServerControl {
    fun restart(project: Project)

    companion object {
        val EP: ExtensionPointName<GoLanguageServerControl> = ExtensionPointName.create("io.github.golangsupport.languageServerControl")
        fun restartAll(project: Project) = EP.extensionList.forEach { it.restart(project) }
    }
}

/** Settings | Tools | Go: the toolchain and the tools; the areas of the plugin are the pages under it ([GoSettingsPage]). Only what has an implementation behind it. */
class GoSettingsConfigurable(project: Project) : GoSettingsPage(project, "Go") {
    private val goPath = TextFieldWithBrowseButton()
    private val goStatus = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val toolRows = GoTool.entries.associateWith { ToolRow(it) }

    private inner class ToolRow(val tool: GoTool) {
        val path = TextFieldWithBrowseButton().apply {
            addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("${tool.command} Executable"))
        }
        val install = JButton("Install").apply { addActionListener { runInstallation() } }
        val status = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }

        /** The page lives in a modal dialog, which hides the Build tool window: the command runs here and its last line is shown. */
        private fun runInstallation() {
            install.isEnabled = false
            status.text = "Running: go ${tool.installCommand().joinToString(" ")}"
            ApplicationManager.getApplication().executeOnPooledThread {
                val output = StringBuffer()
                val exitCode = tool.installBlocking { output.append(it) }
                ApplicationManager.getApplication().invokeLater({
                    install.isEnabled = true
                    if (exitCode == 0) refresh() else status.text = "Failed: " + output.lines().lastOrNull { it.isNotBlank() }.orEmpty().trim()
                }, ModalityState.any())
            }
        }

        fun refresh() {
            val found = tool.find()
            status.text = found?.path ?: "Not installed: go install ${tool.module}@latest"
            install.text = if (found == null) "Install" else "Update"
        }
    }

    override fun createPanel(): DialogPanel {
        goPath.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("go Executable"))
        return panel {
            group("Toolchain") {
                row("Path to go:") { cell(goPath).align(AlignX.FILL).comment("Empty: PATH, GOROOT and the default installation directories") }
                row { cell(goStatus) }
                row("Build tags:") {
                    textField().align(AlignX.FILL).bindText(settings::buildTags)
                        .comment("<code>-tags</code> of build, run, test and vet, of the language server, the linter and the debugger")
                }
                row { checkBox("Create run configurations for the programs of the project").bindSelected(settings::createRunConfigurations).comment("One per directory with a <code>func main</code>, when the project is opened; a deleted one does not come back") }
                row("Test arguments:") { textField().align(AlignX.FILL).bindText(settings::testArguments).comment("Added to every <code>go test</code>: <code>-race -count=1</code>") }
            }
            group("Tools") {
                for (row in toolRows.values) {
                    row(row.tool.command + ":") {
                        cell(row.path).align(AlignX.FILL).comment(row.tool.purpose)
                        cell(row.install)
                    }
                    row("") { cell(row.status) }
                }
            }
            row { comment("The language server, the debugger, the editor and completion, formatting and the linter: the pages under this one") }
        }
    }

    private fun refreshGoStatus() {
        ApplicationManager.getApplication().executeOnPooledThread {
            GoEnvironment.reset()
            val executable = GoCli.findExecutable()
            val environment = GoEnvironment.get()
            val text = if (executable == null) "go is not found" else "$executable  —  Go ${environment.goVersion ?: "?"}, GOROOT ${environment.goRoot ?: "?"}"
            ApplicationManager.getApplication().invokeLater({
                goStatus.text = text
                toolRows.values.forEach { it.refresh() }
            }, ModalityState.any())
        }
    }

    override fun isModified(): Boolean = super.isModified() || goPath.text.trim() != settings.goPath || toolRows.values.any { it.path.text.trim() != settings.toolPath(it.tool.command) }

    override fun apply() {
        super.apply()
        settings.goPath = goPath.text
        toolRows.values.forEach { settings.setToolPath(it.tool.command, it.path.text) }
        refreshGoStatus()
    }

    override fun reset() {
        super.reset()
        goPath.text = settings.goPath
        toolRows.values.forEach { it.path.text = settings.toolPath(it.tool.command) }
        refreshGoStatus()
    }
}
