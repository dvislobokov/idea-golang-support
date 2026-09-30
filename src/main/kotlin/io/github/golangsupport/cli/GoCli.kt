package io.github.golangsupport.cli

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VfsUtil
import io.github.golangsupport.build.BuildViewCommandOutput
import io.github.golangsupport.settings.GoSettings
import java.io.File
import java.nio.charset.StandardCharsets

/** Where the output of background commands goes while they run. Called on a background thread. */
interface CommandOutput {
    fun commandStarted(command: GeneralCommandLine)
    fun text(text: String, isError: Boolean)
    fun commandFinished(exitCode: Int)

    /** All commands are done, or the run has stopped at a failure or a cancellation. */
    fun finished(succeeded: Boolean) {}
}

object GoCli {
    private const val TIMEOUT_MS = 10 * 60 * 1000

    /** The executable to run: the one from Settings | Tools | Go, otherwise the auto-detected one. */
    fun findExecutable(): String? {
        val configured = GoSettings.getInstance().goPath.takeIf { it.isNotEmpty() && File(it).isFile }
        val found = configured ?: detectExecutable()
        // on change only: this is asked before every command and by every tool lookup
        if (LAST_GO.getAndSet(found ?: "-") != (found ?: "-")) {
            if (found != null) GoLog.LOG.info("go: $found (${if (configured != null) "the path from the settings" else "found by the plugin"})")
            else {
                GoLog.LOG.warn("go is not found: not on the PATH of the IDE, not in GOROOT and not in the usual installation directories")
                GoLog.describeSearchOnce()
            }
        }
        return found
    }

    private val LAST_GO = java.util.concurrent.atomic.AtomicReference("")

    /** PATH first, then GOROOT and the default installation directories. */
    fun detectExecutable(): String? {
        val name = executableName("go")
        PathEnvironmentVariableUtil.findInPath(name)?.let { return it.path }
        val home = System.getProperty("user.home")
        val wellKnown = listOfNotNull(System.getenv("GOROOT")?.let { "$it/bin/$name" }) + if (SystemInfo.isWindows) {
            listOfNotNull(System.getenv("ProgramFiles")?.let { "$it\\Go\\bin\\go.exe" }, "C:\\Go\\bin\\go.exe", "$home\\go\\go\\bin\\go.exe", "$home\\sdk\\go\\bin\\go.exe")
        } else {
            listOf("/usr/local/go/bin/go", "/usr/lib/go/bin/go", "/opt/homebrew/bin/go", "/usr/local/bin/go", "/snap/bin/go", "$home/sdk/go/bin/go")
        }
        return wellKnown.firstOrNull { File(it).canExecute() }
    }

    fun executableName(tool: String): String = if (SystemInfo.isWindows) "$tool.exe" else tool

    @Throws(ExecutionException::class)
    fun commandLine(workDirectory: String?, vararg arguments: String): GeneralCommandLine {
        val executable = findExecutable()
            ?: throw ExecutionException("The 'go' executable is not found. Install Go (https://go.dev/dl) and make sure it is on PATH, or set the path in Settings | Tools | Go.")
        return toolCommandLine(executable, workDirectory, *arguments)
    }

    /** A command line of `go` or of one of its tools: a tool started from the IDE must find the same `go` the plugin uses. */
    fun toolCommandLine(executable: String, workDirectory: String?, vararg arguments: String): GeneralCommandLine {
        val commandLine = GeneralCommandLine(executable).withParameters(*arguments).withWorkDirectory(workDirectory).withCharset(StandardCharsets.UTF_8)
        val goDirectory = findExecutable()?.let { File(it).parent }
        if (goDirectory != null && PathEnvironmentVariableUtil.findInPath(executableName("go")) == null) {
            commandLine.withEnvironment("PATH", goDirectory + File.pathSeparator + System.getenv("PATH").orEmpty())
        }
        return commandLine
    }

