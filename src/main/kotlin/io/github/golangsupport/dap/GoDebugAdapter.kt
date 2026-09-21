package io.github.golangsupport.dap

import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.OSProcessUtil
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.platform.dap.DapBreakpointsDescription
import com.intellij.platform.dap.DapClient
import com.intellij.platform.dap.DapCommandProcessor
import com.intellij.platform.dap.DapDebugSession
import com.intellij.platform.dap.DapEventConsumer
import com.intellij.platform.dap.DapLaunchArgumentsProvider
import com.intellij.platform.dap.DapStartRequest
import com.intellij.platform.dap.DebugAdapterDescriptor
import com.intellij.platform.dap.DebugAdapterId
import com.intellij.platform.dap.DebugAdapterSupportProvider
import com.intellij.platform.dap.LaunchRequestArguments
import com.intellij.platform.dap.connection.DebugAdapterHandle
import com.intellij.platform.dap.xdebugger.DapXDebugProcess
import com.intellij.xdebugger.XDebugSession
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.cli.GoTool
import io.github.golangsupport.run.BreakpointExtras
import io.github.golangsupport.run.DapMessageRewritingStream
import io.github.golangsupport.run.DapMessageWatchingStream
import io.github.golangsupport.run.DapSetBreakpoints
import io.github.golangsupport.run.DapStartWatcher
import io.github.golangsupport.run.DlvDap
import io.github.golangsupport.run.GoRunConfiguration
import io.github.golangsupport.settings.GoSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** delve; the id is what launch arguments and the adapter descriptor are matched by. */
object GoDebugAdapterId : DebugAdapterId("go", "Go")

/**
 * Makes the runner of the platform DAP client take Debug of a "Go" configuration and gives it the `launch` request.
 * The arguments themselves are made by the configuration (`GoLaunchArguments`), which knows nothing about DAP.
 */
class GoDapLaunchArgumentsProvider : DapLaunchArgumentsProvider {
    /** Debug only: the runner would take Run as well (a `noDebug` launch), and Run is the `go` command of the configuration itself. */
    override fun isApplicable(executorId: String, profile: RunProfile): Boolean = executorId == DefaultDebugExecutor.EXECUTOR_ID && profile is GoRunConfiguration

    override fun getLaunchArguments(project: Project, profile: RunProfile): LaunchRequestArguments =
        LaunchRequestArguments(GoDebugAdapterId, DapStartRequest.Launch, (profile as GoRunConfiguration).debugLaunchArguments())
}

class GoDebugAdapterSupportProvider : DebugAdapterSupportProvider<GoDebugAdapterId> {
    override val adapterId: GoDebugAdapterId get() = GoDebugAdapterId
    override fun createDebugAdapterDescriptor(project: Project): DebugAdapterDescriptor<GoDebugAdapterId> = GoDebugAdapterDescriptor(project)
}

/** `dlv dap`: a DAP server on a TCP port (delve has no stdio mode), one process per session; it compiles the program itself. */
class GoDebugAdapterDescriptor(private val project: Project) : DebugAdapterDescriptor<GoDebugAdapterId>() {
    override val id: GoDebugAdapterId get() = GoDebugAdapterId

    override val breakpointsDescription: DapBreakpointsDescription = DapBreakpointsDescription(GoLineBreakpointType::class.java, GoPanicBreakpointType::class.java)

    /** A descriptor is made for every session, so this is the stopped thread of one session. */
    private val stoppedThread = StoppedThread()

    override fun createClient(
        eventConsumer: DapEventConsumer, environment: ExecutionEnvironment, executionResult: ExecutionResult?, commandProcessor: DapCommandProcessor,
        sessionScope: CoroutineScope,
    ): DapClient = super.createClient(stoppedThread.recording(eventConsumer), environment, executionResult, commandProcessor, sessionScope)

    override suspend fun launchDebugAdapter(environment: ExecutionEnvironment, executionResult: ExecutionResult?, sessionId: String): DebugAdapterHandle {
        val delve = GoTool.DELVE.find()
        if (delve == null) {
            GoTool.DELVE.offerInstallation(project, "Debug")
            throw ExecutionException("The debugger is not installed: dlv is not found. Install it with: go install ${GoTool.DELVE.module}@latest")
        }
        val directory = (environment.runProfile as? GoRunConfiguration)?.packageDirectory()
        val log = GoDebuggerLogs.newAdapterLog()
        LOG.info("Starting $delve for session $sessionId in $directory, log: ${log ?: "off"}")
        val commandLine = GoCli.toolCommandLine(delve.path, directory, *DlvDap.arguments(log != null, GoSettings.getInstance().debugAnyGoVersion).toTypedArray())
        return withContext(Dispatchers.IO) {
            DelveHandle(commandLine, log, { path, line -> GoLineBreakpointType.extrasAt(project, path, line) }) { GoCli.notifyError(project, "Debug has not started", it) }
        }
    }

