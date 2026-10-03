package io.github.golangsupport.ide.refactoring

import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.refactoring.ui.RefactoringDialog
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.EditableModel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JTextArea
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel

/**
 * The Change Signature dialog: the name, the parameters as a table (name, type, default value for the calls; add, remove, move up and
 * down), the results as text (`int`, `(int, error)`, `(n int, err error)`) and the new signature as it will read. Refactor and Preview
 * come from [RefactoringDialog]; the work is [GoChangeSignatureProcessor]'s.
 */
class GoChangeSignatureDialog(project: Project, private val target: PsiElement) : RefactoringDialog(project, true) {

    private val initial = GoChangeSignature.initial(target)
    private val oldParameters = GoChangeSignature.parametersOf(GoChangeSignature.signatureOf(target))
    private val nameField = JBTextField(initial.name)
    private val resultsField = JBTextField(GoChangeSignature.resultsText(initial.results).trim())
    private val model = ParameterModel(initial.parameters.toMutableList())
    private val table = JBTable(model)
    private val preview = JTextArea(2, 60).apply {
        isEditable = false
        lineWrap = true
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
    }

    init {
        title = GoChangeSignatureHandler.TITLE
        val listener = object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = update()
        }
        nameField.document.addDocumentListener(listener)
        resultsField.document.addDocumentListener(listener)
        model.addTableModelListener { update() }
        init()
        update()
    }

    fun options(): GoChangeSignatureOptions = GoChangeSignatureOptions(nameField.text.trim(), model.rows.toList(), GoChangeSignature.parseResults(resultsField.text))

    private fun update() {
        preview.text = GoChangeSignature.preview(target, options())
        validateButtons()
    }

    override fun canRun() {
        GoChangeSignature.validate(oldParameters, options())?.let { throw ConfigurationException(it) }
    }

    override fun doAction() = invokeRefactoring(GoChangeSignatureProcessor(project, target, options()))

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun createCenterPanel(): JComponent {
        table.preferredScrollableViewportSize = JBUI.size(520, 140)
        val parameters = ToolbarDecorator.createDecorator(table).createPanel()
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Name:", nameField)
            .addLabeledComponentFillVertically("Parameters:", parameters)
            .addLabeledComponent("Results:", resultsField)
            .addLabeledComponent("Signature preview:", preview)
            .panel
    }

    /** The parameters in the new order; a row added here is a new parameter (old slot -1) that needs a default value for the calls. */
    private class ParameterModel(val rows: MutableList<GoChangeParameter>) : AbstractTableModel(), EditableModel {
        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = 3

        override fun getColumnName(column: Int): String = COLUMNS[column]

        override fun getValueAt(row: Int, column: Int): Any = rows[row].let {
            when (column) {
                0 -> it.name
                1 -> it.type
                else -> if (it.oldIndex < 0) it.defaultValue else ""
            }
        }

        // The default value only matters for a new parameter: the calls of an old one keep their argument.
        override fun isCellEditable(row: Int, column: Int): Boolean = column < 2 || rows[row].oldIndex < 0

        override fun setValueAt(value: Any?, row: Int, column: Int) {
            val text = value?.toString()?.trim() ?: ""
            rows[row] = when (column) {
                0 -> rows[row].copy(name = text)
                1 -> rows[row].copy(type = text)
                else -> rows[row].copy(defaultValue = text)
            }
            fireTableRowsUpdated(row, row)
        }

        override fun addRow() {
            val used = rows.map { it.name }.toSet()
            val name = generateSequence(1) { it + 1 }.map { "p$it" }.first { it !in used }
            val index = rows.indexOfFirst { it.isVariadic }.takeIf { it >= 0 } ?: rows.size // before a variadic parameter
            rows.add(index, GoChangeParameter(if (rows.isNotEmpty() && rows.all { it.name.isEmpty() }) "" else name, "int", -1, "0"))
            fireTableRowsInserted(index, index)
        }

        override fun removeRow(index: Int) {
            rows.removeAt(index)
            fireTableRowsDeleted(index, index)
        }

        override fun exchangeRows(oldIndex: Int, newIndex: Int) {
            val row = rows[oldIndex]
            rows[oldIndex] = rows[newIndex]
            rows[newIndex] = row
            fireTableDataChanged()
        }

        override fun canExchangeRows(oldIndex: Int, newIndex: Int): Boolean = oldIndex in rows.indices && newIndex in rows.indices

        companion object {
            val COLUMNS = arrayOf("Name", "Type", "Default value")
        }
    }
}
