package io.github.golangsupport.monitor

import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.BaseProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.golangsupport.run.GoRunConfiguration
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * What can be monitored: a program started from the IDE (measured with its children, and with the telemetry of its runtime when the
 * configuration collects it) or any Go program of the machine (CPU and memory only, until a snapshot is taken with delve).
 */
class MonitorTarget(val pid: Long, val title: String, val withChildren: Boolean, val runtime: GoRuntimeState? = null, val process: GoProcess? = null) {
    override fun toString(): String = title
    override fun equals(other: Any?): Boolean = other is MonitorTarget && other.pid == pid
    override fun hashCode(): Int = pid.hashCode()
}

class MonitorSample(
    val cpuPercent: Double,
    val workingSetBytes: Long,
    val privateBytes: Long,
    val threads: Int?,
    val processes: Int,
    /** Null for a program without the telemetry of the runtime (started outside the IDE, or without the option). */
    val runtime: GoRuntimeState.Snapshot?,
)

/**
 * Samples one [target] every second until the process exits or the session is disposed. The operating system gives CPU and memory; the
 * runtime, when the program was started with `GODEBUG` traces, gives the heap, the collections and the scheduler. The callbacks are
 * invoked on a pooled thread.
 */
class GoMonitorSession(val target: MonitorTarget, private val onSample: (MonitorSample) -> Unit, private val onEnd: () -> Unit) : Disposable {
    private var task: ScheduledFuture<*>? = null
    private var previousCpu: Duration? = null
    private var previousTime = 0L
    @Volatile private var disposed = false

    /** The process the numbers belong to: the program behind `go run`, not the launcher. */
    @Volatile var applicationPid: Long = target.pid
        private set

    fun start() {
        task = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({ runCatching { tick() } }, 0, 1, TimeUnit.SECONDS)
    }

    private fun tick() {
        if (disposed) return
        val processes = if (target.withChildren) ProcessSampler.tree(target.pid) else ProcessHandle.of(target.pid).filter { it.isAlive }.map { listOf(it) }.orElse(emptyList())
        if (processes.isEmpty()) {
            dispose()
            onEnd()
            return
        }
        // `go run` builds first and starts the program afterwards, as its child: what is measured is the youngest process with its own children
        val application = pickApplication(processes)
        applicationPid = application.pid()
        val usage = ProcessSampler.sample(listOf(application) + application.descendants().filter { it.isAlive }.toList())
        val now = System.nanoTime()
        val cpu = previousCpu?.let { ProcessSampler.cpuPercent(it, usage.cpuTime, now - previousTime) } ?: 0.0
        previousCpu = usage.cpuTime
        previousTime = now
        onSample(MonitorSample(cpu, usage.workingSetBytes, usage.privateBytes, usage.threads, usage.processes, target.runtime?.takeIf { it.hasData() }?.read()))
    }

    override fun dispose() {
        disposed = true
        task?.cancel(false)
    }

    companion object {
        /**
         * The program: the root when it is not the go command (a run with telemetry starts the binary itself), else the youngest of its
         * children. Never `conhost.exe`: a console program on Windows has one as a child (seen live: the charts showed its 1 MB).
         */
        fun pickApplication(processes: List<ProcessHandle>): ProcessHandle {
            val root = processes.first()
            if (!isGoCommand(root)) return root
            return processes.drop(1).filter { !isConsoleHost(it) }.maxByOrNull { it.info().startInstant().map { start -> start.toEpochMilli() }.orElse(0) } ?: root
        }

        private fun isGoCommand(process: ProcessHandle): Boolean = executableName(process).let { it == "go" || it == "go.exe" }
        private fun isConsoleHost(process: ProcessHandle): Boolean = executableName(process) == "conhost.exe"
        private fun executableName(process: ProcessHandle): String = process.info().command().orElse("").substringAfterLast('\\').substringAfterLast('/').lowercase()
    }
}

/** The processes of our run configurations that are alive, the newest first, with the telemetry of their runtime when they have it. */
@Service(Service.Level.PROJECT)
class RunningGoProcesses {
    private val targets = CopyOnWriteArrayList<MonitorTarget>()
    private val listeners = CopyOnWriteArrayList<(MonitorTarget?) -> Unit>()

    fun targets(): List<MonitorTarget> = targets.toList()

    /** [listener] gets the started process, or null when one has exited. */
    fun subscribe(parent: Disposable, listener: (MonitorTarget?) -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    fun started(name: String, handler: ProcessHandler) {
        // a run with telemetry builds first: the program, and its id, come later
        if (handler is GoTelemetryProcessHandler) return handler.whenProgramStarted { pid -> add(name, pid, handler, handler.runtime) }
        // the handler of a debug session is not a process of the IDE: that program is added by the debugger when delve starts it
        val pid = runCatching { (handler as? BaseProcessHandler<*>)?.process?.pid() }.getOrNull() ?: return
        add(name, pid, handler, null)
    }

    private fun add(name: String, pid: Long, handler: ProcessHandler, runtime: GoRuntimeState?) {
        val target = MonitorTarget(pid, "$name ($pid)", withChildren = true, runtime = runtime)
        if (handler.isProcessTerminated) return
        targets.add(0, target)
        handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                targets.remove(target)
                listeners.forEach { it(null) }
            }
        })
        listeners.forEach { it(target) }
    }

    /**
     * A program the IDE has started without a process handler of its own: under the debugger delve starts it and says its id in the
     * `process` event. Returns what to call when the program is gone.
     */
    fun started(name: String, pid: Long): () -> Unit {
        val target = MonitorTarget(pid, "$name ($pid)", withChildren = false)
        targets.add(0, target)
        listeners.forEach { it(target) }
        return { if (targets.remove(target)) listeners.forEach { it(null) } }
    }

    companion object {
        fun getInstance(project: Project): RunningGoProcesses = project.service()
    }
}

class GoProcessStartListener(private val project: Project) : ExecutionListener {
    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        val configuration = env.runProfile as? GoRunConfiguration ?: return
        RunningGoProcesses.getInstance(project).started(configuration.name, handler)
    }
}
