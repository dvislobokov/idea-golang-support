package io.github.golangsupport.cli

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.DumbAware
import java.io.File
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Every log of the plugin, in one folder of the home directory the user can find and send: `~/idea-golang-logs`, with `plugin` (the
 * journal, [GoPluginLog]), `commands` (every `go` command and tool the plugin runs, with its output) and `delve` (the logs of the
 * debugger, [io.github.golangsupport.debugger.GoDebuggerLogs]) in it. Not the log directory of the IDE: that one is per IDE and version,
 * and hard to find (the .NET plugin went the same way, asked for by the user).
 */
object GoLogs {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    val root: Path
        get() = if (ApplicationManager.getApplication()?.isUnitTestMode == true) Path.of(PathManager.getTempPath(), "idea-golang-logs")
        else Path.of(System.getProperty("user.home"), "idea-golang-logs")

    fun directory(name: String): Path = root.resolve(name)

    val commandsDirectory: Path get() = directory("commands")
    val pluginDirectory: Path get() = directory("plugin")

    private val commands = DailyLog("commands", "commands-")

    fun commandLogName(day: LocalDate): String = commands.fileName(day)

    /** The lines of one command in the log: when it started, what it printed and when, how it ended. Timestamps show where it stood still. */
    fun line(time: LocalTime, tag: String, text: String): String = "${TIME.format(time)} [$tag] $text"

    /** Appends to the log of today, flushed at once: a command that hangs must have its lines on disk while it hangs. */
    fun command(tag: String, text: String) {
        val now = LocalTime.now()
        commands.append(text.trimEnd('\n', '\r').lines().joinToString("") { line(now, tag, it.trimEnd('\r')) + "\n" })
    }

    fun commandStarted(tag: String, command: GeneralCommandLine) {
        val text = "> ${GoCli.displayString(command)}   (in ${command.workDirectory?.path ?: "."})"
        command(tag, text)
        GoPluginLog.info(CATEGORY_COMMANDS, "$tag: $text")
    }

    /** How a command ended, in the command log and in the journal: a failure carries the last lines the command printed, nothing else does. */
    fun commandFinished(tag: String, result: String, failed: Boolean, lastLines: String = "") {
        command(tag, result + if (failed && lastLines.isNotBlank()) "\n$lastLines" else "")
        if (failed) GoPluginLog.warn(CATEGORY_COMMANDS, "$tag: $result" + if (lastLines.isNotBlank()) "\n$lastLines" else "")
        else GoPluginLog.info(CATEGORY_COMMANDS, "$tag: $result")
    }

    /** `exit code 1 in 2.3 s`: how a command ended, for the logs and the journal. */
    fun result(exitCode: Int, startedAt: Long, cancelled: Boolean = false, timedOut: Boolean = false): String {
        val seconds = "%.1f s".format(java.util.Locale.ROOT, (System.currentTimeMillis() - startedAt) / 1000.0)
        return when {
            cancelled -> "cancelled after $seconds"
            timedOut -> "timed out after $seconds"
            else -> "exit code $exitCode in $seconds"
        }
    }

    /** The last lines of what a failed command printed: its error stream, or its output when it said nothing there. */
    fun tail(stdout: String, stderr: String, lines: Int = 15): String = stderr.ifBlank { stdout }.trim().lines().takeLast(lines).joinToString("\n")

    /** The category of the journal for the commands: what ran, where, and how it ended; their output is in `commands`. */
    const val CATEGORY_COMMANDS = "go"
}

/**
 * A log with a file per day under [GoLogs.root]`/`[directory], `<prefix><yyyyMMdd>.log`, kept for two weeks. Appended and flushed at
 * once, so that what hangs or crashes has its lines on disk.
 */
class DailyLog(private val directory: String, private val prefix: String) {
    private val lock = Any()
    private var writer: Writer? = null
    private var writerDay: LocalDate? = null

    fun fileName(day: LocalDate): String = "$prefix${DAY.format(day)}.log"

    fun append(text: String) {
        synchronized(lock) {
            try {
                val out = writer() ?: return
                out.write(text)
                out.flush()
            } catch (e: java.io.IOException) {
                GoLog.LOG.info("Cannot write the log $directory: ${e.message}")
                writer = null
            }
        }
    }

    private fun writer(): Writer? {
        val today = LocalDate.now()
        if (writer != null && writerDay == today) return writer
        runCatching { writer?.close() }
        val folder = GoLogs.directory(directory)
        Files.createDirectories(folder)
        outdated(folder.toFile().list().orEmpty().toList(), today).forEach { File(folder.toFile(), it).delete() }
        writerDay = today
        writer = Files.newBufferedWriter(folder.resolve(fileName(today)), Charsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
        return writer
    }

    /** The files of this log older than two weeks. */
    fun outdated(names: List<String>, today: LocalDate): List<String> {
        val oldest = fileName(today.minusDays(KEEP_DAYS.toLong()))
        return names.filter { it.startsWith(prefix) && it.endsWith(".log") && it < oldest }
    }

    private companion object {
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        const val KEEP_DAYS = 14
    }
}

/** Menu Go | Open Logs Folder: the folder with every log of the plugin. */
class OpenGoLogsFolderAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        RevealFileAction.openDirectory(Files.createDirectories(GoLogs.root))
    }
}
