package io.github.golangsupport.settings

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.ComboBoxTableRenderer
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.dsl.builder.selected
import com.intellij.ui.dsl.builder.toNullableProperty
import com.intellij.ui.layout.selectedValueMatches
import com.intellij.ui.table.TableView
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import javax.swing.DefaultComboBoxModel
import javax.swing.JCheckBox
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer
import io.github.golangsupport.GoBundle
import io.github.golangsupport.ide.GoRenameChoice
import io.github.golangsupport.ide.inspections.printf.GoPrintfFunctions
import io.github.golangsupport.lang.GoFeature
import io.github.golangsupport.problems.GoProjectProblems

/**
 * A page under Settings | Go ([GoSettingsTree]): one area each, so that a page is read in one look. The settings are application-level; the
 * language server is restarted on apply, since most of them go into its start or its configuration. The texts come from [GoBundle]:
 * the settings pages are the part of the plugin that speaks Russian when the IDE does.
 */
abstract class GoSettingsPage(protected val project: Project, titleKey: String) : BoundConfigurable(GoBundle.message(titleKey)) {
    protected val settings: GoSettings get() = GoSettings.getInstance()

    override fun apply() {
        super.apply()
        GoLanguageServerControl.restartAll(project)
        io.github.golangsupport.lang.GoProjectPresence.Windows.refreshUi(project)
    }
}

/** The switches of gopls the plugin knows what to do with; every other option of the server is on the gopls page under this one. */
class GoLanguageServerConfigurable(project: Project) : GoSettingsPage(project, "page.languageServer") {
    override fun createPanel(): DialogPanel = panel {
        row { checkBox(GoBundle.message("ls.enabled")).bindSelected(settings::languageServerEnabled) }
        row { checkBox(GoBundle.message("ls.staticcheck")).bindSelected(settings::goplsStaticcheck) }
        row { checkBox(GoBundle.message("ls.gofumpt")).bindSelected(settings::goplsGofumpt) }
        row { checkBox(GoBundle.message("ls.inlayHints")).bindSelected(settings::goplsInlayHints).comment(GoBundle.message("ls.inlayHints.comment")) }
        row { checkBox(GoBundle.message("ls.highlightUsages")).bindSelected(settings::goplsHighlightUsages).comment(GoBundle.message("ls.highlightUsages.comment")) }
        row { checkBox(GoBundle.message("ls.trace")).bindSelected(settings::goplsTrace).comment(GoBundle.message("ls.trace.comment")) }
        row { checkBox(GoBundle.message("ls.debugPages")).bindSelected(settings::goplsDebugPages).comment(GoBundle.message("ls.debugPages.comment")) }
        row { comment(GoBundle.message("ls.more")) }
        group(GoBundle.message("features.group")) {
            row(GoBundle.message("features.source")) {
                // the renderer, not toString(): what the settings file keeps has to stay English whatever the language of the page
                comboBox(GoFeatureSource.entries, SimpleListCellRenderer.create("") { it.label }).bindItem(settings::languageFeaturesSource.toNullableProperty())
                    .comment(GoBundle.message("features.source.comment"))
            }
        }
    }

    private fun sources() = GoFeature.entries.map(settings::featureSource)

    /** A changed source shows at once: the error elements and the gopls annotations are both decided per highlighting pass, so a restart of the daemon is enough. */
    override fun apply() {
        val before = sources()
        super.apply()
        val after = sources()
        if (after != before) ProjectManager.getInstance().openProjects.forEach { DaemonCodeAnalyzer.getInstance(it).restart() }
    }
}

