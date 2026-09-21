package io.github.golangsupport.dap

import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.registry.Registry
import io.github.golangsupport.settings.GoSettings
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * What to look at when a debug session misbehaves, all in `<log directory of the IDE>/delve`: the log of delve itself
 * (`dlv dap --log --log-output=dap,debugger`, one file per session) and, when switched on, the DAP messages as the platform client traces them.
 */
object GoDebuggerLogs {
    /** The key of the platform DAP client: a directory for the traces, empty for none. */
    const val PROTOCOL_TRACE_KEY = "dap.message.trace.dir"
    private const val KEEP = 20
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    val directory: Path get() = Path.of(PathManager.getLogPath(), "delve")
    val protocolDirectory: Path get() = directory.resolve("protocol")

    fun adapterLogName(time: LocalDateTime): String = "dlv-${STAMP.format(time)}.log"

    /** The oldest of the logs beyond [keep]; the names sort by time. */
    fun outdated(names: List<String>, keep: Int = KEEP): List<String> = names.filter { it.startsWith("dlv-") && it.endsWith(".log") }.sortedDescending().drop(keep)

    /** The file for a new session, or null when the log is switched off (Settings | Tools | Go) or the directory cannot be made. */
    fun newAdapterLog(): File? {
        if (!GoSettings.getInstance().debugAdapterLog) return null
        return try {
            val directory = Files.createDirectories(directory)
            outdated(directory.toFile().list().orEmpty().toList(), KEEP - 1).forEach { directory.resolve(it).toFile().delete() }
            directory.resolve(adapterLogName(LocalDateTime.now())).toFile()
        } catch (_: java.io.IOException) {
            null
        }
    }
}

class ShowDebuggerLogsAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        RevealFileAction.openDirectory(Files.createDirectories(GoDebuggerLogs.directory))
    }
}

/** Points the trace of the platform DAP client at the directory of the debugger logs; takes effect from the next session. */
class TraceDebuggerProtocolAction : ToggleAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun isSelected(e: AnActionEvent): Boolean = Registry.stringValue(GoDebuggerLogs.PROTOCOL_TRACE_KEY).isNotBlank()

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val directory = if (state) Files.createDirectories(GoDebuggerLogs.protocolDirectory).toString() else ""
        Registry.get(GoDebuggerLogs.PROTOCOL_TRACE_KEY).setValue(directory)
    }
}
