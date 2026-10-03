package io.github.golangsupport.mod

import io.github.golangsupport.lang.GoProjectPresence
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.golangsupport.GoIcons
import io.github.golangsupport.build.BuildViewCommandOutput
import io.github.golangsupport.cli.CommandOutput
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.cli.GoTool
import java.awt.BorderLayout
import java.awt.event.MouseEvent
import java.io.File
import java.io.StringReader
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.AbstractTableModel

/** One line of `go list -m -u -json all`: a requirement of the module, with the newer version the proxy knows, when there is one. */
data class GoModuleInfo(
    val path: String,
    val version: String?,
    val update: String?,
    val indirect: Boolean,
    val isMain: Boolean,
    val replacedBy: String?,
    val directory: String?,
) {
    /** `path@version`: what `go get` takes. */
    fun updateTarget(): String? = update?.let { "$path@$it" }
}

/** Reads the stream of JSON objects `go list -m -json` prints (one after another, not an array). */
object GoModuleList {
    fun parse(text: String): List<GoModuleInfo> {
        val result = ArrayList<GoModuleInfo>()
        val reader = JsonReader(StringReader(text)).apply { isLenient = true }
        while (runCatching { reader.peek() != JsonToken.END_DOCUMENT }.getOrDefault(false)) {
            val element = runCatching { JsonParser.parseReader(reader) }.getOrNull() ?: break
            val module = element as? JsonObject ?: continue
            val replace = module.getAsJsonObject("Replace")
            result += GoModuleInfo(
                path = module.string("Path") ?: continue,
                version = module.string("Version"),
                update = module.getAsJsonObject("Update")?.string("Version"),
                indirect = module.get("Indirect")?.asBoolean == true,
                isMain = module.get("Main")?.asBoolean == true,
                replacedBy = replace?.let { listOfNotNull(it.string("Path"), it.string("Version")).joinToString(" ") },
                directory = module.string("Dir"),
            )
        }
        return result
    }

    /** `Found in: golang.org/x/text@v0.3.7` of the text report of govulncheck: the modules a vulnerability reaches through. */
    fun vulnerableModules(govulncheckOutput: String): Map<String, String> =
        Regex("""Found in: (\S+)@(\S+)""").findAll(govulncheckOutput).associate { it.groupValues[1] to it.groupValues[2] }

    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
}

class GoDependenciesToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = GoDependenciesPanel(project)
        toolWindow.contentManager.addContent(toolWindow.contentManager.factory.createContent(panel, "", false).apply { isCloseable = false })
    }

    override fun shouldBeAvailable(project: Project): Boolean = GoProjectPresence.hasGoFiles(project)

    companion object {
        const val ID = "Go Dependencies"
        fun show(project: Project) = ToolWindowManager.getInstance(project).getToolWindow(ID)?.activate(null)
    }
}

/** Menu Go | Modules | Dependencies...: the window with the requirements, their updates and their vulnerabilities. */
class GoDependenciesAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let(GoDependenciesToolWindowFactory::show)
    }
}

/**
 * The requirements of a module as a table: version, the newer one the proxy has (`go list -m -u`), direct or indirect, replacement,
 * and whether govulncheck names it. Upgrade runs `go get path@version`, then `go mod tidy` is a click away; the go.mod is re-read afterwards.
 */
class GoDependenciesPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {
    private val modules = ComboBox<GoModule>()
    private var rows: List<GoModuleInfo> = emptyList()
    private var vulnerable: Map<String, String> = emptyMap()
    private val model = object : AbstractTableModel() {
        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = COLUMNS.size
        override fun getColumnName(column: Int): String = COLUMNS[column]
        override fun getValueAt(row: Int, column: Int): Any = rows[row]
    }
    private val table = JBTable(model)
    private val status = JBLabel()