class GoDebuggerConfigurable(project: Project) : GoSettingsPage(project, "page.debugger") {
    override fun createPanel(): DialogPanel = panel {
        row { checkBox(GoBundle.message("debugger.globals")).bindSelected(settings::debugShowGlobalVariables) }
        row { checkBox(GoBundle.message("debugger.hideSystem")).bindSelected(settings::debugHideSystemGoroutines).comment(GoBundle.message("debugger.hideSystem.comment")) }
        row(GoBundle.message("debugger.stackDepth")) { intTextField(1..1000).bindIntText(settings::debugStackTraceDepth) }
        row {
            checkBox(GoBundle.message("debugger.anyGoVersion")).bindSelected(settings::debugAnyGoVersion)
                .comment(GoBundle.message("debugger.anyGoVersion.comment"))
        }
        row { checkBox(GoBundle.message("debugger.log")).bindSelected(settings::debugAdapterLog).comment(GoBundle.message("debugger.log.comment")) }
        row {
            // Windows has no SIGQUIT: delve is the only way there, the box stays on and cannot be changed
            checkBox(GoBundle.message("debugger.dumpViaDelve")).bindSelected({ settings.debugDumpViaDelve || SystemInfo.isWindows }, { settings.debugDumpViaDelve = it })
                .enabled(!SystemInfo.isWindows).comment(GoBundle.message("debugger.dumpViaDelve.comment"))
        }
        lateinit var location: ComboBox<GoDebugBinaryLocation>
        row(GoBundle.message("debugger.binaryIn")) {
            // the renderer, not toString(): what the settings file keeps has to stay English whatever the language of the page
            location = comboBox(GoDebugBinaryLocation.entries, SimpleListCellRenderer.create("") { it.label })
                .bindItem(settings::debugBinaryLocation.toNullableProperty())
                .comment(GoBundle.message("debugger.binaryIn.comment")).component
        }
        row(GoBundle.message("debugger.binaryDirectory")) {
            textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle(GoBundle.message("debugger.binaryDirectory.chooser")), project)
                .align(AlignX.FILL).bindText(settings::debugBinaryDirectory)
                .comment(GoBundle.message("debugger.binaryDirectory.comment"))
        }.visibleIf(location.selectedValueMatches { it == GoDebugBinaryLocation.CUSTOM })
    }
}

class GoEditorConfigurable(project: Project) : GoSettingsPage(project, "page.editor") {
    override fun createPanel(): DialogPanel = panel {
        group(GoBundle.message("editor.group")) {
            row { checkBox(GoBundle.message("editor.docComments")).bindSelected(settings::docCommentNames).comment(GoBundle.message("editor.docComments.comment")) }
            row { checkBox(GoBundle.message("editor.goplsActions")).bindSelected(settings::goplsActionsInMenu).comment(GoBundle.message("editor.goplsActions.comment")) }
            row { checkBox(GoBundle.message("editor.latin")).bindSelected(settings::latinInCode).comment(GoBundle.message("editor.latin.comment")) }
            row { checkBox(GoBundle.message("editor.askBeforePlayground")).bindSelected(settings::askBeforePlayground).comment(GoBundle.message("editor.askBeforePlayground.comment")) }
            row { checkBox(GoBundle.message("editor.idioms")).bindSelected(settings::inlineIdioms).comment(GoBundle.message("editor.idioms.comment")) }
            row { checkBox(GoBundle.message("editor.suggestions")).bindSelected(settings::inlineSuggestions).comment(GoBundle.message("editor.suggestions.comment")) }
            row { checkBox(GoBundle.message("editor.suggestionColors")).bindSelected(settings::inlineSuggestionColors).comment(GoBundle.message("editor.suggestionColors.comment")) }
            // Reformat block on typing '}' and Insert documentation comment stub are the platform's checkboxes, as in GoLand
            row {
                link(GoBundle.message("editor.smartKeys")) { event ->
                    val settingsDialog = com.intellij.ide.DataManager.getInstance().getDataContext(event.source as java.awt.Component).getData(com.intellij.openapi.options.ex.Settings.KEY)
                    settingsDialog?.find("editor.preferences.smartKeys")?.let(settingsDialog::select)
                }.comment(GoBundle.message("editor.smartKeys.comment"))
            }
        }
        group(GoBundle.message("completion.group")) {
            row { checkBox(GoBundle.message("completion.returnValues")).bindSelected(settings::completeReturnValues).comment(GoBundle.message("completion.returnValues.comment")) }
            row { checkBox(GoBundle.message("completion.prefixFirst")).bindSelected(settings::completionPrefixFirst).comment(GoBundle.message("completion.prefixFirst.comment")) }
            row { checkBox(GoBundle.message("completion.byType")).bindSelected(settings::completionByType).comment(GoBundle.message("completion.byType.comment")) }
            row { checkBox(GoBundle.message("completion.structBraces")).bindSelected(settings::completionStructBraces).comment(GoBundle.message("completion.structBraces.comment")) }
            row { checkBox(GoBundle.message("completion.keywords")).bindSelected(settings::completionKeywordTemplates).comment(GoBundle.message("completion.keywords.comment")) }
            row { checkBox(GoBundle.message("completion.values")).bindSelected(settings::completionValues).comment(GoBundle.message("completion.values.comment")) }
            row { checkBox(GoBundle.message("completion.arguments")).bindSelected(settings::completionArguments).comment(GoBundle.message("completion.arguments.comment")) }
        }
        // GoLand's "When ... is renamed" / "When JSON is pasted" of its Go page
        group(GoBundle.message("rename.group")) {
            renameChoice("rename.testFiles", settings::renameTestFiles)
            renameChoice("rename.structTags", settings::renameStructTags)
            renameChoice("rename.directoryPackage", settings::renameDirectoryPackage)
            renameChoice("rename.packageDirectory", settings::renamePackageDirectory)
            row { comment(GoBundle.message("rename.comment")) }
        }
        group(GoBundle.message("paste.group")) {
            row(GoBundle.message("paste.json")) {
                comboBox(GoPasteJson.entries, SimpleListCellRenderer.create("") { it.label }).bindItem(settings::pasteJson.toNullableProperty())
                    .comment(GoBundle.message("paste.json.comment"))
            }
        }
    }

