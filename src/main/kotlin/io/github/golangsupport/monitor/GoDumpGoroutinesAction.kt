package io.github.golangsupport.monitor

import com.intellij.execution.process.UnixProcessManager
import com.intellij.execution.ui.ConsoleView
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.SystemInfo
import com.intellij.unscramble.AnalyzeStacktraceUtil
import com.intellij.xdebugger.XDebuggerManager
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.cli.GoPluginLog
import io.github.golangsupport.cli.GoTool
import io.github.golangsupport.debugger.GoDebugProcess
import io.github.golangsupport.settings.GoSettings
import java.awt.BorderLayout
import java.util.concurrent.TimeUnit
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Run | Dump Goroutines (GoLand's `DlvDumpAction`): every goroutine with its stack, in a console tab of the Run tool window. A paused Go debug
 * session answers through its own delve; a running program of our configurations gets a delve attach for a moment (Windows, or the setting),
 * or SIGQUIT, which makes the Go runtime print all goroutines to the program's console and exit.
 */
class GoDumpGoroutinesAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project ?: return run { e.presentation.isEnabledAndVisible = false }
        val availability = availability(project, e)
        e.presentation.isEnabled = availability.enabled
        e.presentation.description = availability.description
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val availability = availability(project, e)
        when (availability.source) {
            GoDumpSource.DEBUG_SESSION -> debugProcess(project)?.let { GoGoroutineDumps.fromDebugSession(project, it) }
            GoDumpSource.PROCESS -> (selectedTarget(project, e) ?: runningTargets(project).singleOrNull())?.let { GoGoroutineDumps.fromProcess(project, it) }
            GoDumpSource.CHOOSE -> JBPopupFactory.getInstance().createPopupChooserBuilder(runningTargets(project)).setTitle("Dump Goroutines of")
                .setItemChosenCallback { GoGoroutineDumps.fromProcess(project, it) }.createPopup().showInBestPositionFor(e.dataContext)
            GoDumpSource.NONE -> Unit
        }
    }

    private fun availability(project: Project, e: AnActionEvent): GoDumpAvailability {
        val session = XDebuggerManager.getInstance(project).currentSession?.takeIf { it.debugProcess is GoDebugProcess && !it.isStopped }
        return GoDumpGoroutines.availability(session != null, session?.isSuspended == true, selectedTarget(project, e) != null, runningTargets(project).size)
    }

    private fun debugProcess(project: Project): GoDebugProcess? =
        XDebuggerManager.getInstance(project).currentSession?.takeIf { it.isSuspended }?.debugProcess as? GoDebugProcess

    private fun selectedTarget(project: Project, e: AnActionEvent): MonitorTarget? {
        val handler = e.getData(LangDataKeys.RUN_CONTENT_DESCRIPTOR)?.processHandler ?: return null
        return runningTargets(project).firstOrNull { it.handler === handler }
    }

    /** The programs of our run configurations, not the debugged ones: delve holds those already (and they come without children). */
    private fun runningTargets(project: Project): List<MonitorTarget> =
        RunningGoProcesses.getInstance(project).targets().filter { it.withChildren && it.handler?.isProcessTerminated == false }
}

/** Taking the dump and showing it. */
object GoGoroutineDumps {
    private const val CATEGORY = "debugger"

    fun fromDebugSession(project: Project, process: GoDebugProcess) {
        val depth = GoSettings.getInstance().debugStackTraceDepth
        object : Task.Backgroundable(project, "Dump goroutines", true) {
            override fun run(indicator: ProgressIndicator) {
                val goroutines = try {
                    GoGoroutineDump.collect(depth) { command, arguments -> process.connection.request(command, arguments).get(15, TimeUnit.SECONDS) }
                } catch (_: ProcessCanceledException) {
                    return
                } catch (e: Exception) {
                    return GoCli.notifyError(project, "Dump Goroutines", GoPluginLog.describe(e))
                }
                show(project, "debug session", goroutines)
            }
        }.queue()
    }

    fun fromProcess(project: Project, target: MonitorTarget) {
        val title = target.title
        if (!GoDumpGoroutines.viaDelve(GoSettings.getInstance().debugDumpViaDelve, SystemInfo.isWindows)) return sigquit(project, target)
        val delve = GoTool.DELVE.find() ?: return GoTool.DELVE.offerInstallation(project, "Dump Goroutines")
        val depth = GoSettings.getInstance().debugStackTraceDepth
        object : Task.Backgroundable(project, "Dump goroutines of $title", true) {
            override fun run(indicator: ProgressIndicator) {
                val goroutines = try {
                    GoSnapshot.attachAndDump(delve.path, programPid(target), depth)
                } catch (_: ProcessCanceledException) {
                    return
                } catch (e: Exception) {
                    GoPluginLog.warn(CATEGORY, "Dump Goroutines of $title: ${GoPluginLog.describe(e)}")
                    return GoCli.notifyError(project, "Dump Goroutines of $title", e.message ?: e.javaClass.simpleName)
                }
                show(project, title, goroutines)
            }
        }.queue()
    }

    /** The program behind `go run` / `go test`, not the go command: the youngest process of the tree, as the Go Monitor picks it. */
    private fun programPid(target: MonitorTarget): Long =
        if (!target.withChildren) target.pid else ProcessSampler.tree(target.pid).takeIf { it.isNotEmpty() }?.let { GoMonitorSession.pickApplication(it).pid() } ?: target.pid

    /** SIGQUIT: the runtime prints every goroutine to the program's stderr (the console of the run) and the program exits with status 2. */
    private fun sigquit(project: Project, target: MonitorTarget) {
        val answer = Messages.showYesNoDialog(project, "SIGQUIT makes the Go runtime print all goroutines to the console of ${target.title}, and then the program exits.\n" +
            "To keep it running, turn on \"Dump goroutines via delve attach\" in Settings | Go | Debugger.", "Dump Goroutines", "Send SIGQUIT", "Cancel", Messages.getQuestionIcon())
        if (answer != Messages.YES) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val pid = programPid(target)
            val result = UnixProcessManager.sendSignal(pid.toInt(), UnixProcessManager.SIGQUIT)
            if (result != 0) GoCli.notifyError(project, "Dump Goroutines", "SIGQUIT could not be sent to process $pid (code $result)")
        }
    }

    private fun show(project: Project, title: String, goroutines: List<GoGoroutine>) {
        val text = "# " + GoGoroutineDump.summary(goroutines) + "\n\n" + GoGoroutineDump.format(goroutines)
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            AnalyzeStacktraceUtil.addConsole(project, ConsoleFactory, "Goroutines: $title", text, AllIcons.Actions.Dump)
        }, project.disposed)
    }

    /** The console with the toolbar the platform fills (soft wraps, close), as its thread dumps have. */
    private object ConsoleFactory : AnalyzeStacktraceUtil.ConsoleFactory {
        override fun createConsoleComponent(consoleView: ConsoleView?, toolbarActions: DefaultActionGroup?): JComponent = JPanel(BorderLayout()).apply {
            val console = consoleView?.component ?: return@apply
            val toolbar = ActionManager.getInstance().createActionToolbar("GoGoroutineDump", toolbarActions ?: DefaultActionGroup(), false)
            toolbar.targetComponent = console
            add(toolbar.component, BorderLayout.WEST)
            add(console, BorderLayout.CENTER)
        }
    }
}
