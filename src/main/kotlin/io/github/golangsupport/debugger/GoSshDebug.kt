package io.github.golangsupport.debugger

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.cli.GoPluginLog
import io.github.golangsupport.run.DlvDap
import io.github.golangsupport.run.GoCommand
import io.github.golangsupport.run.GoRunConfiguration
import io.github.golangsupport.run.GoSsh
import io.github.golangsupport.settings.GoSettings
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private val LOG = logger<SshDelve>()

/**
 * Debug of a `go run` / `go test` configuration on an SSH host (see [GoSsh] for the plan). Everything here blocks: it runs in the
 * background task of [GoDebugRunner], and each step says what it does in the progress text.
 */
object GoSshDebug {
    /** What a session needs from the host: the adapter, already connected, and the `launch` of the copied program. */
    @Throws(ExecutionException::class)
    fun prepare(configuration: GoRunConfiguration, indicator: ProgressIndicator): Pair<DelveAdapter, DebugStart> {
        val options = configuration.options
        val host = options.sshHost.orEmpty().trim()
        val ssh = GoSsh.executable() ?: throw ExecutionException("ssh is not found: install the OpenSSH client")

        indicator.text = "Connecting to $host"
        val probe = run(GoSsh.commandLine(ssh, host, GoSsh.PROBE_SCRIPT), null, 30_000)
        if (probe.exit != 0) throw ExecutionException("Cannot run commands on $host over ssh: ${GoSsh.failure(probe.output)}")
        val target = GoSsh.probe(probe.output) ?: throw ExecutionException("$host is not a platform delve debugs: ${GoSsh.failure(probe.output)}")
        val directory = GoSsh.directory(options.sshDirectory, target.home)
        GoPluginLog.info(GoDebuggerLogs.CATEGORY, "SSH debug on $host: ${target.goos}/${target.goarch}, directory $directory")

        indicator.checkCanceled()
        val delve = GoBundledDelve.crossBuilt(target.goos, target.goarch, indicator)
        val remoteDelve = "$directory/${delve.name}-${GoBundledDelve.sources()?.let(GoBundledDelve::hash).orEmpty().take(12)}"

        indicator.checkCanceled()
        indicator.text = "Building ${configuration.name} for ${target.goos}/${target.goarch}"
        val test = options.command == GoCommand.TEST
        val local = Files.createTempDirectory("go-ssh-").toFile()
        try {
            val program = File(local, GoSsh.programName(configuration.packageDirectory(), test))
            val build = CapturingProcessHandler(configuration.sshBuildCommandLine(program, target.goos, target.goarch)).runProcessWithProgressIndicator(indicator, 600_000)
            indicator.checkCanceled()
            if (build.exitCode != 0 || !program.isFile) {
                throw ExecutionException("The build for ${target.goos}/${target.goarch} has failed:\n" + (build.stderr + build.stdout).trim().lines().take(30).joinToString("\n"))
            }

            if (run(GoSsh.commandLine(ssh, host, GoSsh.existsScript(remoteDelve)), null, 30_000).exit != 0) {
                indicator.text = "Copying delve to $host"
                upload(ssh, host, delve, remoteDelve)
            }
            indicator.checkCanceled()
            indicator.text = "Copying ${configuration.name} to $host"
            val remoteProgram = "$directory/${program.name}"
            upload(ssh, host, program, remoteProgram)

            indicator.checkCanceled()
            indicator.text = "Starting delve on $host"
            val adapter = SshDelve(ssh, host, remoteDelve, directory, options.sshDelvePort)
            return adapter to DebugStart(attach = false, arguments = configuration.sshLaunchArguments(remoteProgram, directory), name = configuration.name)
        } finally {
            local.deleteRecursively()
        }
    }

    private fun upload(ssh: String, host: String, file: File, path: String) {
        val result = run(GoSsh.commandLine(ssh, host, GoSsh.uploadScript(path)), file, 600_000)
        if (result.exit != 0) throw ExecutionException("Cannot copy ${file.name} to $host:$path: ${GoSsh.failure(result.output)}")
    }

    class Result(val exit: Int, val output: String)