    override fun createXDebugProcess(
        session: XDebugSession, dapDebugSession: DapDebugSession, xDebugProcessScope: CoroutineScope, globalScope: CoroutineScope,
        debugAdapterDescriptor: DebugAdapterDescriptor<*>, executionEnvironment: ExecutionEnvironment, executionResult: ExecutionResult?,
        startRequestType: DapStartRequest, startRequestArguments: Map<String, Any?>,
    ): DapXDebugProcess = GoDebugProcess(
        session, dapDebugSession, xDebugProcessScope, globalScope, debugAdapterDescriptor, executionEnvironment, executionResult, startRequestType, startRequestArguments, stoppedThread,
    )

    private companion object {
        val LOG = logger<GoDebugAdapterDescriptor>()
    }
}

/**
 * The process of `dlv dap` and the connection to the port it announces on its first line. Stopping is a kill of the process tree, as
 * in the .NET sibling: the soft kill of the platform is Ctrl+C through a helper on Windows, and an adapter busy with a long request
 * answers nothing at all. The debuggee is a child of delve and goes with it.
 */
class DelveHandle(
    commandLine: GeneralCommandLine, log: File?, breakpointExtras: (path: String, line: Int) -> BreakpointExtras?, onStartFailed: (String) -> Unit,
) : DebugAdapterHandle {
    private val process: Process = commandLine.withRedirectErrorStream(true).createProcess()
    private val socket: Socket

    init {
        val listening = CompletableFuture<Pair<String, Int>>()
        val startup = StringBuffer()
        // the first line is the port; what follows (errors of delve itself) has to be drained, or the process blocks on a full pipe
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching {
                // the log of the session, when it is on; without it the lines are few and go to the log of the IDE
                val writer = log?.bufferedWriter()
                writer.use {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        if (!listening.isDone) {
                            startup.append(line).append('\n')
                            DlvDap.listeningAt(line)?.let(listening::complete)
                        }
                        if (writer == null) LOG.info("dlv: $line") else {
                            writer.appendLine(line)
                            writer.flush()
                        }
                    }
                }
            }
            listening.completeExceptionally(ExecutionException("dlv has exited before it started to listen:\n$startup"))
        }
        socket = try {
            val (host, port) = listening.get(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            Socket(host, port).apply { tcpNoDelay = true }
        } catch (e: Exception) {
            kill()
            throw (e.cause as? ExecutionException) ?: ExecutionException("Cannot connect to dlv: ${e.message}\n$startup", e)
        }
    }

    /**
     * What delve answers is watched for a failed `launch` / `attach` (the program does not compile, the Go version is not supported):
     * the client of the platform shows nothing then and leaves the session hanging at "Building...". The reason is reported and delve
     * is killed, which the client does notice.
     */
    private val startWatcher = DapStartWatcher(onStartFailed)

    override val input: InputStream = DapMessageWatchingStream(socket.getInputStream()) { body -> if (startWatcher.message(body)) kill() }

    /** What the client writes goes to delve with the hit counts and log messages of the breakpoints added, see [DapSetBreakpoints]. */
    override val output: OutputStream = DapMessageRewritingStream(socket.getOutputStream()) { body ->
        DapSetBreakpoints.rewrite(body, breakpointExtras) { LOG.warn("Cannot add hit counts and log messages to setBreakpoints", it) }
    }

    /** Called after the `disconnect` request of the protocol (or its timeout): a moment to exit by itself, then the whole process tree. */
    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        if (!process.waitFor(EXIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            LOG.info("dlv (pid ${process.pid()}) has not exited by itself, killing the process tree")
            kill()
        }
        runCatching { socket.close() }
        Unit
    }

    private fun kill() {
        if (!OSProcessUtil.killProcessTree(process)) process.destroyForcibly()
    }

    private companion object {
        const val START_TIMEOUT_SECONDS = 20L
        const val EXIT_TIMEOUT_MS = 1500L
        val LOG = logger<DelveHandle>()
    }
}
