package io.github.golangsupport.run

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import java.io.File
import javax.swing.JComponent

/**
 * "Files to copy" of a configuration as a table: the file or directory here (relative to the package directory), where it goes there
 * (empty: the same relative path). Stored as the `local[=there]` lines of [GoRunConfigurationOptions.sshFiles] ([GoSsh.fileEntries]).
 */
class GoSshFilesTable(private val project: Project, private val packageDirectory: () -> String?) {
    class Row(var local: String = "", var remote: String = "")

    private val model = ListTableModel<Row>(
        column("Here (relative to the package)", Row::local) { row, value -> row.local = value },
        column("There (relative to where it runs; empty: the same)", Row::remote) { row, value -> row.remote = value },
    )
    private val table = TableView(model).apply { setShowGrid(false); visibleRowCount = 4 }

    val component: JComponent = ToolbarDecorator.createDecorator(table)
        .setAddAction { add() }
        .setRemoveAction {
            if (table.isEditing) table.cellEditor?.cancelCellEditing()
            table.selectedRows.sortedDescending().forEach { model.removeRow(table.convertRowIndexToModel(it)) }
        }
        .disableUpDownActions()
        .createPanel()

    var text: String?
        get() {
            if (table.isEditing) table.cellEditor?.stopCellEditing()
            return model.items.filter { it.local.isNotBlank() }
                .joinToString("\n") { it.local.trim() + if (it.remote.isBlank()) "" else "=" + it.remote.trim() }.ifEmpty { null }
        }
        set(value) {
            model.items = GoSsh.fileEntries(value).map { Row(it.local, it.remote.orEmpty()) }
        }

    /** Files and directories picked in the project, relative to the package directory (`certs/ca.pem`, `../shared/app.yaml`). */
    private fun add() {
        val base = packageDirectory()?.let { File(it).let { f -> if (f.isFile) f.parentFile else f } }
        val descriptor = FileChooserDescriptor(true, true, false, false, false, true).withTitle("Files to Copy to the SSH Host")
        val start = base?.let { LocalFileSystem.getInstance().findFileByIoFile(it) }
        FileChooser.chooseFiles(descriptor, project, start) { files ->
            for (file in files) {
                val path = base?.let { FileUtil.getRelativePath(it, File(file.path)) }?.replace('\\', '/') ?: file.path
                if (model.items.none { it.local == path }) model.addRow(Row(path))
            }
        }
    }

    private fun column(name: String, get: (Row) -> String, set: (Row, String) -> Unit) = object : ColumnInfo<Row, String>(name) {
        override fun valueOf(item: Row): String = get(item)
        override fun isCellEditable(item: Row): Boolean = true
        override fun setValue(item: Row, value: String?) = set(item, value.orEmpty())
    }
}