    private fun Panel.renameChoice(key: String, property: kotlin.reflect.KMutableProperty0<GoRenameChoice>) {
        row(GoBundle.message(key)) {
            comboBox(GoRenameChoice.entries, SimpleListCellRenderer.create("") { GoBundle.message("renameChoice.${it.name}") }).bindItem(property.toNullableProperty())
        }
    }
}

/** Settings | Go | Imports: what completion offers from packages the file does not import yet, with the import written on choice. */
class GoImportsConfigurable(project: Project) : GoSettingsPage(project, "page.imports") {
    override fun createPanel(): DialogPanel = panel {
        row { checkBox(GoBundle.message("completion.unimported")).bindSelected(settings::completionUnimportedPackages).comment(GoBundle.message("completion.unimported.comment")) }
        row { checkBox(GoBundle.message("completion.catalogue")).bindSelected(settings::completionCatalogue).comment(GoBundle.message("completion.catalogue.comment")) }
        row { comment(GoBundle.message("imports.paste")) }
        row { comment(GoBundle.message("imports.autoImport")) }
    }
}

/** Settings | Go | Formatting: who formats at Reformat Code and on save. */
class GoFormattingConfigurable(project: Project) : GoSettingsPage(project, "page.formatting") {
    // `golangci-lint fmt` is a choice only while golangci-lint is on (the Linters page): with it off nothing may start the tool
    // (GoSettings.formatter falls back to gofmt); filled at reset, so that a switch applied on the other page shows here
    private val formatters = DefaultComboBoxModel<GoFormatter>()

    override fun createPanel(): DialogPanel = panel {
        fillFormatters()
        row(GoBundle.message("quality.formatter")) { comboBox(formatters).bindItem(settings::formatter.toNullableProperty()).comment(GoBundle.message("formatting.formatter.comment")) }
        row { checkBox(GoBundle.message("quality.formatOnSave")).bindSelected(settings::formatOnSave).comment(GoBundle.message("quality.formatOnSave.comment")) }
        row {
            optimizeImportsOnSave = checkBox(GoBundle.message("formatting.optimizeImportsOnSave")).bindSelected(settings::optimizeImportsOnSave)
                .comment(GoBundle.message("formatting.optimizeImportsOnSave.comment")).component
        }
    }

