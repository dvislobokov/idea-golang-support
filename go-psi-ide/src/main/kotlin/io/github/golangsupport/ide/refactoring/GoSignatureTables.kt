package io.github.golangsupport.ide.refactoring

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.ui.EditorTextField
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.AbstractTableCellEditor
import com.intellij.util.ui.EditableModel
import com.intellij.util.ui.JBUI
import io.github.golangsupport.ide.completion.GoCodeFragments
import io.github.golangsupport.lang.GoFileType
import io.github.golangsupport.lang.psi.GoFile
import java.awt.Component
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.util.EventObject
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

/** A column of [GoRowsTableModel]: its header, the cell text of a row, the row with a new cell text, whether the row's cell can be edited. */
class GoRowsColumn<T>(val name: String, val get: (T) -> String, val set: (T, String) -> T, val editable: (T) -> Boolean = { true })

/**
 * The rows of a signature table (parameters, results) for [ToolbarDecorator]: editable cells (trimmed), add, remove, move up and down.
 * [newRow] gives the added row and where it goes from the current rows.
 */
class GoRowsTableModel<T>(initial: List<T>, private val columns: List<GoRowsColumn<T>>, private val newRow: (List<T>) -> Pair<Int, T>) : AbstractTableModel(), EditableModel {

    val rows: MutableList<T> = initial.toMutableList()

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = columns[column].name

    override fun getValueAt(row: Int, column: Int): Any = columns[column].get(rows[row])

    override fun isCellEditable(row: Int, column: Int): Boolean = columns[column].editable(rows[row])

    override fun setValueAt(value: Any?, row: Int, column: Int) {
        rows[row] = columns[column].set(rows[row], value?.toString()?.trim() ?: "")
        fireTableRowsUpdated(row, row)
    }

    override fun addRow() {
        val (index, row) = newRow(rows)
        rows.add(index, row)
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
}

/**
 * The Go text fields of the refactoring dialogs: a one-line editor over a [GoCodeFragments] fragment resolved in [context], so Go
 * completion offers the types, packages and values of that file's package (Ctrl+Space, and as you type). Error highlighting is off:
 * the fragment alone is not a Go file; the dialogs validate the text themselves.
 */
object GoSignatureTables {

    fun field(project: Project, context: GoFile?, kind: GoCodeFragments.Kind, text: String): EditorTextField {
        val fragment = GoCodeFragments.create(project, context, kind, text)
        DaemonCodeAnalyzer.getInstance(project).setHighlightingEnabled(fragment, false)
        val document = PsiDocumentManager.getInstance(project).getDocument(fragment) ?: return EditorTextField(text, project, GoFileType)
        return EditorTextField(document, project, GoFileType, false, true)
    }

    /**
     * [model] as a single-selection table whose edits are kept when focus leaves the cell (a click on Refactor right after typing must
     * not lose the text). Enter and F2 start editing the selected cell (not the dialog's default button), Enter again commits it.
     */
    fun table(model: GoRowsTableModel<*>): JBTable = JBTable(model).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        putClientProperty("terminateEditOnFocusLost", true)
        // Typing into a Go cell would start an edit whose first character the editor never sees: edits start by Enter, F2 or a double click.
        putClientProperty("JTable.autoStartsEdit", false)
        surrendersFocusOnKeystroke = true
        // JTable's own startEditing: on the table it edits the lead cell; inside an editor it commits the cell and returns to the table.
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "startEditing")
    }

    /** [table] with the add / remove / up / down toolbar (Alt+Insert, Alt+Delete, Alt+Up, Alt+Down come with it), [width] x [height] visible. */
    fun decorated(table: JBTable, width: Int, height: Int): JComponent {
        table.preferredScrollableViewportSize = JBUI.size(width, height)
        return ToolbarDecorator.createDecorator(table).createPanel()
    }

    /** Edits the cells of [column] with a Go [field] of [kind]. */
    fun goColumn(table: JBTable, column: Int, project: Project, context: GoFile?, kind: GoCodeFragments.Kind) {
        table.columnModel.getColumn(column).cellEditor = GoCellEditor(project, context, kind)
    }

    /** A table cell edited in a Go [field]; a mouse starts it on a double click, like the text cells. */
    private class GoCellEditor(private val project: Project, private val context: GoFile?, private val kind: GoCodeFragments.Kind) : AbstractTableCellEditor() {
        private var field: EditorTextField? = null

        override fun getTableCellEditorComponent(table: JTable, value: Any?, isSelected: Boolean, row: Int, column: Int): Component =
            field(project, context, kind, value?.toString() ?: "").also { field = it }

        override fun getCellEditorValue(): Any = field?.text ?: ""

        override fun isCellEditable(e: EventObject?): Boolean = e !is MouseEvent || e.clickCount >= 2
    }
}
