package io.github.golangsupport.settings

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
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
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import com.intellij.ui.layout.selectedValueMatches
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import javax.swing.DefaultComboBoxModel
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer
import io.github.golangsupport.GoBundle
import io.github.golangsupport.lang.GoFeature

/**
 * A page under Settings | Tools | Go: one area each, so that a page is read in one look. The settings are application-level; the
 * language server is restarted on apply, since most of them go into its start or its configuration. The texts come from [GoBundle]:
 * the settings pages are the part of the plugin that speaks Russian when the IDE does.
 */
abstract class GoSettingsPage(protected val project: Project, titleKey: String) : BoundConfigurable(GoBundle.message(titleKey)) {
    protected val settings: GoSettings get() = GoSettings.getInstance()

    override fun apply() {
        super.apply()
        GoLanguageServerControl.restartAll(project)
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
            row { checkBox(GoBundle.message("editor.idioms")).bindSelected(settings::inlineIdioms).comment(GoBundle.message("editor.idioms.comment")) }
        }
        group(GoBundle.message("completion.group")) {
            row { checkBox(GoBundle.message("completion.returnValues")).bindSelected(settings::completeReturnValues).comment(GoBundle.message("completion.returnValues.comment")) }
            row { checkBox(GoBundle.message("completion.prefixFirst")).bindSelected(settings::completionPrefixFirst).comment(GoBundle.message("completion.prefixFirst.comment")) }
            row { checkBox(GoBundle.message("completion.byType")).bindSelected(settings::completionByType).comment(GoBundle.message("completion.byType.comment")) }
            row { checkBox(GoBundle.message("completion.catalogue")).bindSelected(settings::completionCatalogue).comment(GoBundle.message("completion.catalogue.comment")) }
            row { checkBox(GoBundle.message("completion.unimported")).bindSelected(settings::completionUnimportedPackages).comment(GoBundle.message("completion.unimported.comment")) }
            row { checkBox(GoBundle.message("completion.structBraces")).bindSelected(settings::completionStructBraces).comment(GoBundle.message("completion.structBraces.comment")) }
            row { checkBox(GoBundle.message("completion.keywords")).bindSelected(settings::completionKeywordTemplates).comment(GoBundle.message("completion.keywords.comment")) }
            row { checkBox(GoBundle.message("completion.values")).bindSelected(settings::completionValues).comment(GoBundle.message("completion.values.comment")) }
            row { checkBox(GoBundle.message("completion.arguments")).bindSelected(settings::completionArguments).comment(GoBundle.message("completion.arguments.comment")) }
        }
    }
}

class GoCodeQualityConfigurable(project: Project) : GoSettingsPage(project, "page.codeQuality") {
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

    override fun createPanel(): DialogPanel = panel {
        // `golangci-lint fmt` is a choice only while golangci-lint is on: with it off nothing may start the tool (GoSettings.formatter falls back to gofmt)
        val formatters = DefaultComboBoxModel(GoFormatter.entries.filter { it != GoFormatter.GOLANGCI_LINT_FMT || settings.golangciLint }.toTypedArray())
        row(GoBundle.message("quality.formatter")) { comboBox(formatters).bindItem(settings::formatter.toNullableProperty()) }
        row { checkBox(GoBundle.message("quality.formatOnSave")).bindSelected(settings::formatOnSave).comment(GoBundle.message("quality.formatOnSave.comment")) }
        group(GoBundle.message("quality.golangci.group")) {
            row {
                val golangci = checkBox(GoBundle.message("quality.golangci")).bindSelected(settings::golangciLint).comment(GoBundle.message("quality.golangci.comment")).component
                golangci.addItemListener {
                    val fmt = GoFormatter.GOLANGCI_LINT_FMT
                    if (golangci.isSelected && formatters.getIndexOf(fmt) < 0) formatters.insertElementAt(fmt, GoFormatter.entries.indexOf(fmt))
                    if (!golangci.isSelected && formatters.getIndexOf(fmt) >= 0) {
                        if (formatters.selectedItem == fmt) formatters.selectedItem = GoFormatter.GOFMT
                        formatters.removeElement(fmt)
                    }
                }
            }
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
            row(GoBundle.message("quality.linters.timeout")) { intTextField(5..600).bindIntText(settings::lintTimeoutSeconds) }
        }
    }

    private fun stopEditing() {
        table.cellEditor?.stopCellEditing()
    }

    /** The linters run in the highlighting pass: a changed table shows at the next pass, without waiting for an edit. */
    override fun apply() {
        super.apply()
        ProjectManager.getInstance().openProjects.forEach { DaemonCodeAnalyzer.getInstance(it).restart() }
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