    /** Mirrored on Settings | Tools | Actions on Save ([io.github.golangsupport.lang.GoOptimizeImportsOnSaveInfoProvider]), which reads and sets the box of this page. */
    lateinit var optimizeImportsOnSave: JCheckBox
        private set

    private fun fillFormatters() {
        val available = GoFormatter.entries.filter { it != GoFormatter.GOLANGCI_LINT_FMT || settings.golangciLint }
        if ((0 until formatters.size).map(formatters::getElementAt) == available) return
        formatters.removeAllElements()
        formatters.addAll(available)
    }

    override fun reset() {
        fillFormatters()
        super.reset()
    }
}

/**
 * Settings | Go | Linters: the built-in analysis (the project pass and the table of every inspection and lint rule of the plugin),
 * golangci-lint (optional) and the linters of the user.
 */
class GoLintersConfigurable(project: Project) : GoSettingsPage(project, "page.linters") {
    private val linters = ListTableModel<GoCustomLinter>(
        textColumn("quality.linters.name", GoCustomLinter::name) { linter, value -> linter.name = value.trim() },
        textColumn("quality.linters.commandLine", GoCustomLinter::commandLine) { linter, value -> linter.commandLine = value.trim() },
        object : ColumnInfo<GoCustomLinter, Boolean>(GoBundle.message("quality.linters.enabled")) {
            override fun valueOf(item: GoCustomLinter): Boolean = item.enabled
            override fun isCellEditable(item: GoCustomLinter): Boolean = true
            override fun setValue(item: GoCustomLinter, value: Boolean) { item.enabled = value }
            override fun getColumnClass(): Class<*> = java.lang.Boolean::class.java
        },
        enumColumn("quality.linters.trigger", GoLinterTrigger.entries.toTypedArray(), { it.label }, GoCustomLinter::trigger) { linter, value -> linter.trigger = value },
        enumColumn("quality.linters.directory", GoLinterDirectory.entries.toTypedArray(), { it.label }, GoCustomLinter::directory) { linter, value -> linter.directory = value },
        enumColumn("quality.linters.format", GoLinterFormat.entries.toTypedArray(), { it.label }, GoCustomLinter::format) { linter, value -> linter.format = value },
    )
    private val table = TableView(linters).apply { setShowGrid(false); visibleRowCount = 4 }
    private val checks = GoChecksPanel(project)

    override fun createPanel(): DialogPanel = panel {
        group(GoBundle.message("linters.builtin")) { builtIn() }
        group(GoBundle.message("printf.group")) { printf() }
        group(GoBundle.message("quality.golangci.group")) {
            row { checkBox(GoBundle.message("quality.golangci")).bindSelected(settings::golangciLint).comment(GoBundle.message("quality.golangci.comment")) }
        }
        group(GoBundle.message("quality.vulncheck.group")) {
            row { checkBox(GoBundle.message("quality.vulncheck")).bindSelected(settings::vulnerabilityCheck).comment(GoBundle.message("quality.vulncheck.comment")) }
        }
        group(GoBundle.message("quality.linters.group")) {
            row {
                val decorated = ToolbarDecorator.createDecorator(table)
                    .setAddAction { stopEditing(); linters.addRow(GoCustomLinter(name = "mylinter", commandLine = "mylinter -json \$Package\$")) }
                    .disableUpDownActions().createPanel()
                cell(decorated).align(Align.FILL)
                    .onIsModified { stopEditing(); linters.items != settings.customLinters }
                    .onApply { stopEditing(); settings.customLinters = linters.items }
                    .onReset { linters.items = settings.customLinters }
            }.resizableRow()
            row { comment(GoBundle.message("quality.linters.comment")) }
        }
        row(GoBundle.message("quality.linters.timeout")) { intTextField(5..600).bindIntText(settings::lintTimeoutSeconds).comment(GoBundle.message("linters.timeout.comment")) }
    }

