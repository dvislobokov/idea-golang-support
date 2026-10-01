package io.github.golangsupport.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import com.intellij.util.ui.UIUtil
import io.github.golangsupport.GoBundle
import io.github.golangsupport.PluginLanguage
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
class GoSettingsConfigurable(project: Project) : GoSettingsPage(project, "page.go") {
    private val goPath = TextFieldWithBrowseButton()
    private val goStatus = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val toolRows = GoTool.entries.associateWith { ToolRow(it) }

    private inner class ToolRow(val tool: GoTool) {
        val path = TextFieldWithBrowseButton().apply {
            addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle(GoBundle.message("settings.tools.chooser", tool.command)))
        }
        val install = JButton(GoBundle.message("settings.tools.install")).apply { addActionListener { runInstallation() } }
        val status = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }

        /**
         * A progress in the status bar of the IDE, as every other long command of the plugin has (asked by the user), and the last
         * line of the output here: the page is a modal dialog, which hides both the Build tool window and the status bar behind it.
         */
        private fun runInstallation() {
            install.isEnabled = false
            status.text = GoBundle.message("settings.tools.running", tool.installCommand().joinToString(" "))
            val title = GoBundle.message(if (tool.find() == null) "settings.tools.installing" else "settings.tools.updating", tool.command)
            ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    indicator.text = "go " + tool.installCommand().joinToString(" ")
                    val output = StringBuffer()
                    val exitCode = tool.installBlocking { text ->
                        output.append(text)
                        text.lines().lastOrNull { it.isNotBlank() }?.let { indicator.text2 = it.trim() }
                    }
                    ApplicationManager.getApplication().invokeLater({
                        install.isEnabled = true
                        if (exitCode == 0) refresh() else status.text = GoBundle.message("settings.tools.failed", output.lines().lastOrNull { it.isNotBlank() }.orEmpty().trim())
                    }, ModalityState.any())
                }
            })
        }

        fun refresh() {
            val found = tool.find()
            status.text = found?.path ?: GoBundle.message("settings.tools.notInstalled", tool.module)
            install.text = GoBundle.message(if (found == null) "settings.tools.install" else "settings.tools.update")
            // the field holds an override and stays empty while the plugin finds the tool itself: what it found is the text of the empty field
            (path.textField as? JBTextField)?.emptyText?.text = found?.path ?: GoBundle.message("settings.tools.missing")
        }
    }

    override fun createPanel(): DialogPanel {
        goPath.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle(GoBundle.message("settings.goPath.chooser")))
        return panel {
            group(GoBundle.message("settings.toolchain")) {
                row(GoBundle.message("settings.goPath")) { cell(goPath).align(AlignX.FILL).comment(GoBundle.message("settings.goPath.comment")) }
                row { cell(goStatus) }
                row(GoBundle.message("settings.buildTags")) {
                    textField().align(AlignX.FILL).bindText(settings::buildTags).comment(GoBundle.message("settings.buildTags.comment"))
                }
                row { checkBox(GoBundle.message("settings.runConfigurations")).bindSelected(settings::createRunConfigurations).comment(GoBundle.message("settings.runConfigurations.comment")) }
                row(GoBundle.message("settings.testArguments")) { textField().align(AlignX.FILL).bindText(settings::testArguments).comment(GoBundle.message("settings.testArguments.comment")) }
                // the pages are rebuilt when the dialog is reopened: said here, since the texts around do not change at once
                row(GoBundle.message("settings.language")) {
                    comboBox(PluginLanguage.entries, SimpleListCellRenderer.create("") { it.label }).bindItem(settings::language.toNullableProperty())
                }
            }
            group(GoBundle.message("settings.tools")) {
                for (row in toolRows.values) {
                    row(row.tool.command + ":") {
                        // resizableColumn: in a row of several cells the free width goes to the one that asks for it, and without it
                        // the field keeps its preferred size while the page grows (seen live: a path field of ten characters)
                        cell(row.path).align(AlignX.FILL).resizableColumn().comment(GoBundle.messageOr("tool.${row.tool.command}.purpose", row.tool.purpose))
                        cell(row.install).align(AlignY.TOP)
                    }
                    row("") { cell(row.status) }
                }
            }
            row { comment(GoBundle.message("settings.pagesBelow")) }
        }
    }

    private fun refreshGoStatus() {
        ApplicationManager.getApplication().executeOnPooledThread {
            GoEnvironment.reset()
            val executable = GoCli.findExecutable()
            val environment = GoEnvironment.get()
            val unknown = GoBundle.message("settings.unknown")
            val text = if (executable == null) GoBundle.message("settings.go.notFound")
            else GoBundle.message("settings.go.found", executable, environment.goVersion ?: unknown, environment.goRoot ?: unknown)
            val placeholder = executable ?: GoBundle.message("settings.notFound")
            ApplicationManager.getApplication().invokeLater({
                goStatus.text = text
                (goPath.textField as? JBTextField)?.emptyText?.text = placeholder
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
