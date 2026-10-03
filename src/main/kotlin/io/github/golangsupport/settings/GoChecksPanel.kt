package io.github.golangsupport.settings

import com.intellij.codeInspection.ex.EditInspectionToolsSettingsAction
import com.intellij.ide.DataManager
import com.intellij.openapi.options.ex.ConfigurableWrapper
import com.intellij.openapi.options.ex.Settings
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.profile.codeInspection.ui.ErrorsConfigurable
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.golangsupport.GoBundle
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Font
import javax.swing.DefaultCellEditor
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/** The texts of the Source column for a row; pure, for the tests. */
object GoChecksText {
    fun source(model: GoChecksModel, check: GoCheck): String {
        val notes = ArrayList<String>()
        if (check.configured) {
            val own = GoChecksModel.ruleOverride(check, model.state(check.id))
            val overridden = own.enabled != null || own.level != null || own.options.isNotEmpty()
            notes += GoBundle.message(if (overridden) "checks.source.overridesConfig" else "checks.source.config")
        }
        if (check.goplsQuiet) notes += GoBundle.message("checks.source.gopls")
        if (model.mutedByMaster(check)) notes += GoBundle.message("checks.source.master")
        return notes.joinToString(" · ")
    }

    /** `Default (Warning)`: what Default stands for. */
    fun level(check: GoCheck, level: GoCheckLevel): String =
        if (level == GoCheckLevel.DEFAULT) GoBundle.message("checks.level.defaultOf", check.baseLevel.label) else level.label
}

/**
 * The Built-in table of Settings | Go | Linters: every inspection of the plugin and every rule of the rule engine, grouped, with a
 * search field, on/off, level and a source note per row; below it the description of the selected row and the options of a rule (an
 * inspection's own options stay in Settings | Editor | Inspections, the link goes there). Edits a [GoChecksModel]; [GoChecksStore] writes it.
 */
class GoChecksPanel(private val project: Project) : JPanel(BorderLayout()) {
    var model: GoChecksModel = GoChecksModel(emptyList(), emptyMap())
        private set

    private val tableModel = TableModel()
    private val table = JBTable(tableModel)
    private val search = SearchTextField(false)
    private val description = JEditorPane().apply {
        editorKit = HTMLEditorKitBuilder.simple()
        isEditable = false
        border = JBUI.Borders.empty(4)
    }
    private val options = JPanel(GridBagLayout())

