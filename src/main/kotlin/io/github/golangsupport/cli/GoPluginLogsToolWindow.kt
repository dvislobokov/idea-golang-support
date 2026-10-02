package io.github.golangsupport.cli

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import java.nio.file.Files

/**
 * Menu Go | Plugin Logs: the journal of the plugin ([GoPluginLog]) as it is written, nothing of the IDE in it. The window is not on the
 * stripe until it is asked for: a log is for the day something does not work.
 */
class GoPluginLogsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = GoPluginLogsPanel(project, toolWindow.disposable)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "", false))
    }

    override fun shouldBeAvailable(project: Project): Boolean = false

    companion object {
        const val ID = "Go Plugin Logs"

        fun show(project: Project) {
            val manager = ToolWindowManager.getInstance(project)
            val window = manager.getToolWindow(ID) ?: manager.registerToolWindow(ID) {
                anchor = ToolWindowAnchor.BOTTOM
                icon = AllIcons.Debugger.Console
                contentFactory = GoPluginLogsToolWindowFactory()
            }
            if (!window.isAvailable) window.setAvailable(true, null)
            window.activate(null)
        }
    }
}

/** The lines of the journal in a console: errors red, warnings in the colour of warnings, with the search and the filters of a console. */
class GoPluginLogsPanel(project: Project, parentDisposable: Disposable) : SimpleToolWindowPanel(false, true) {
    private val console: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
    private var problemsOnly = false

    init {
        Disposer.register(parentDisposable, console)
        setContent(console.component)
        val clear = object : AnAction("Clear", "Clear the window; the file of the journal keeps everything", AllIcons.Actions.GC), DumbAware {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) = console.clear()
        }
        val problems = object : ToggleAction("Warnings and Errors Only", "Hide the lines of the level INFO", AllIcons.General.Warning), DumbAware {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent): Boolean = problemsOnly
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                problemsOnly = state
                reprint()
            }
        }
        val folder = object : AnAction("Open Logs Folder", "~/idea-golang-logs: this journal, every go command with its output, the logs of delve", AllIcons.Nodes.Folder), DumbAware {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            override fun actionPerformed(e: AnActionEvent) = RevealFileAction.openDirectory(Files.createDirectories(GoLogs.root))
        }
        toolbar = ActionManager.getInstance().createActionToolbar("GoPluginLogs", DefaultActionGroup(clear, problems, folder), false)
            .also { it.targetComponent = console.component }.component
        GoPluginLog.subscribe(parentDisposable) { print(it) }
    }

    private fun print(entry: LogEntry) {
        if (problemsOnly && entry.level == LogLevel.INFO) return
        console.print(entry.format() + "\n", contentType(entry.level))
    }

    /** The console prints from any thread; a change of the filter prints the journal from the start. */
    private fun reprint() {
        console.clear()
        GoPluginLog.entries.forEach(::print)
    }

    companion object {
        fun contentType(level: LogLevel): ConsoleViewContentType = when (level) {
            LogLevel.INFO -> ConsoleViewContentType.NORMAL_OUTPUT
            LogLevel.WARN -> ConsoleViewContentType.LOG_WARNING_OUTPUT
            LogLevel.ERROR -> ConsoleViewContentType.ERROR_OUTPUT
        }
    }
}

/** Menu Go | Plugin Logs. */
class ShowGoPluginLogsAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        GoPluginLogsToolWindowFactory.show(e.project ?: return)
    }
}
