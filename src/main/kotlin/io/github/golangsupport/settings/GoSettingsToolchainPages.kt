package io.github.golangsupport.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import com.intellij.util.ui.UIUtil
import io.github.golangsupport.GoBundle
import io.github.golangsupport.cli.GoEnvironment
import io.github.golangsupport.cli.GoPluginData
import io.github.golangsupport.cli.GoPluginRelocation
import io.github.golangsupport.cli.GoTool
import io.github.golangsupport.mod.GoModule
import io.github.golangsupport.mod.GoModulesService
import io.github.golangsupport.project.api.GoModuleGraphProvider
import io.github.golangsupport.sdk.GoIgsLibraryRootsPolicy
import javax.swing.JButton
import kotlin.reflect.KMutableProperty0

/** A grey label of the pages that show what `go env` or the project model says. */
private fun infoLabel() = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }

/** Settings | Go | GOROOT: the `go` whose toolchain is in use; GOROOT, the version and the standard library come from it. */
class GoGorootConfigurable(project: Project) : GoSettingsPage(project, "page.goroot") {
    private val goPath = TextFieldWithBrowseButton()
    private val goStatus = infoLabel()

    override fun createPanel(): DialogPanel {
        goPath.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle(GoBundle.message("settings.goPath.chooser")))
        return panel {
            row(GoBundle.message("settings.goPath")) { cell(goPath).align(AlignX.FILL).comment(GoBundle.message("settings.goPath.comment")) }
            row(GoBundle.message("goroot.inUse")) { cell(goStatus) }
            row { comment(GoBundle.message("goroot.comment")) }
        }
    }

    private fun refresh() = GoSettingsConfigurable.refreshGoStatus(goStatus) { executable ->
        (goPath.textField as? JBTextField)?.emptyText?.text = executable ?: GoBundle.message("settings.notFound")
    }

    override fun isModified(): Boolean = super.isModified() || goPath.text.trim() != settings.goPath

    override fun apply() {
        super.apply()
        settings.goPath = goPath.text
        refresh()
    }

    override fun reset() {
        super.reset()
        goPath.text = settings.goPath
        refresh()
    }
}

/** Settings | Go | GOPATH: what `go env` says, read-only; the plugin looks for the tools in GOBIN and GOPATH/bin. */
class GoGopathConfigurable(project: Project) : GoSettingsPage(project, "page.gopath") {
    private val goPath = infoLabel()
    private val goBin = infoLabel()

    override fun createPanel(): DialogPanel = panel {
        row("GOPATH:") { cell(goPath) }
        row("GOBIN:") { cell(goBin) }
        row { comment(GoBundle.message("gopath.comment")) }
    }

    override fun reset() {
        super.reset()
        ApplicationManager.getApplication().executeOnPooledThread {
            val environment = GoEnvironment.get()
            val none = GoBundle.message("gopath.notSet")
            ApplicationManager.getApplication().invokeLater({
                goPath.text = environment.goPath ?: none
                goBin.text = environment.values["GOBIN"]?.takeIf { it.isNotEmpty() } ?: GoBundle.message("gopath.gobin.default", environment.binDirectory?.path ?: none)
            }, ModalityState.any())
        }
    }
}

/** Settings | Go | Go Modules: the library roots of the native PSI, and what the project model found: the module cache, vendor mode. */
class GoModulesConfigurable(project: Project) : GoSettingsPage(project, "page.modules") {
    private val modCache = infoLabel()
    private val modules = infoLabel()

    override fun createPanel(): DialogPanel = panel {
        row(GoBundle.message("settings.libraryRoots")) {
            // the renderer, not toString(): what the settings file keeps has to stay English whatever the language of the page
            comboBox(GoLibraryRoots.entries, SimpleListCellRenderer.create("") { it.label }).bindItem(settings::libraryRoots.toNullableProperty())
                .comment(GoBundle.message("settings.libraryRoots.comment"))
        }
        row("GOMODCACHE:") { cell(modCache) }
        group(GoBundle.message("modules.group")) {
            row { cell(modules) }
            row { comment(GoBundle.message("modules.comment")) }
        }
    }

