package io.github.golangsupport.debugger

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
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * What to look at when a debug session misbehaves, all in `<log directory of the IDE>/delve`: the log of delve itself
 * (`dlv dap --log --log-output=dap,debugger`, one file per session) and, when switched on, every DAP message both ways (`protocol/`).
 */
object GoDebuggerLogs {
    /** The messages of the protocol, per session; takes effect from the next session. */
    const val PROTOCOL_TRACE_KEY = "go.debugger.protocol.trace"
    private const val KEEP = 20
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    val directory: Path get() = Path.of(PathManager.getLogPath(), "delve")
    val protocolDirectory: Path get() = directory.resolve("protocol")

    fun adapterLogName(time: LocalDateTime): String = "dlv-${STAMP.format(time)}.log"

    /** The oldest of the logs beyond [keep]; the names sort by time. */
    fun outdated(names: List<String>, keep: Int = KEEP, prefix: String = "dlv-"): List<String> =
        names.filter { it.startsWith(prefix) && it.endsWith(".log") }.sortedDescending().drop(keep)

    /** The file for a new session, or null when the log is switched off (Settings | Tools | Go) or the directory cannot be made. */
    fun newAdapterLog(): File? {
        if (!GoSettings.getInstance().debugAdapterLog) return null
        return newFile(directory, "dlv-")
    }

    /** Where the messages of a new session go, or null when the trace is off. */
    fun newProtocolTrace(): Writer? {
        if (!Registry.`is`(PROTOCOL_TRACE_KEY, false)) return null
        return newFile(protocolDirectory, "protocol-")?.bufferedWriter()
    }

    private fun newFile(directory: Path, prefix: String): File? = try {
        Files.createDirectories(directory)
        outdated(directory.toFile().list().orEmpty().toList(), KEEP - 1, prefix).forEach { directory.resolve(it).toFile().delete() }
        directory.resolve("$prefix${STAMP.format(LocalDateTime.now())}.log").toFile()
    } catch (_: java.io.IOException) {
        null
    }
}

class ShowDebuggerLogsAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        RevealFileAction.openDirectory(Files.createDirectories(GoDebuggerLogs.directory))
    }
}

/** Every DAP message of the sessions that start from now on, next to the debugger logs. */
class TraceDebuggerProtocolAction : ToggleAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun isSelected(e: AnActionEvent): Boolean = Registry.`is`(GoDebuggerLogs.PROTOCOL_TRACE_KEY, false)
    override fun setSelected(e: AnActionEvent, state: Boolean) = Registry.get(GoDebuggerLogs.PROTOCOL_TRACE_KEY).setValue(state)
}