    /** A short ssh command with [input] as its stdin; the output and the errors together. */
    fun run(commandLine: GeneralCommandLine, input: File?, timeoutMs: Long): Result {
        val process = commandLine.withRedirectErrorStream(true).createProcess()
        val output = CompletableFuture.supplyAsync({ process.inputStream.readAllBytes().toString(Charsets.UTF_8) }, { ApplicationManager.getApplication().executeOnPooledThread(it) })
        process.outputStream.use { stream -> input?.inputStream()?.use { it.copyTo(stream) } }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw ExecutionException("ssh has not finished in ${timeoutMs / 1000} s: ${GoCli.displayString(commandLine)}")
        }
        return Result(process.exitValue(), runCatching { output.get(5, TimeUnit.SECONDS) }.getOrDefault(""))
    }
}

/**
 * `dlv dap` on an SSH host and the connection to it: one ssh runs delve there (its output is the port line and the log of delve), another
 * (`ssh -W`) is the connection. Stopping closes both; the first one's end makes the script there kill delve ([GoSsh.serverScript]).
 */
class SshDelve(ssh: String, private val host: String, delve: String, directory: String, port: Int) : DelveAdapter {
    private val log = GoDebuggerLogs.newAdapterLog()
    private val server: Process = GoSsh.commandLine(ssh, host, GoSsh.serverScript(delve, directory, port, GoSettings.getInstance().debugAnyGoVersion, log != null))
        .withRedirectErrorStream(true).createProcess()
    private val tunnel: Process
    private val tunnelErrors = StringBuffer()

    override val description: String = "dlv dap on $host (through ssh)"
    override val isLocal: Boolean get() = false
    override val input: InputStream get() = tunnel.inputStream
    override val output: OutputStream get() = tunnel.outputStream

    init {
        val listening = CompletableFuture<Int>()
        val startup = StringBuffer()
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching {
                log?.bufferedWriter().use { writer ->
                    server.inputStream.bufferedReader().forEachLine { line ->
                        if (!listening.isDone) {
                            startup.append(line).append('\n')
                            DlvDap.listeningAt(line)?.let { listening.complete(it.second) }
                            if (GoSsh.portTaken(line)) listening.completeExceptionally(ExecutionException("Port $port on $host is in use: choose another port of dlv dap in the configuration, or 0 for a free one"))
                        }
                        if (writer == null) LOG.info("dlv@$host: $line") else {
                            writer.appendLine(line)
                            writer.flush()
                        }
                    }
                }
            }
            listening.completeExceptionally(ExecutionException("dlv on $host has exited before it started to listen:\n$startup"))
        }
        val remotePort = try {
            listening.get(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            stopServer(0)
            throw (e.cause as? ExecutionException) ?: ExecutionException("dlv on $host has not started: ${e.message}\n$startup", e)
        }
        GoPluginLog.info(GoDebuggerLogs.CATEGORY, "dlv dap on $host listens on 127.0.0.1:$remotePort")
        tunnel = GoSsh.tunnelCommandLine(ssh, host, remotePort).createProcess()
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { tunnel.errorStream.bufferedReader().forEachLine { tunnelErrors.append(it).append('\n'); LOG.info("ssh -W $host: $it") } }
        }
        // a refused forward (AllowTcpForwarding no) ends ssh at once; a live one stays: give it the time of one round trip
        if (tunnel.waitFor(TUNNEL_CHECK_MS, TimeUnit.MILLISECONDS)) {
            stopServer(0)
            throw ExecutionException("ssh cannot connect to dlv on $host (127.0.0.1:$remotePort): ${GoSsh.failure(tunnelErrors.toString())}. The SSH server must allow TCP forwarding")
        }
    }

    override fun stop(graceMs: Long) {
        runCatching { tunnel.outputStream.close() }
        tunnel.destroy()
        stopServer(graceMs)
    }

    private fun stopServer(graceMs: Long) {
        // the end of stdin there is what kills delve (see GoSsh.serverScript); the local ssh is not the remote process
        runCatching { server.outputStream.close() }
        if (!server.waitFor(graceMs, TimeUnit.MILLISECONDS)) server.destroy()
    }

    private companion object {
        const val START_TIMEOUT_SECONDS = 30L
        const val TUNNEL_CHECK_MS = 1500L
    }
}