    override fun apply() {
        val libraryRoots = settings.libraryRoots
        super.apply()
        if (settings.libraryRoots != libraryRoots) GoIgsLibraryRootsPolicy.settingChanged()
    }

    override fun reset() {
        super.reset()
        modules.text = GoBundle.message("modules.reading")
        ApplicationManager.getApplication().executeOnPooledThread {
            val cache = GoEnvironment.get().goModCache ?: GoBundle.message("gopath.notSet")
            val found = runCatching { ReadAction.compute<List<GoModule>, RuntimeException> { GoModulesService.getInstance(project).modules() } }.getOrDefault(emptyList())
            val lines = found.map { module ->
                val vendor = runCatching { GoModuleGraphProvider.getInstance(project).graphFor(module.root)?.vendorMode }.getOrNull() == true
                GoBundle.message(if (vendor) "modules.vendor" else "modules.cache", module.path)
            }
            val text = if (lines.isEmpty()) GoBundle.message("modules.none") else lines.joinToString("<br>", "<html>", "</html>")
            ApplicationManager.getApplication().invokeLater({ modCache.text = cache; modules.text = text }, ModalityState.any())
        }
    }
}

/**
 * Settings | Go | Build Tags: GOOS / GOARCH the built-in analysis assumes (the status bar widget sets the same two), Cgo support and
 * Experiments (the `cgo` and `goexperiment.X` tags of the analysis, `CGO_ENABLED` / `GOEXPERIMENT` of the go commands) and the `-tags`
 * of every command that compiles. "Edit build tags…" of the widget opens this page.
 */
class GoBuildTagsConfigurable(project: Project) : GoSettingsPage(project, "page.buildTags") {
    // `CGO_ENABLED` of `go env`: what Default means
    private var environmentCgo: String? = null
    private var cgoCombo: ComboBox<GoCgoMode>? = null

    override fun createPanel(): DialogPanel = panel {
        group(GoBundle.message("buildTags.target")) {
            row("GOOS:") { platformCombo(GoPlatformChoices.operatingSystems(), GoPlatformChoices.hostOs(), settings::analysisGoos) }
            row("GOARCH:") { platformCombo(GoPlatformChoices.architectures(), GoPlatformChoices.hostArch(), settings::analysisGoarch) }
            row { comment(GoBundle.message("buildTags.target.comment")) }
        }
        group(GoBundle.message("buildTags.toolchain")) {
            row(GoBundle.message("buildTags.cgo")) {
                // the renderer, not toString(): what the settings file keeps has to stay English whatever the language of the page
                cgoCombo = comboBox(GoCgoMode.entries, SimpleListCellRenderer.create("") { cgoLabel(it) }).bindItem(settings::cgoMode.toNullableProperty())
                    .comment(GoBundle.message("buildTags.cgo.comment")).component
            }
            row(GoBundle.message("buildTags.experiments")) {
                val field = textField().align(AlignX.FILL).resizableColumn().bindText(settings::goExperiments).comment(GoBundle.message("buildTags.experiments.comment"))
                    .validationOnInput { field -> GoSettings.invalidExperiment(field.text)?.let { error(GoBundle.message("buildTags.experiments.invalid", it)) } }
                    .validationOnApply { field -> GoSettings.invalidExperiment(field.text)?.let { error(GoBundle.message("buildTags.experiments.invalid", it)) } }
                    .component
                // the names come from the sources of the installed toolchain: they change with Go versions
                button(GoBundle.message("buildTags.experiments.choose")) { GoExperimentsDialog.choose(project, field.text)?.let { field.text = it } }.align(AlignY.TOP)
            }
        }
        group(GoBundle.message("buildTags.tags")) {
            row(GoBundle.message("settings.buildTags")) { textField().align(AlignX.FILL).bindText(settings::buildTags).comment(GoBundle.message("settings.buildTags.comment")) }
        }
    }

    /** `Default (CGO_ENABLED=1)` while `go env` is known, else `Default`. */
    private fun cgoLabel(mode: GoCgoMode?): String = when (mode) {
        null -> ""
        GoCgoMode.DEFAULT -> environmentCgo?.let { GoBundle.message("buildTags.default", "CGO_ENABLED=$it") } ?: mode.label
        else -> mode.label
    }

