package io.github.golangsupport.build

import com.intellij.build.BuildViewManager
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.FilePosition
import com.intellij.build.events.MessageEvent
import com.intellij.build.events.impl.FailureResultImpl
import com.intellij.build.events.impl.FileMessageEventImpl
import com.intellij.build.events.impl.FinishBuildEventImpl
import com.intellij.build.events.impl.OutputBuildEventImpl
import com.intellij.build.events.impl.StartBuildEventImpl
import com.intellij.build.events.impl.SuccessResultImpl
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.github.golangsupport.cli.CommandOutput
import io.github.golangsupport.cli.GoCli
import java.io.File

/** `file.go:12:5: undefined: foo` of the compiler, of `go vet` and of most linters; line and column are one-based, the column may be missing. */
data class GoBuildMessage(val file: String, val line: Int, val column: Int, val text: String) {
    /** The paths are relative to the directory the command ran in. */
    fun resolveFile(workDirectory: String?): File = File(file).let { if (it.isAbsolute || workDirectory == null) it else File(workDirectory, file) }
}

object GoBuildOutputParser {
    // `vet: ` prefixes the type errors vet hits before its own checks; a drive letter must not be taken for the end of the path
    private val MESSAGE = Regex("""^(?:vet: )?((?:[A-Za-z]:)?[^:]+\.go):(\d+)(?::(\d+))?: (.+)$""")

    fun parseLine(line: String): GoBuildMessage? {
        val match = MESSAGE.matchEntire(line.trim()) ?: return null
        val (file, lineNumber, column, text) = match.destructured
        return GoBuildMessage(file, lineNumber.toInt(), column.toIntOrNull() ?: 1, text)
    }

    /** `# example.com/app/store` before the messages of a package, `# example.com/app/store [example.com/app/store.test]` for its tests. */
    fun packageOf(line: String): String? = line.takeIf { it.startsWith("# ") }?.removePrefix("# ")?.substringBefore(' ')?.takeIf { it.isNotEmpty() }
}

/**
 * Shows background `go` commands (build, vet, mod tidy, tool installation, ...) as a task of the Build tool window: the output appears
 * while the command runs, the messages of the compiler become navigable nodes, the window opens by itself only on a failure.
 */
class BuildViewCommandOutput(private val project: Project, private val title: String) : CommandOutput {
    private val buildId = Any()
    private val pending = StringBuilder()
    private var started = false
    private var failed = false
    private var workDirectory: String? = null
    private var kind = MessageEvent.Kind.ERROR

    private fun view(): BuildViewManager = project.service()

    override fun commandStarted(command: GeneralCommandLine) {
        if (project.isDisposed) return
        workDirectory = command.workDirectory?.path
        // what vet finds does not stop a build
        kind = if (command.parametersList.list.firstOrNull() == "vet") MessageEvent.Kind.WARNING else MessageEvent.Kind.ERROR
        if (!started) {
            started = true
            val descriptor = DefaultBuildDescriptor(buildId, title, workDirectory.orEmpty(), System.currentTimeMillis()).apply {
                isActivateToolWindowWhenAdded = false
                isActivateToolWindowWhenFailed = true
            }
            view().onEvent(buildId, StartBuildEventImpl(descriptor, "running..."))
        }
        view().onEvent(buildId, OutputBuildEventImpl(buildId, "> ${GoCli.displayString(command)}\n", true))
    }

    override fun text(text: String, isError: Boolean) {
        if (project.isDisposed || !started) return
        view().onEvent(buildId, OutputBuildEventImpl(buildId, text, !isError))
        pending.append(text)
        while (true) {
            val lineEnd = pending.indexOf("\n")
            if (lineEnd < 0) break
            reportMessage(pending.substring(0, lineEnd).trimEnd('\r'))
            pending.delete(0, lineEnd + 1)
        }
    }

    private fun reportMessage(line: String) {
        val message = GoBuildOutputParser.parseLine(line) ?: return
        val position = FilePosition(message.resolveFile(workDirectory), (message.line - 1).coerceAtLeast(0), (message.column - 1).coerceAtLeast(0))
        view().onEvent(buildId, FileMessageEventImpl(buildId, kind, "Go", message.text, line.trim(), position))
    }

    override fun commandFinished(exitCode: Int) {
        if (exitCode != 0) failed = true
        if (!project.isDisposed && started) view().onEvent(buildId, OutputBuildEventImpl(buildId, "\n", true))
    }

    override fun finished(succeeded: Boolean) {
        if (project.isDisposed || !started) return
        val ok = succeeded && !failed
        view().onEvent(buildId, FinishBuildEventImpl(buildId, null, System.currentTimeMillis(), if (ok) "finished" else "failed", if (ok) SuccessResultImpl() else FailureResultImpl()))
    }
}
