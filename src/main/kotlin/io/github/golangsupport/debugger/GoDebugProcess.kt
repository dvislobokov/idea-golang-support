package io.github.golangsupport.debugger

import com.google.gson.JsonObject
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.ui.XDebugTabLayouter
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.wm.ToolWindowManager
import io.github.golangsupport.build.BuildViewCommandOutput
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.cli.GoEnvironment
import io.github.golangsupport.cli.GoTool
import io.github.golangsupport.monitor.RunningGoProcesses
import io.github.golangsupport.run.DelveGoVersion
import io.github.golangsupport.run.GoEvaluate
import io.github.golangsupport.run.GoLaunchArguments
import io.github.golangsupport.settings.GoDebuggerConfigurable
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise
import java.io.File
import java.io.OutputStream
import java.io.Writer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

private val LOG = logger<GoDebugProcess>()

/**
 * A debug session of delve on the XDebugger API of the platform, which every IDE has; the DAP module of the platform is not needed.
 * The order of the protocol: `initialize`, `launch` / `attach`, then on the `initialized` event the breakpoints and `configurationDone`
 * (delve answers `launch` only after that: it builds the program in between, and its compiler errors come as `output` events).
 */
class GoDebugProcess(
    session: XDebugSession,
    private val adapter: DelveAdapter,
    private val start: DebugStart,
    trace: Writer?,
) : XDebugProcess(session), DapConnection.Listener {
    val connection = DapConnection(adapter.input, adapter.output, this, trace)
    private val handler = DebuggeeProcessHandler()
    private val lineBreakpoints = GoLineBreakpointHandler(this)
    private val panicBreakpoints = GoPanicBreakpointHandler(this)
    private val functionBreakpoints = GoFunctionBreakpointHandler(this)
    private val editors = GoEditorsProvider()

    @Volatile var capabilities: JsonObject = JsonObject()
        private set

    /** The breakpoints go to the adapter as they change only after the first full list, which is sent on `initialized`. */
    @Volatile var configured: Boolean = false
        private set

    /** The goroutine of the last stop: the one the steps and Resume are for. */
    @Volatile var stoppedThreadId: Int? = null
        private set

    @Volatile private var exitCode: Int? = null
    @Volatile private var started = false
    private val shutdown = AtomicBoolean()
    private val stopped = AsyncPromise<Any>()

    /** The temp binary delve was told to build (see GoLaunchArguments); removed when the session ends so nothing is left behind. */
    private val outputBinary: File? = (start.arguments["output"] as? String)?.let(::File)

    /** What delve printed before the program ran: the errors of the compiler, for the notification of a failed start. */
    private val startupOutput = StringBuilder()

    override fun getEditorsProvider(): XDebuggerEditorsProvider = editors
    override fun createTabLayouter(): XDebugTabLayouter = GoGoroutinesTabLayouter(this)
    override fun getBreakpointHandlers(): Array<XBreakpointHandler<*>> = arrayOf(lineBreakpoints, panicBreakpoints, functionBreakpoints)
    override fun doGetProcessHandler(): ProcessHandler = handler

    /**
     * A session started without an execution result gets a console from the platform that is attached to nothing. The output of the
     * program comes as `output` events (`outputMode: remote`), which the handler turns into its text.
     */
    override fun createConsole(): ExecutionConsole = super.createConsole().also { (it as? ConsoleView)?.attachToProcess(handler) }

    override fun sessionInitialized() {
        handler.startNotify()
        // what runs, as GoLand prints its commands: the adapter, the build delve does and the program it starts
        print("> ${adapter.description}\n", ProcessOutputTypes.SYSTEM)
        for (line in GoLaunchArguments.describe(start.arguments, start.attach)) print("> $line\n", ProcessOutputTypes.SYSTEM)
        connection.start()
        connection.request("initialize", json(
            "clientID" to "intellij", "clientName" to "IntelliJ Platform", "adapterID" to "go", "locale" to "en-us",
            "pathFormat" to "path", "linesStartAt1" to true, "columnsStartAt1" to true,
            "supportsVariableType" to true, "supportsVariablePaging" to true, "supportsRunInTerminalRequest" to false,
        )).thenCompose { answer ->
            capabilities = answer
            connection.request(if (start.attach) "attach" else "launch", DapConnection.GSON.toJsonTree(start.arguments))
        }.whenComplete { _, error ->
            if (error == null) {
                started = true
                refreshBinary()
                // delve sends no `process` event for an attach (seen live): the process is known from the request, when it is on this machine
                val pid = (start.arguments["processId"] as? Number)?.toLong()
                if (start.attach && pid != null && adapter !is RemoteDelve) RunningGoProcesses.getInstance(session.project).started("Debug: ${session.sessionName}", pid, handler)
            } else if (!shutdown.get()) startFailed(errorText(error))
        }
    }

    /**
     * The binary delve built (or removed) is told to the VFS at once: built into the project tree, it showed in the Project view only
     * when the file watcher or the next refresh on focus got to it, seconds after the session was running (seen live).
     */
    private fun refreshBinary() {
        var directory = outputBinary?.parentFile ?: return
        var known = LocalFileSystem.getInstance().findFileByIoFile(directory)
        // a custom directory made for this session is not in the VFS yet: the nearest one that is, with what is below it
        while (known == null) {
            directory = directory.parentFile ?: return
            known = LocalFileSystem.getInstance().findFileByIoFile(directory)
        }
        VfsUtil.markDirtyAndRefresh(true, directory != outputBinary.parentFile, true, known)
    }

    /**
     * The program has not started. The reason of delve and what the compiler said are in the console of the session already (they came
     * as `output` events) and go to the Build tool window as well, where the messages of the compiler are links to the code: the console
     * goes away with the session in some IDEs (seen live with the old client), a build task stays. The balloon says only that, in one line.
     */
    private fun startFailed(reason: String) {
        val output = synchronized(startupOutput) { startupOutput.toString().trim() }
        exitCode = 1
        print("Cannot start debugging: $reason\n", ProcessOutputTypes.STDERR)
        val mismatch = DelveGoVersion.find(reason + "\n" + output)
        val explanation = mismatch?.explain(GoEnvironment.quick().goVersion)
        val project = session.project
        val build = BuildViewCommandOutput(project, "Debug: ${start.name}")
        build.started(GoLaunchArguments.describe(start.arguments, start.attach).firstOrNull() ?: adapter.description, adapter.workDirectory ?: start.arguments["cwd"] as? String)
        if (output.isNotEmpty()) build.text(output + "\n", true)
        build.text(listOfNotNull(reason, explanation).joinToString("\n") + "\n", true)
        build.commandFinished(1)
        build.finished(false)
        val summary = reason.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "delve has not started the program"
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(GoCli.NOTIFICATION_GROUP)
            .createNotification("Debug has not started", listOfNotNull(summary, explanation).joinToString("<br>") + "<br>The output is in the Build window.", NotificationType.ERROR)
        notification.addAction(NotificationAction.createSimple("Show Build Window") { ToolWindowManager.getInstance(project).getToolWindow("Build")?.activate(null) })
        when (mismatch?.kind) {
            DelveGoVersion.Kind.GO_TOO_OLD -> notification.addAction(NotificationAction.createSimple("Download Go") { BrowserUtil.browse("https://go.dev/dl/") })
            DelveGoVersion.Kind.GO_TOO_NEW -> notification.addAction(NotificationAction.createSimpleExpiring("Update delve") { GoTool.DELVE.install(session.project) })
            null -> Unit
        }
        if (mismatch != null) notification.addAction(NotificationAction.createSimple("Configure...") { ShowSettingsUtil.getInstance().showSettingsDialog(session.project, GoDebuggerConfigurable::class.java) })
        notification.notify(session.project)
        session.stop()
    }

    // --- events of the adapter ---

    override fun event(event: String, body: JsonObject) {
        when (event) {
            "initialized" -> configure()
            "stopped" -> stoppedEvent(body)
            "continued" -> if (!session.isStopped) session.sessionResumed()
            "output" -> output(body)
            "breakpoint" -> body.getAsJsonObject("breakpoint")?.let(lineBreakpoints::update)
            "exited" -> exitCode = body.int("exitCode")
            // the program delve has started or attached to: CPU and memory of it in the Go Monitor, next to the runs
            "process" -> body.int("systemProcessId")?.let { pid -> RunningGoProcesses.getInstance(session.project).started("Debug: ${session.sessionName}", pid.toLong(), handler) }
            "terminated" -> AppExecutorUtil.getAppExecutorService().execute { shutdown(detach = false, programGone = true) }
        }
    }

    override fun closed() {
        // the adapter has exited or was killed: whatever state the session is in, it is over
        AppExecutorUtil.getAppExecutorService().execute { shutdown(detach = false, programGone = true) }
    }

    private fun configure() {
        // first: a breakpoint set while the lists below are on their way sends its file itself instead of waiting for the next change
        configured = true
        CompletableFuture.allOf(lineBreakpoints.sendAll(), panicBreakpoints.send(), functionBreakpoints.send())
            .handle { _, _ -> null }
            .thenCompose { connection.request("configurationDone") }
            .exceptionally { error -> LOG.info("configurationDone: ${errorText(error)}"); null }
    }

    private fun stoppedEvent(body: JsonObject) {
        val threadId = body.int("threadId")
        val reason = body.string("reason")
        stoppedThreadId = threadId
        lineBreakpoints.clearTemporary()
        val threads = connection.request("threads").thenApply { answer ->
            answer.objects("threads").mapNotNull { thread -> thread.int("id")?.let { it to thread.string("name").orEmpty() } }
        }
        val top = if (threadId == null) CompletableFuture.completedFuture(emptyList()) else stackTrace(threadId, 0, FIRST_FRAMES)
        threads.thenCombine(top) { list, frames -> list to frames }.whenComplete { result, error ->
            if (error != null) return@whenComplete LOG.info("Cannot show the stop: ${errorText(error)}")
            val (list, frames) = result
            // the goroutine of the event, not a guess: the ids of goroutines come in no particular order
            val active = threadId ?: list.firstOrNull()?.first
            val context = GoSuspendContext(this, list.ifEmpty { listOfNotNull(active?.let { it to "Goroutine $it" }) }, active, frames)
            if (reason == "exception" && threadId != null) printException(threadId)
            reached(context, body)
        }
    }

    /** A breakpoint of the IDE when the stop is one: the platform then logs, counts and decides whether to stay suspended. */
    private fun reached(context: GoSuspendContext, body: JsonObject) {
        val breakpoint = body.getAsJsonArray("hitBreakpointIds")?.firstNotNullOfOrNull { lineBreakpoints.find(it.asInt) ?: functionBreakpoints.find(it.asInt) }
            ?: if (body.string("reason") == "exception") panicBreakpoints.first() else null
        if (breakpoint == null) return session.positionReached(context)
        val expression = breakpoint.logExpressionObject?.expression?.takeIf { it.isNotBlank() }
        val logged = if (expression == null) CompletableFuture.completedFuture<String?>(null)
        else context.topFrameId?.let { frame -> evaluate(expression, frame, "repl").handle { answer, error -> answer?.string("result") ?: error?.let(::errorText) } }
            ?: CompletableFuture.completedFuture(null)
        logged.thenAccept { value -> if (!session.breakpointReached(breakpoint, value, context)) resume(context) }
    }

    /** A panic or a fatal throw: delve describes it in `exceptionInfo` (the value of the panic, the message of the runtime). */
    private fun printException(threadId: Int) {
        connection.request("exceptionInfo", json("threadId" to threadId)).thenAccept { info ->
            val id = info.string("exceptionId") ?: "panic"
            val description = info.string("description").orEmpty()
            print("$id: $description\n".trimStart(), ProcessOutputTypes.STDERR)
            info.getAsJsonObject("details")?.string("stackTrace")?.takeIf { it.isNotBlank() }?.let { print("$it\n", ProcessOutputTypes.STDERR) }
        }
    }

    private fun output(body: JsonObject) {
        val text = body.string("output") ?: return
        if (!started) synchronized(startupOutput) { if (startupOutput.length < MAX_STARTUP_OUTPUT) startupOutput.append(text) }
        when (body.string("category")) {
            "telemetry" -> Unit
            "stderr" -> print(text, ProcessOutputTypes.STDERR)
            "stdout", null -> print(text, ProcessOutputTypes.STDOUT)
            else -> print(text, ProcessOutputTypes.SYSTEM)
        }
    }

    fun print(text: String, type: Key<*>) = handler.notifyTextAvailable(text, type)

    // --- requests the frames and values make ---

    fun stackTrace(threadId: Int, startFrame: Int, levels: Int): CompletableFuture<List<JsonObject>> =
        connection.request("stackTrace", json("threadId" to threadId, "startFrame" to startFrame, "levels" to levels), REQUEST_TIMEOUT_MS)
            .thenApply { it.objects("stackFrames") }

    /** `order.Total()` goes to delve as `call order.Total()`, see [GoEvaluate]. */
    fun evaluate(expression: String, frameId: Int?, context: String): CompletableFuture<JsonObject> =
        connection.request("evaluate", json("expression" to GoEvaluate.expression(expression), "frameId" to frameId, "context" to context), EVALUATE_TIMEOUT_MS)

    // --- commands of the IDE ---

    private fun threadOf(context: XSuspendContext?): Int? = (context as? GoSuspendContext)?.activeThreadId ?: stoppedThreadId

    override fun resume(context: XSuspendContext?) {
        connection.request("continue", json("threadId" to (threadOf(context) ?: 0)))
    }

    override fun startStepOver(context: XSuspendContext?) = step("next", context)
    override fun startStepInto(context: XSuspendContext?) = step("stepIn", context)
    override fun startStepOut(context: XSuspendContext?) = step("stepOut", context)

    private fun step(command: String, context: XSuspendContext?) {
        val threadId = threadOf(context) ?: return
        connection.request(command, json("threadId" to threadId)).exceptionally { error ->
            session.reportMessage("${command.replaceFirstChar(Char::uppercase)} failed: ${errorText(error)}", MessageType.WARNING)
            null
        }
    }

    override fun startPausing() {
        connection.request("pause", json("threadId" to (stoppedThreadId ?: 0)))
    }

    /** Run to Cursor: DAP has no request of its own, so a breakpoint of one stop, sent with the others of its file. */
    override fun runToPosition(position: XSourcePosition, context: XSuspendContext?) {
        lineBreakpoints.runTo(position.file.path, position.line).thenRun { resume(context) }
    }

    override fun stopAsync(): Promise<Any> {
        AppExecutorUtil.getAppExecutorService().execute { shutdown(detach = start.attach, programGone = false) }
        return stopped
    }

    /**
     * The end of the session, once, whoever asks. A launched program is ended with `disconnect` (delve has no `terminate`); a process the
     * debugger was attached to is let go: continued first when it stands at a breakpoint, then `disconnect` without ending it. Then the
     * adapter is stopped, whatever it has answered.
     */
    private fun shutdown(detach: Boolean, programGone: Boolean) {
        if (!shutdown.compareAndSet(false, true)) return
        try {
            if (!programGone && !connection.isClosed) {
                if (detach || start.attach) {
                    if (session.isSuspended) runCatching { connection.request("continue", json("threadId" to (stoppedThreadId ?: 0)), SHORT_TIMEOUT_MS).get() }
                    runCatching { connection.request("disconnect", json("terminateDebuggee" to false), SHORT_TIMEOUT_MS).get() }
                } else {
                    if (capabilities.bool("supportsTerminateRequest") == true) runCatching { connection.request("terminate", null, SHORT_TIMEOUT_MS).get() }
                    runCatching { connection.request("disconnect", json("terminateDebuggee" to true), SHORT_TIMEOUT_MS).get() }
                }
            }
        } finally {
            connection.close()
            adapter.stop()
            handler.finish(exitCode)
            stopped.setResult(Unit)
            // the program has stopped by now, so the binary is unlocked; deleteOnExit covers a Windows handle that lingers
            outputBinary?.let { bin -> runCatching { if (bin.exists() && !bin.delete()) bin.deleteOnExit() } }
            refreshBinary()
        }
    }

    /** Stop and Detach of the IDE come here through the process handler of the session. */
    private inner class DebuggeeProcessHandler : ProcessHandler() {
        override fun destroyProcessImpl() {
            AppExecutorUtil.getAppExecutorService().execute { shutdown(detach = start.attach, programGone = false) }
        }

        override fun detachProcessImpl() {
            AppExecutorUtil.getAppExecutorService().execute { shutdown(detach = true, programGone = false) }
        }

        override fun detachIsDefault(): Boolean = start.attach
        override fun getProcessInput(): OutputStream? = null

        fun finish(code: Int?) {
            if (!isProcessTerminated) notifyProcessTerminated(code ?: 0)
        }
    }

    companion object {
        const val REQUEST_TIMEOUT_MS = 30_000L
        /** A `call` in an expression runs code of the program; one that never returns must not hold the session forever. */
        const val EVALUATE_TIMEOUT_MS = 30_000L
        private const val SHORT_TIMEOUT_MS = 3_000L
        const val FIRST_FRAMES = 20
        private const val MAX_STARTUP_OUTPUT = 4000

        /** The text of delve as it is, not the class of the exception around it. */
        fun errorText(error: Throwable): String {
            val cause = generateSequence(error) { it.cause }.firstOrNull { it is DapException || it is DapClosedException || it is java.util.concurrent.TimeoutException } ?: error
            return when (cause) {
                is java.util.concurrent.TimeoutException -> "The debugger has not answered in time"
                else -> cause.message ?: cause.javaClass.simpleName
            }
        }

        fun samePath(a: String, b: String): Boolean = FileUtil.pathsEqual(FileUtil.toSystemIndependentName(a), FileUtil.toSystemIndependentName(b))

        fun pathOf(fileUrl: String): String = VfsUtilCore.urlToPath(fileUrl)
    }
}
