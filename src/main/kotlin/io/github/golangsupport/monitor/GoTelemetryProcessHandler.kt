package io.github.golangsupport.monitor

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.util.Key
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A run with the telemetry of the runtime, as one process of the Run window: first `go build -o` (its output goes to the console; a
 * failed build ends the run with its exit code), then the program with `GODEBUG` traces. The lines of the runtime (`gc 4 @1.7s ...`,
 * `SCHED 1000ms: ...`) go to [runtime] for the monitor and not to the console, which would drown in them; everything else the program
 * writes passes as it is. A line may come in pieces: stderr is kept until its end of line before the check. Nothing here blocks the
 * thread that starts the run (the platform starts a run on EDT).
 */
class GoTelemetryProcessHandler(private val build: GeneralCommandLine, private val program: GeneralCommandLine, private val onExit: () -> Unit = {}) : ProcessHandler() {
    val runtime = GoRuntimeState()
    private val pending = StringBuilder()
    @Volatile private var current: ProcessHandler? = null
    @Volatile private var destroyed = false
    private val programListeners = CopyOnWriteArrayList<(Long) -> Unit>()

    /** The id of the program once it runs; null while it is being built. */
    @Volatile var programPid: Long? = null
        private set

    /** [callback] gets the id of the program when it starts, or at once when it has. */
    fun whenProgramStarted(callback: (Long) -> Unit) {
        programPid?.let { return callback(it) }
        programListeners += callback
        programPid?.let { programListeners.remove(callback); callback(it) }
    }

    override fun startNotify() {
        super.startNotify()
        val handler = OSProcessHandler(build)
        current = handler
        notifyTextAvailable(build.commandLineString + "\n", ProcessOutputTypes.SYSTEM)
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) = notifyTextAvailable(event.text, outputType)
            override fun processTerminated(event: ProcessEvent) {
                if (event.exitCode == 0 && !destroyed) startProgram() else finish(event.exitCode)
            }
        })
        handler.startNotify()
    }

    private fun startProgram() {
        val handler = object : KillableColoredProcessHandler(program) {
            override fun coloredTextAvailable(text: String, attributes: Key<*>) = forward(text, attributes)
        }
        current = handler
        val pid = handler.process.pid()
        programPid = pid
        programListeners.forEach { it(pid) }
        programListeners.clear()
        handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) = finish(event.exitCode)
        })
        handler.startNotify()
    }

    /** What the program writes: the traces of the runtime taken out of stderr, the rest to the console. */
    private fun forward(text: String, attributes: Key<*>) {
        if (!ProcessOutputType.isStderr(attributes)) return notifyTextAvailable(text, attributes)
        synchronized(pending) {
            pending.append(text)
            while (true) {
                val end = pending.indexOf("\n")
                if (end < 0) break
                val line = pending.substring(0, end + 1)
                pending.delete(0, end + 1)
                val event = GoRuntimeTrace.parse(line)
                if (event != null) runtime.accept(event) else notifyTextAvailable(line, attributes)
            }
        }
    }

    private fun finish(exitCode: Int) {
        // a last line without its end: shown, whatever it is
        synchronized(pending) {
            if (pending.isNotEmpty()) {
                notifyTextAvailable(pending.toString(), ProcessOutputTypes.STDERR)
                pending.setLength(0)
            }
        }
        runCatching { onExit() }
        notifyProcessTerminated(exitCode)
    }

    override fun destroyProcessImpl() {
        destroyed = true
        current?.destroyProcess() ?: notifyProcessTerminated(-1)
    }

    override fun detachProcessImpl() {
        current?.detachProcess() ?: notifyProcessDetached()
    }

    override fun detachIsDefault(): Boolean = false
    override fun getProcessInput(): OutputStream? = current?.processInput
}
