package io.github.golangsupport.debugger

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import io.github.golangsupport.run.DlvDap
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private val LOG = logger<DelveProcess>()

/**
 * The process of `dlv dap` and the connection to the port it announces on its first line (delve has no stdio mode; `--listen=127.0.0.1:0`
 * makes it pick a free port). One process per session; it compiles the program itself. Stopped by killing the process tree: an adapter
 * busy with a long request answers nothing at all, `disconnect` included. The debuggee is a child of delve and goes with it.
 */
class DelveProcess(commandLine: GeneralCommandLine, log: File?) {
    private val process: Process = commandLine.withRedirectErrorStream(true).createProcess()
    private val socket: Socket

    val input: InputStream get() = socket.getInputStream()
    val output: OutputStream get() = socket.getOutputStream()
    val pid: Long get() = process.pid()
    val isAlive: Boolean get() = process.isAlive

    init {
        val listening = CompletableFuture<Pair<String, Int>>()
        val startup = StringBuffer()
        // the first line is the port; what follows (the log of delve itself) has to be drained, or the process blocks on a full pipe
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

    /** A moment to exit by itself after `disconnect`, then the whole process tree. */
    fun stop(graceMs: Long = 1500) {
        runCatching { socket.close() }
        if (process.waitFor(graceMs, TimeUnit.MILLISECONDS)) return
        LOG.info("dlv (pid ${process.pid()}) has not exited by itself, killing the process tree")
        kill()
    }

    private fun kill() {
        if (!OSProcessUtil.killProcessTree(process)) process.destroyForcibly()
    }

    private companion object {
        const val START_TIMEOUT_SECONDS = 20L
    }
}

/** What a debug session starts with: the `launch` of a program (delve builds it), or the `attach` to a process. */
class DebugStart(
    val attach: Boolean,
    /** The arguments of the request, as `GoLaunchArguments` makes them. */
    val arguments: Map<String, Any?>,
    val name: String,
)