    /** "" first, shown as `Default (windows)`: the empty setting follows the host. */
    private fun Row.platformCombo(values: List<String>, host: String, property: KMutableProperty0<String>) =
        comboBox(listOf("") + values, SimpleListCellRenderer.create("") { if (it.isEmpty()) GoBundle.message("buildTags.default", host) else it })
            .bindItem({ property.get() }, { property.set(it.orEmpty()) })

    private fun target() = listOf(settings.analysisGoos, settings.analysisGoarch, settings.buildTags, settings.cgoMode, settings.goExperiments)

    /** No process here: `go env` is read once at startup by the toolchain provider; until then Default stays without a value. */
    override fun reset() {
        environmentCgo = if (GoEnvironment.isKnown()) GoEnvironment.quick().values["CGO_ENABLED"]?.takeIf { it.isNotEmpty() } else null
        super.reset()
        cgoCombo?.repaint()
    }

    /** As the widget does: the toolchain provider keys its answer on these settings, and the highlighting restarts. */
    override fun apply() {
        val before = target()
        super.apply()
        if (target() != before) GoPlatformWidgetFactory.reanalyze()
    }
}

/** Settings | Go | Tools: where gopls, dlv, golangci-lint, goimports, govulncheck are, with Install / Update. */
class GoToolsConfigurable(project: Project) : GoSettingsPage(project, "page.tools") {
    private val toolRows = GoTool.entries.associateWith { ToolRow(it) }

    private inner class ToolRow(val tool: GoTool) {
        val path = TextFieldWithBrowseButton().apply {
            addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle(GoBundle.message("settings.tools.chooser", tool.command)))
        }
        val install = JButton(GoBundle.message("settings.tools.install")).apply { addActionListener { runInstallation() } }
        val status = infoLabel()

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

    override fun createPanel(): DialogPanel = panel {
        for (row in toolRows.values) {
            row(row.tool.command + ":") {
                // resizableColumn: in a row of several cells the free width goes to the one that asks for it, and without it
                // the field keeps its preferred size while the page grows (seen live: a path field of ten characters)
                cell(row.path).align(AlignX.FILL).resizableColumn().comment(GoBundle.messageOr("tool.${row.tool.command}.purpose", row.tool.purpose))
                cell(row.install).align(AlignY.TOP)
            }
            row("") { cell(row.status) }
        }
        row { comment(GoBundle.message("tools.comment")) }
        group(GoBundle.message("pluginData.group")) {
            row(GoBundle.message("pluginData.directory")) {
                cell(dataDirectory).align(AlignX.FILL).comment(GoBundle.message("pluginData.comment"))
            }
        }
    }

    /** Where the plugin keeps delve, installed tools, the catalogue and temporary builds: moved in the background on apply. */
    private val dataDirectory = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle(GoBundle.message("pluginData.directory")))
        text = settings.pluginDataDirectory
        (textField as? JBTextField)?.emptyText?.text = GoPluginData.defaultRoot().toString()
    }

    /** GOBIN comes from `go env`, which blocks: asked off EDT, then the tools are looked for. */
    private fun refreshTools() {
        ApplicationManager.getApplication().executeOnPooledThread {
            GoEnvironment.get()
            ApplicationManager.getApplication().invokeLater({ toolRows.values.forEach { it.refresh() } }, ModalityState.any())
        }
    }

    override fun isModified(): Boolean = super.isModified() || toolRows.values.any { it.path.text.trim() != settings.toolPath(it.tool.command) } ||
        dataDirectory.text.trim() != settings.pluginDataDirectory

    override fun apply() {
        super.apply()
        toolRows.values.forEach { settings.setToolPath(it.tool.command, it.path.text) }
        if (dataDirectory.text.trim() != settings.pluginDataDirectory) GoPluginRelocation.relocate(project, dataDirectory.text)
        refreshTools()
    }

    override fun reset() {
        super.reset()
        toolRows.values.forEach { it.path.text = settings.toolPath(it.tool.command) }
        refreshTools()
    }
}