    /** The command line for logs and progress texts. */
    fun displayString(command: GeneralCommandLine): String =
        (listOf(File(command.exePath).nameWithoutExtension) + command.parametersList.list).joinToString(" ") { if (' ' in it) "\"$it\"" else it }

    /** Runs a short command and captures its output. Must not be called on EDT. */
    @Throws(ExecutionException::class)
    fun execute(commandLine: GeneralCommandLine, timeoutMs: Int = TIMEOUT_MS): ProcessOutput = CapturingProcessHandler(commandLine).runProcess(timeoutMs)

    /**
     * Runs [commands] one after another in a background task, streaming what they print into [output] as it arrives;
     * stops at the first failure and reports it. [refresh] are re-read from disk afterwards, then [onSuccess] is invoked on EDT.
     * Without an explicit [output] the commands show up as a task of the Build tool window.
     */
    fun runInBackground(
        project: Project,
        title: String,
        commands: List<GeneralCommandLine>,
        refresh: List<File> = emptyList(),
        output: CommandOutput = BuildViewCommandOutput(project, title),
        /** Called on the background thread with the output of the failed command; true when it has reported the failure itself. */
        onFailure: (ProcessOutput) -> Boolean = { false },
        onSuccess: () -> Unit = {},
    ) {
        // callers that had to block to build the commands come from a pooled thread
        val application = ApplicationManager.getApplication()
        if (!application.isDispatchThread) {
            application.invokeLater({ runInBackground(project, title, commands, refresh, output, onFailure, onSuccess) }, project.disposed)
            return
        }
        // saving documents and starting a task are for EDT
        FileDocumentManager.getInstance().saveAllDocuments()
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                val succeeded = runCommands(indicator)
                output.finished(succeeded)
                if (refresh.isNotEmpty()) VfsUtil.markDirtyAndRefresh(false, true, true, *refresh.toTypedArray())
                if (succeeded) ApplicationManager.getApplication().invokeLater(onSuccess, project.disposed)
            }

            private fun runCommands(indicator: ProgressIndicator): Boolean {
                for (command in commands) {
                    if (indicator.isCanceled) return false
                    indicator.text2 = displayString(command)
                    output.commandStarted(command)
                    val result = try {
                        val handler = CapturingProcessHandler(command)
                        handler.addProcessListener(object : ProcessListener {
                            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                                if (outputType !== ProcessOutputTypes.SYSTEM) output.text(event.text, outputType === ProcessOutputTypes.STDERR)
                            }
                        })
                        // cancelling the progress kills the process
                        handler.runProcessWithProgressIndicator(indicator, TIMEOUT_MS, true)
                    } catch (e: ExecutionException) {
                        output.text(e.message.orEmpty() + "\n", true)
                        notifyError(project, title, e.message.orEmpty())
                        return false
                    }
                    output.commandFinished(result.exitCode)
                    if (result.isCancelled) return false
                    if (result.exitCode != 0) {
                        if (onFailure(result)) return false
                        val details = (result.stderr.ifBlank { result.stdout }).trim().lines().takeLast(15).joinToString("\n")
                        notifyError(project, "$title: exit code ${result.exitCode}", details)
                        return false
                    }
                }
                return true
            }
        })
    }

    /** Command lines cannot be built without Go; reports that instead of throwing. */
    fun commandLinesOrNotify(project: Project, title: String, build: () -> List<GeneralCommandLine>): List<GeneralCommandLine>? = try {
        build()
    } catch (e: ExecutionException) {
        notifyError(project, title, e.message.orEmpty())
        null
    }

    fun notifyError(project: Project?, title: String, content: String) = notify(project, title, content, NotificationType.ERROR)
    fun notifyInfo(project: Project?, title: String, content: String = "") = notify(project, title, content, NotificationType.INFORMATION)

    private fun notify(project: Project?, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, content.replace("\n", "<br>"), type)
            .notify(project)
    }

    const val NOTIFICATION_GROUP = "Go"
}
