package io.github.golangsupport.debugger

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.cli.GoPluginLog
import io.github.golangsupport.run.GoCommand
import io.github.golangsupport.run.GoRunConfiguration
import io.github.golangsupport.run.GoSsh
import io.github.golangsupport.run.GoTarWriter
import io.github.golangsupport.settings.GoSettings
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private val LOG = logger<SshDelve>()

/**
 * Debug of a `go run` / `go test` configuration on an SSH host (see [GoSsh] for the plan and the layout there). Everything here blocks: it
 * runs in the background task of [GoDebugRunner], and each step says what it does in the progress text.
 */
object GoSshDebug {
    /** What a session needs from the host: the adapter, already connected, and the `launch` of the copied program. */
    @Throws(ExecutionException::class)
    fun prepare(configuration: GoRunConfiguration, indicator: ProgressIndicator): Pair<DelveAdapter, DebugStart> {
        val options = configuration.options
        val host = options.sshHost.orEmpty().trim()
        GoSsh.invalidHost(host)?.let { throw ExecutionException(it) }
        val ssh = GoSsh.executable() ?: throw ExecutionException("ssh is not found: install the OpenSSH client")
        val test = options.command == GoCommand.TEST

        indicator.text = "Connecting to $host"
        val probe = run(GoSsh.commandLine(ssh, host, GoSsh.PROBE_SCRIPT), null, 30_000)
        if (probe.exit != 0) throw ExecutionException("Cannot run commands on $host over ssh: ${GoSsh.failure(probe.output)}")
        val target = GoSsh.probe(probe.output) ?: throw ExecutionException("$host is not a platform delve debugs: ${GoSsh.failure(probe.output)}")
        val directory = GoSsh.directory(options.sshDirectory, target.home, configuration.project.name)
        val runDirectory = GoSsh.runDirectory(directory, configuration.packageDirectory(), test)
        GoPluginLog.info(GoDebuggerLogs.CATEGORY, "SSH debug on $host: ${target.goos}/${target.goarch}, directory $directory")

        val setup = run(GoSsh.commandLine(ssh, host, GoSsh.setupScript(directory, runDirectory, own = options.sshDirectory.isNullOrBlank())), null, 30_000)
        if (GoSsh.UNSAFE_MARK in setup.output) {
            throw ExecutionException("$directory on $host is writable by other users, who could replace the delve that runs as you: make it private (chmod 700) or choose another directory")
        }
        if (setup.exit != 0) throw ExecutionException("Cannot prepare $directory on $host: ${GoSsh.failure(setup.output)}")

        indicator.checkCanceled()
        val delve = GoBundledDelve.crossBuilt(target.goos, target.goarch, indicator)
        val remoteDelve = GoSsh.delvePath(directory, delve.name, GoBundledDelve.sources()?.let(GoBundledDelve::hash).orEmpty())

        indicator.checkCanceled()
        indicator.text = "Building ${configuration.name} for ${target.goos}/${target.goarch}"
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
            val remoteProgram = "$runDirectory/${program.name}"
            upload(ssh, host, program, remoteProgram)

            indicator.checkCanceled()
            copyFiles(configuration, ssh, host, runDirectory, test, indicator)

            indicator.checkCanceled()
            indicator.text = "Starting delve on $host"
            val adapter = SshDelve(ssh, host, remoteDelve, directory)
            return adapter to DebugStart(attach = false, arguments = configuration.sshLaunchArguments(remoteProgram, runDirectory), name = configuration.name)
        } finally {
            local.deleteRecursively()
        }
    }

    /** A file here and its place there, relative to the run directory. */
    class LocalFile(val file: File, val remote: String)

    /** "Files to copy" and the `testdata` of a tested package, file by file with their paths there; directories walked. */
    fun filesToCopy(configuration: GoRunConfiguration, test: Boolean): List<LocalFile> {
        val roots = GoSsh.fileEntries(configuration.options.sshFiles).map { entry ->
            configuration.sshFileProblem(entry)?.let { throw ExecutionException(it) }
            val file = configuration.sshLocalFile(entry.local)
            file to GoSsh.remotePath(entry.remote, GoSsh.defaultRemote(entry.local, file.name))!!
        } + listOfNotNull(File(configuration.packageDirectory(), "testdata").takeIf { test && configuration.options.sshCopyTestdata && it.isDirectory }?.let { it to "testdata" })
        val project = configuration.sshProjectRoot()
        return roots.flatMap { (root, remote) ->
            if (root.isFile) listOf(LocalFile(root, remote))
            else root.walkTopDown().filter { it.isFile }.map { LocalFile(it, remote + "/" + it.relativeTo(root).invariantSeparatorsPath) }.toList()
        }.onEach { file ->
            // a link inside a copied directory may point anywhere: what is sent is what it points to
            if (project == null || !GoSsh.isInside(file.file.toPath(), project)) throw ExecutionException("A file to copy is outside the project (a link?): ${file.file.path}")
        }.distinctBy { it.remote }
    }

    /** One tar stream for all the files, sent only when the set differs from the last copy into [runDirectory] (see [GoSsh.fingerprint]). */
    private fun copyFiles(configuration: GoRunConfiguration, ssh: String, host: String, runDirectory: String, test: Boolean, indicator: ProgressIndicator) {
        val files = filesToCopy(configuration, test)
        if (files.isEmpty()) return
        val fingerprint = GoSsh.fingerprint(files.map { GoSsh.Copied(it.remote, it.file.length(), it.file.lastModified()) })
        if (run(GoSsh.commandLine(ssh, host, GoSsh.markerScript(runDirectory)), null, 30_000).output.trim().endsWith(fingerprint)) {
            GoPluginLog.info(GoDebuggerLogs.CATEGORY, "Files for ${configuration.name} on $host are up to date (${files.size})")
            return
        }
        indicator.text = "Copying ${files.size} files to $host"
        val result = send(GoSsh.commandLine(ssh, host, GoSsh.unpackScript(runDirectory, fingerprint)), { out ->
            val tar = GoTarWriter(out)
            for (file in files) {
                indicator.checkCanceled()
                file.file.inputStream().use { tar.file(file.remote, file.file.length(), file.file.lastModified(), it) }
            }
            tar.finish()
        }, 600_000)
        if (result.exit != 0) throw ExecutionException("Cannot copy the files of ${configuration.name} to $host: ${GoSsh.failure(result.output)}")
        // names only: the files may be keys and configs
        GoPluginLog.info(GoDebuggerLogs.CATEGORY, "Copied ${files.size} files to $host: " + files.take(10).joinToString(", ") { it.remote } + if (files.size > 10) ", ..." else "")
    }

    private fun upload(ssh: String, host: String, file: File, path: String) {
        val result = run(GoSsh.commandLine(ssh, host, GoSsh.uploadScript(path)), file, 600_000)
        if (result.exit != 0) throw ExecutionException("Cannot copy ${file.name} to $host:$path: ${GoSsh.failure(result.output)}")
    }

    class Result(val exit: Int, val output: String)

    fun run(commandLine: GeneralCommandLine, input: File?, timeoutMs: Long): Result =
        send(commandLine, input?.let { file -> { out: OutputStream -> file.inputStream().use { it.copyTo(out) } } }, timeoutMs)

    /** A short ssh command with what [write] puts into its stdin; the output and the errors together. */
    fun send(commandLine: GeneralCommandLine, write: ((OutputStream) -> Unit)?, timeoutMs: Long): Result {
        val process = commandLine.withRedirectErrorStream(true).createProcess()
        val output = CompletableFuture.supplyAsync({ process.inputStream.readAllBytes().toString(Charsets.UTF_8) }, { ApplicationManager.getApplication().executeOnPooledThread(it) })
        try {
            process.outputStream.buffered().use { stream -> write?.invoke(stream) }
        } catch (e: Exception) {
            process.destroyForcibly()
            throw e
        }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw ExecutionException("ssh has not finished in ${timeoutMs / 1000} s: ${GoCli.displayString(commandLine)}")
        }
        return Result(process.exitValue(), runCatching { output.get(5, TimeUnit.SECONDS) }.getOrDefault(""))
    }
}