    init {
        modules.renderer = SimpleListCellRenderer.create("") { it.path }
        table.setDefaultRenderer(Any::class.java, object : ColoredTableCellRenderer() {
            override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
                val item = value as? GoModuleInfo ?: return
                val vulnerableVersion = vulnerable[item.path]
                when (column) {
                    0 -> { icon = if (vulnerableVersion != null) AllIcons.General.Warning else if (item.indirect) GoIcons.IndirectPackage else GoIcons.Package; append(item.path) }
                    1 -> append(item.version.orEmpty(), if (vulnerableVersion != null) SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, VULNERABLE) else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    2 -> item.update?.let { append(it, SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, UPDATE)) }
                    3 -> append(if (item.indirect) "indirect" else "direct", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    4 -> item.replacedBy?.let { append("=> $it", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES) }
                    5 -> if (vulnerableVersion != null) append("vulnerable", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, VULNERABLE))
                }
            }
        })
        table.columnModel.getColumn(0).preferredWidth = JBUI.scale(360)
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val item = selected().firstOrNull() ?: return false
                openInGoMod(item)
                return true
            }
        }.installOn(table)
        modules.addActionListener { reload() }

        val actions = DefaultActionGroup(
            action("Refresh", AllIcons.Actions.Refresh, { true }) { reload() },
            action("Upgrade Selected", AllIcons.Actions.Download, { selected().any { it.update != null } }) { upgrade(selected().mapNotNull { it.updateTarget() }) },
            action("Upgrade All", AllIcons.Actions.Compile, { rows.any { it.update != null } }) { upgrade(rows.mapNotNull { it.updateTarget() }) },
            action("Tidy", GoIcons.Module, { module() != null }) { runGo("Go Mod Tidy", listOf("mod", "tidy")) },
            action("Check Vulnerabilities", AllIcons.General.InspectionsEye, { module() != null }) { vulncheck() },
            action("Open go.mod", AllIcons.Actions.EditSource, { module() != null }) { module()?.let { OpenFileDescriptor(project, it.modFile).navigate(true) } },
        )
        toolbar = ActionManager.getInstance().createActionToolbar("GoDependencies", actions, true).also { it.targetComponent = table }.component
        setContent(JPanel(BorderLayout()).apply {
            add(JPanel(BorderLayout()).apply { add(JBLabel("Module: "), BorderLayout.WEST); add(modules, BorderLayout.CENTER); border = JBUI.Borders.empty(2, 6) }, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER)
            add(status.apply { border = JBUI.Borders.empty(4, 8) }, BorderLayout.SOUTH)
        })
        reloadModules()
    }

    private fun module(): GoModule? = modules.selectedItem as? GoModule
    private fun selected(): List<GoModuleInfo> = table.selectedRows.map { rows[table.convertRowIndexToModel(it)] }

    private fun reloadModules() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val found = ReadAction.compute<List<GoModule>, RuntimeException> { if (project.isDisposed) emptyList() else GoModulesService.getInstance(project).modules() }
            ApplicationManager.getApplication().invokeLater({
                val current = module()?.root
                modules.removeAllItems()
                found.forEach(modules::addItem)
                found.firstOrNull { it.root == current }?.let { modules.selectedItem = it }
                if (found.isEmpty()) status.text = "No go.mod in the project"
            }, ModalityState.any())
        }
    }

    /** `go list -m -u -json all`: asks the proxy about every requirement, so it takes a moment and needs the network. */
    private fun reload() {
        val module = module() ?: return
        status.text = "Asking the proxy for updates of ${module.path}…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val output = runCatching { GoCli.execute(GoCli.commandLine(module.root.path, "list", "-m", "-u", "-json", "all"), 120_000) }
            val parsed = output.getOrNull()?.takeIf { it.exitCode == 0 || it.stdout.isNotBlank() }?.let { GoModuleList.parse(it.stdout) }
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                if (parsed == null) {
                    status.text = "go list has failed: " + (output.exceptionOrNull()?.message ?: output.getOrNull()?.stderr?.lines()?.firstOrNull { it.isNotBlank() }).orEmpty()
                    return@invokeLater
                }
                rows = parsed.filter { !it.isMain }
                model.fireTableDataChanged()
                val updates = rows.count { it.update != null }
                status.text = "${rows.size} requirements, ${rows.count { !it.indirect }} direct; " + if (updates == 0) "all up to date" else "$updates with a newer version"
            }, ModalityState.any())
        }
    }

    private fun upgrade(targets: List<String>) {
        if (targets.isEmpty()) return
        runGo("Go Get " + targets.joinToString(" "), listOf("get") + targets)
    }

    private fun runGo(title: String, arguments: List<String>) {
        val module = module() ?: return
        val commands = GoCli.commandLinesOrNotify(project, title) { listOf(GoCli.commandLine(module.root.path, *arguments.toTypedArray())) } ?: return
        GoCli.runInBackground(project, title, commands, refresh = listOf(File(module.root.path)), onSuccess = { reload() })
    }

    /** govulncheck over the module: the modules its report names get a mark; the whole report is in the Build window. */
    private fun vulncheck() {
        val module = module() ?: return
        val tool = GoTool.GOVULNCHECK.find() ?: return GoTool.GOVULNCHECK.offerInstallation(project, "Check Vulnerabilities") { vulncheck() }
        status.text = "Running govulncheck…"
        val report = StringBuilder()
        val build = BuildViewCommandOutput(project, "govulncheck")
        val output = object : CommandOutput {
            override fun commandStarted(command: GeneralCommandLine) = build.commandStarted(command)
            override fun text(text: String, isError: Boolean) { report.append(text); build.text(text, isError) }
            override fun commandFinished(exitCode: Int) = build.commandFinished(exitCode)
            override fun finished(succeeded: Boolean) {
                // exit code 3 means "vulnerabilities found": a result, not a failure
                build.finished(true)
                val found = GoModuleList.vulnerableModules(report.toString())
                ApplicationManager.getApplication().invokeLater({
                    vulnerable = found
                    model.fireTableDataChanged()
                    status.text = if (found.isEmpty()) "govulncheck: no known vulnerabilities reachable from ${module.path}" else "govulncheck: ${found.size} vulnerable modules, the report is in the Build window"
                }, ModalityState.any())
            }
        }
        GoCli.runInBackground(project, "govulncheck", listOf(GoCli.toolCommandLine(tool.path, module.root.path, "./...")), output = output, onFailure = { true })
    }

    private fun openInGoMod(item: GoModuleInfo) {
        val module = module() ?: return
        val line = module.content.requires.firstOrNull { it.path == item.path }?.line ?: 0
        OpenFileDescriptor(project, module.modFile, line, 0).navigate(true)
    }

    private fun action(text: String, icon: Icon, enabled: () -> Boolean, perform: () -> Unit): AnAction = object : AnAction(text, null, icon), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = enabled()
        }

        override fun actionPerformed(e: AnActionEvent) = perform()
    }

    private companion object {
        val COLUMNS = listOf("Module", "Version", "Newer", "Kind", "Replaced by", "Vulnerabilities")
        val UPDATE = JBColor(0x1E8449, 0x5FAD65)
        val VULNERABLE = JBColor(0xC7222B, 0xE55765)
    }
}