    /** The Built-in group: the project analysis and the table of every native check ([GoChecksPanel]), stored on apply by [GoChecksStore]. */
    private fun Panel.builtIn() {
        lateinit var whole: Cell<JCheckBox>
        row { whole = checkBox(GoBundle.message("quality.project")).bindSelected(settings::projectAnalysis).comment(GoBundle.message("quality.project.comment")) }
        row { checkBox(GoBundle.message("quality.project.warnings")).bindSelected(settings::projectAnalysisWarnings).enabledIf(whole.selected) }
        row {
            cell(checks).align(Align.FILL)
                .onIsModified { checks.model.isModified }
                .onApply {
                    checks.stopEditing()
                    checks.model.problem()?.let { throw ConfigurationException(it) }
                    GoChecksStore.save(project, checks.model)
                    checks.load(GoChecksStore.load(project))
                }
                .onReset { checks.load(GoChecksStore.load(project)) }
        }.resizableRow()
        row { comment(GoBundle.message("linters.builtin.checks")) }
    }

    /**
     * The printf-like functions of the format checks ([GoPrintfFunctions], also edited by Alt+Enter Mark as / Exclude string formatting
     * function): vet's full names, one per line.
     */
    private fun Panel.printf() {
        val functions = GoPrintfFunctions.getInstance()
        row(GoBundle.message("printf.extra")) {}
        row {
            textArea().rows(3).align(AlignX.FILL)
                .bindText({ functions.extra.joinToString("\n") }, { functions.extra = GoSettingsLists.lines(it) })
                .comment(GoBundle.message("printf.extra.comment"))
        }
        row(GoBundle.message("printf.excluded")) {}
        row {
            textArea().rows(3).align(AlignX.FILL)
                .bindText({ functions.excluded.joinToString("\n") }, { functions.excluded = GoSettingsLists.lines(it) })
                .comment(GoBundle.message("printf.excluded.comment"))
        }
    }

    private fun stopEditing() {
        table.cellEditor?.stopCellEditing()
    }

    /** The linters run in the highlighting pass: a changed table shows at the next pass, without waiting for an edit. */
    override fun apply() {
        super.apply()
        ProjectManager.getInstance().openProjects.forEach { DaemonCodeAnalyzer.getInstance(it).restart() }
        ProjectManager.getInstance().openProjects.forEach { GoProjectProblems.getInstance(it).settingsChanged() }
    }

    private fun textColumn(key: String, get: (GoCustomLinter) -> String, set: (GoCustomLinter, String) -> Unit) = object : ColumnInfo<GoCustomLinter, String>(GoBundle.message(key)) {
        override fun valueOf(item: GoCustomLinter): String = get(item)
        override fun isCellEditable(item: GoCustomLinter): Boolean = true
        override fun setValue(item: GoCustomLinter, value: String) = set(item, value)
    }

    /** A combo in the cell, showing [label]: the enum's toString is what the settings file keeps and stays English. */
    private fun <E : Any> enumColumn(key: String, values: Array<E>, label: (E) -> String, get: (GoCustomLinter) -> E, set: (GoCustomLinter, E) -> Unit) =
        object : ColumnInfo<GoCustomLinter, E>(GoBundle.message(key)) {
            private val combo = object : ComboBoxTableRenderer<E>(values) {
                override fun getTextFor(value: E): String = label(value)
            }
            override fun valueOf(item: GoCustomLinter): E = get(item)
            override fun isCellEditable(item: GoCustomLinter): Boolean = true
            override fun setValue(item: GoCustomLinter, value: E) = set(item, value)
            override fun getRenderer(item: GoCustomLinter): TableCellRenderer = combo
            override fun getEditor(item: GoCustomLinter): TableCellEditor = combo
        }
}