/**
 * `dlv dap` on an SSH host and the connection to it: one ssh runs delve there (its output is the socket line and the log of delve), another
 * (`ssh -W <socket>`) is the connection. Stopping closes both; the first one's end makes the script there kill delve ([GoSsh.serverScript]).
 */
class SshDelve(ssh: String, private val host: String, delve: String, directory: String) : DelveAdapter {
    private val log = GoDebuggerLogs.newAdapterLog()
    private val server: Process = GoSsh.commandLine(ssh, host, GoSsh.serverScript(delve, directory, GoSettings.getInstance().debugAnyGoVersion, log != null))
        .withRedirectErrorStream(true).createProcess()
    private val tunnel: Process
    private val tunnelErrors = StringBuffer()

    override val description: String = "dlv dap on $host (through ssh)"
    override val isLocal: Boolean get() = false
    override val input: InputStream get() = tunnel.inputStream
    override val output: OutputStream get() = tunnel.outputStream

    init {
        val listening = CompletableFuture<String>()
        val startup = StringBuffer()
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching {
                log?.bufferedWriter().use { writer ->
                    server.inputStream.bufferedReader().forEachLine { line ->
                        if (!listening.isDone) {
                            startup.append(line).append('\n')
                            GoSsh.socketPath(line)?.let(listening::complete)
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
        val socket = try {
            listening.get(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            stopServer(0)
            throw (e.cause as? ExecutionException) ?: ExecutionException("dlv on $host has not started: ${e.message}\n$startup", e)
        }
        GoPluginLog.info(GoDebuggerLogs.CATEGORY, "dlv dap on $host listens on $socket")
        tunnel = GoSsh.tunnelCommandLine(ssh, host, socket).createProcess()
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { tunnel.errorStream.bufferedReader().forEachLine { tunnelErrors.append(it).append('\n'); LOG.info("ssh -W $host: $it") } }
        }
        // a refused forward ends ssh at once; a live one stays: give it the time of one round trip
        if (tunnel.waitFor(TUNNEL_CHECK_MS, TimeUnit.MILLISECONDS)) {
            stopServer(0)
            throw ExecutionException("ssh cannot connect to dlv on $host ($socket): ${GoSsh.failure(tunnelErrors.toString())}")
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
