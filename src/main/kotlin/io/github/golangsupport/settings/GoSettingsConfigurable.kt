package io.github.golangsupport.settings

import com.intellij.ide.DataManager
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.options.ex.Settings
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import com.intellij.util.ui.UIUtil
import io.github.golangsupport.GoBundle
import io.github.golangsupport.PluginLanguage
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.cli.GoEnvironment
import java.awt.Component

/** What the part of the plugin with the language server does when the settings it was started with change. Implemented where the LSP API of the platform is. */
interface GoLanguageServerControl {
    fun restart(project: Project)

    companion object {
        val EP: ExtensionPointName<GoLanguageServerControl> = ExtensionPointName.create("io.github.golangsupport.languageServerControl")
        fun restartAll(project: Project) = EP.extensionList.forEach { it.restart(project) }
    }
}

/**
 * The ids of Settings | Go and its pages, in the order of the tree (GoLand's: GOROOT, GOPATH, Go Modules, Build Tags, ...).
 * plugin.xml registers the same ids with descending `groupWeight`s; GoSettingsTreeTest holds the two together.
 */
object GoSettingsTree {
    const val ROOT = "io.github.golangsupport.settings"
    const val GOROOT = "$ROOT.goroot"
    const val GOPATH = "$ROOT.gopath"
    const val MODULES = "$ROOT.modules"
    const val BUILD_TAGS = "$ROOT.buildTags"
    const val IMPORTS = "$ROOT.imports"
    const val LINTERS = "$ROOT.linters"
    const val FORMATTING = "$ROOT.formatting"
    const val EDITOR = "$ROOT.editor"
    const val LANGUAGE_SERVER = "$ROOT.languageServer"
    const val GOPLS = "$ROOT.gopls"
    const val DEBUGGER = "$ROOT.debugger"
    const val TOOLS = "$ROOT.tools"

    /** The pages right under Go, in order: id -> the part of the GoBundle keys `page.X` (title) and `root.page.X` (its line on the Go page). */
    val PAGES: List<Pair<String, String>> = listOf(
        GOROOT to "goroot", GOPATH to "gopath", MODULES to "modules", BUILD_TAGS to "buildTags", IMPORTS to "imports", LINTERS to "linters",
        FORMATTING to "formatting", EDITOR to "editor", LANGUAGE_SERVER to "languageServer", DEBUGGER to "debugger", TOOLS to "tools",
    )

    /** Selects the page [id] in the Settings dialog [component] is in; false outside of it. */
    fun select(component: Component, id: String): Boolean {
        val settings = Settings.KEY.getData(DataManager.getInstance().getDataContext(component)) ?: return false
        val page = settings.find(id) ?: return false
        settings.select(page)
        return true
    }
}

/**
 * Settings | Go, the root of the tree above Appearance & Behavior, as GoLand has it: which Go is in use, the settings of no single
 * area, and a line for each page under it. Only what has an implementation behind it.
 */
class GoSettingsConfigurable(project: Project) : GoSettingsPage(project, "page.go") {
    private val goStatus = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }

    override fun createPanel(): DialogPanel = panel {
        group(GoBundle.message("root.inUse")) {
            row(GoBundle.message("root.go")) { cell(goStatus) }
            row(GoBundle.message("features.source")) { comment(GoBundle.message("root.features", settings.languageFeaturesSource.label)) }
        }
        group(GoBundle.message("root.general")) {
            row { checkBox(GoBundle.message("settings.runConfigurations")).bindSelected(settings::createRunConfigurations).comment(GoBundle.message("settings.runConfigurations.comment")) }
            row(GoBundle.message("settings.testArguments")) { textField().align(AlignX.FILL).bindText(settings::testArguments).comment(GoBundle.message("settings.testArguments.comment")) }
            // the pages are rebuilt when the dialog is reopened: said here, since the texts around do not change at once
            row(GoBundle.message("settings.language")) {
                comboBox(PluginLanguage.entries, SimpleListCellRenderer.create("") { it.label }).bindItem(settings::language.toNullableProperty())
            }
            // only where the IDE has the Shared Indexes plugin: go-shared-indexes.xml is loaded with it
            if (sharedIndexesAvailable()) {
                row(GoBundle.message("settings.sharedIndexUrl")) {
                    textField().align(AlignX.FILL).bindText(settings::sharedIndexUrl).comment(GoBundle.message("settings.sharedIndexUrl.comment"))
                }
            }
        }
        group(GoBundle.message("root.pages")) {
            for ((id, key) in GoSettingsTree.PAGES) {
                row {
                    link(GoBundle.message("page.$key")) { GoSettingsTree.select(it.source as Component, id) }
                    comment(GoBundle.message("root.page.$key"))
                }
            }
        }
    }

    override fun reset() {
        super.reset()
        refreshGoStatus(goStatus)
    }

    override fun apply() {
        super.apply()
        refreshGoStatus(goStatus)
    }

    companion object {
        /** The plugin of go-shared-indexes.xml is installed and on; checked by id, so nothing of the package `sharedindex` is loaded here. */
        fun sharedIndexesAvailable(): Boolean = PluginManagerCore.getPlugin(PluginId.getId("intellij.indexing.shared.core"))?.let { !PluginManagerCore.isDisabled(it.pluginId) } == true

        /** `go` found, its version and GOROOT, or "not found"; `go env` blocks, so it is asked off EDT. [then] gets the executable on EDT. */
        fun refreshGoStatus(label: JBLabel, then: (String?) -> Unit = {}) {
            ApplicationManager.getApplication().executeOnPooledThread {
                GoEnvironment.reset()
                val executable = GoCli.findExecutable()
                val environment = GoEnvironment.get()
                val unknown = GoBundle.message("settings.unknown")
                val text = if (executable == null) GoBundle.message("settings.go.notFound")
                else GoBundle.message("settings.go.found", executable, environment.goVersion ?: unknown, environment.goRoot ?: unknown)
                ApplicationManager.getApplication().invokeLater({ label.text = text; then(executable) }, ModalityState.any())
            }
        }
    }
}