    init {
        table.setShowGrid(false)
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.columnModel.getColumn(COLUMN_ENABLED).apply { maxWidth = JBUI.scale(36); minWidth = JBUI.scale(36) }
        table.columnModel.getColumn(COLUMN_NAME).preferredWidth = JBUI.scale(300)
        table.columnModel.getColumn(COLUMN_ID).preferredWidth = JBUI.scale(150)
        table.columnModel.getColumn(COLUMN_LEVEL).preferredWidth = JBUI.scale(130)
        table.columnModel.getColumn(COLUMN_SOURCE).preferredWidth = JBUI.scale(170)
        val levels = ComboBox(GoCheckLevel.entries.toTypedArray()).apply { renderer = SimpleListCellRenderer.create("") { it?.label.orEmpty() } }
        table.columnModel.getColumn(COLUMN_LEVEL).cellEditor = DefaultCellEditor(levels)
        table.setDefaultRenderer(Any::class.java, TextRenderer())
        table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) showSelected() }
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                model.filter = search.text
                tableModel.fireTableDataChanged()
            }
        })

        val actions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(10), 0)).apply {
            add(ActionLink(GoBundle.message("checks.enableGroup")) { selectedGroup()?.let { model.setGroupEnabled(it, true); changed() } })
            add(ActionLink(GoBundle.message("checks.disableGroup")) { selectedGroup()?.let { model.setGroupEnabled(it, false); changed() } })
            add(ActionLink(GoBundle.message("checks.resetGroup")) { selectedGroup()?.let { model.resetGroup(it); changed(); showSelected() } })
            add(ActionLink(GoBundle.message("checks.resetAll")) { model.resetAll(); changed(); showSelected() })
        }
        val top = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            add(search, BorderLayout.CENTER)
            add(actions, BorderLayout.EAST)
            border = JBUI.Borders.emptyBottom(4)
        }
        val details = JPanel(BorderLayout()).apply {
            add(JBScrollPane(description), BorderLayout.CENTER)
            add(options, BorderLayout.SOUTH)
        }
        val splitter = JBSplitter(true, 0.62f).apply {
            firstComponent = JBScrollPane(table)
            secondComponent = details
        }
        add(top, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)
        preferredSize = JBUI.size(760, 480)
    }

    /** A new model (reset of the page): the filter is kept. */
    fun load(model: GoChecksModel) {
        stopEditing()
        model.filter = search.text
        this.model = model
        tableModel.fireTableDataChanged()
        showSelected()
    }

    fun stopEditing() {
        table.cellEditor?.stopCellEditing()
    }

    private fun changed() = tableModel.fireTableRowsUpdated(0, maxOf(0, tableModel.rowCount - 1))

    private fun selectedRow(): GoChecksRow? = table.selectedRow.takeIf { it >= 0 }?.let { model.rows.getOrNull(it) }

    private fun selectedGroup(): String? = when (val row = selectedRow()) {
        is GoChecksRow.Header -> row.group
        is GoChecksRow.Item -> row.check.group
        null -> null
    }

    private fun showSelected() {
        options.removeAll()
        when (val row = selectedRow()) {
            null -> description.text = html(GoBundle.message("checks.select"))
            is GoChecksRow.Header -> description.text = html("<b>${row.group}</b><br>" + GoBundle.message("checks.group", model.inGroup(row.group).size))
            is GoChecksRow.Item -> {
                val check = row.check
                description.text = html("<b>${check.name}</b> <code>${check.id}</code><br>" + check.description())
                description.caretPosition = 0
                if (check.kind == GoCheckKind.INSPECTION) addInspectionLink(check) else addOptions(check)
            }
        }
        options.revalidate()
        options.repaint()
    }

    private fun addInspectionLink(check: GoCheck) {
        options.add(ActionLink(GoBundle.message("checks.editInInspections")) { openInInspections(it.source as Component, check.id) }, constraints(0, 0, 2))
    }

    private fun addOptions(check: GoCheck) {
        if (check.options.isEmpty()) {
            options.add(JBLabel(GoBundle.message("checks.noOptions")).apply { foreground = UIUtil.getContextHelpForeground() }, constraints(0, 0, 2))
            return
        }
        check.options.forEachIndexed { index, option ->
            val label = JBLabel("${option.name}:").apply { toolTipText = option.description.ifEmpty { null } }
            val text = model.optionText(check.id, option.name)
            val editor: Component = when (option.type) {
                GoCheckOption.Type.BOOL -> JBCheckBox(option.description.ifEmpty { option.name }, text.toBooleanStrictOrNull() ?: false).apply {
                    addActionListener { this@GoChecksPanel.model.setOption(check.id, option.name, isSelected.toString()); changed() }
                }
                else -> JBTextField(text).apply {
                    toolTipText = option.description.ifEmpty { null }
                    emptyText.text = option.baseText
                    document.addDocumentListener(object : DocumentAdapter() {
                        override fun textChanged(e: DocumentEvent) { this@GoChecksPanel.model.setOption(check.id, option.name, getText()); changed() }
                    })
                }
            }
            options.add(label, constraints(0, index, 1))
            options.add(editor, constraints(1, index, 1).apply { weightx = 1.0; fill = GridBagConstraints.HORIZONTAL })
        }
    }

    private fun constraints(x: Int, y: Int, width: Int) = GridBagConstraints().apply {
        gridx = x; gridy = y; gridwidth = width; anchor = GridBagConstraints.WEST; insets = JBUI.insets(2, 4)
    }

    /** Settings | Editor | Inspections with the inspection selected: in this dialog when the page is in one, else a dialog of its own. */
    private fun openInInspections(source: Component, shortName: String) {
        val settings = Settings.KEY.getData(DataManager.getInstance().getDataContext(source))
        val page = settings?.find(INSPECTIONS_PAGE)
        if (settings != null && page != null) {
            settings.select(page).doWhenDone { ConfigurableWrapper.cast(ErrorsConfigurable::class.java, page)?.selectInspectionTool(shortName) }
            return
        }
        EditInspectionToolsSettingsAction.editToolSettings(project, InspectionProjectProfileManager.getInstance(project).currentProfile, shortName)
    }

    /** An inspection's description is a whole HTML document: its own html / body tags go, so that it nests. */
    private fun html(body: String) = "<html><body>" + body.replace(DOCUMENT_TAGS, "") + "</body></html>"

    private inner class TableModel : AbstractTableModel() {
        override fun getRowCount(): Int = model.rows.size
        override fun getColumnCount(): Int = 5

        override fun getColumnName(column: Int): String = GoBundle.message(
            when (column) {
                COLUMN_ENABLED -> "checks.column.enabled"
                COLUMN_NAME -> "checks.column.name"
                COLUMN_ID -> "checks.column.id"
                COLUMN_LEVEL -> "checks.column.level"
                else -> "checks.column.source"
            },
        )

        override fun getColumnClass(column: Int): Class<*> = if (column == COLUMN_ENABLED) java.lang.Boolean::class.java else Any::class.java

        override fun isCellEditable(row: Int, column: Int): Boolean = when (model.rows.getOrNull(row)) {
            is GoChecksRow.Header -> column == COLUMN_ENABLED
            is GoChecksRow.Item -> column == COLUMN_ENABLED || column == COLUMN_LEVEL
            null -> false
        }

        override fun getValueAt(row: Int, column: Int): Any? = when (val r = model.rows.getOrNull(row)) {
            is GoChecksRow.Header -> when (column) {
                COLUMN_ENABLED -> model.groupEnabled(r.group)
                COLUMN_NAME -> r.group
                else -> null
            }
            is GoChecksRow.Item -> when (column) {
                COLUMN_ENABLED -> model.state(r.check.id).enabled
                COLUMN_NAME -> r.check.name
                COLUMN_ID -> r.check.id
                COLUMN_LEVEL -> model.state(r.check.id).level
                else -> GoChecksText.source(model, r.check)
            }
            null -> null
        }

        override fun setValueAt(value: Any?, row: Int, column: Int) {
            when (val r = model.rows.getOrNull(row)) {
                is GoChecksRow.Header -> if (column == COLUMN_ENABLED && value is Boolean) model.setGroupEnabled(r.group, value)
                is GoChecksRow.Item -> when {
                    column == COLUMN_ENABLED && value is Boolean -> model.setEnabled(r.check.id, value)
                    column == COLUMN_LEVEL && value is GoCheckLevel -> model.setLevel(r.check.id, value)
                }
                null -> return
            }
            changed()
        }
    }

    /** Bold group names, `Default (Warning)` in the level column, grey rows switched off with the Go lint rules inspection. */
    private inner class TextRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(table: JTable, value: Any?, selected: Boolean, focused: Boolean, row: Int, column: Int): Component {
            val r = model.rows.getOrNull(row)
            val text = when {
                r is GoChecksRow.Item && value is GoCheckLevel -> GoChecksText.level(r.check, value)
                else -> value?.toString().orEmpty()
            }
            val component = super.getTableCellRendererComponent(table, text, selected, false, row, column)
            component.font = table.font.deriveFont(if (r is GoChecksRow.Header) Font.BOLD else Font.PLAIN)
            if (!selected) {
                val muted = r is GoChecksRow.Item && (model.mutedByMaster(r.check) || !model.state(r.check.id).enabled)
                component.foreground = if (muted || column == COLUMN_SOURCE) UIUtil.getContextHelpForeground() else table.foreground
            }
            return component
        }
    }

    private companion object {
        const val COLUMN_ENABLED = 0
        const val COLUMN_NAME = 1
        const val COLUMN_ID = 2
        const val COLUMN_LEVEL = 3
        const val COLUMN_SOURCE = 4

        val DOCUMENT_TAGS = Regex("</?(html|body)[^>]*>", RegexOption.IGNORE_CASE)

        /** The id of Settings | Editor | Inspections. */
        const val INSPECTIONS_PAGE = "Errors"
    }
}
