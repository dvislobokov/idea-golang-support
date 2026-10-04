package io.github.golangsupport.debugger

import io.github.golangsupport.lang.GoProjectPresence
import com.intellij.execution.ExecutionException
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.cli.GoExecutionProbe
import io.github.golangsupport.cli.GoPluginData
import io.github.golangsupport.cli.GoPluginLog
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The delve that ships with the plugin as sources (`delve/` of the plugin: third_party/delve at a release tag with its vendor/, see
 * build.gradle.kts), built with the user's go into the system directory of the IDE, one directory per SOURCE-HASH. Nothing is downloaded:
 * `-mod=vendor` and `GOTOOLCHAIN=local`. Changed sources (a plugin update) give a new hash and so a new build; older builds are removed.
 */
object GoBundledDelve {
    private const val PLUGIN_ID = "io.github.golangsupport"
    private val building = AtomicBoolean()

    /** A hash whose build failed in this session: not retried on every project open. */
    @Volatile private var failedHash: String? = null

    val isBuilding: Boolean get() = building.get()

    /** After the plugin data moved: a build refused for the old directory may succeed in the new one. */
    fun forgetFailure() {
        failedHash = null
    }

    /** Whether the build of the shipped sources failed in this session (no go, an old toolchain): the caller offers `go install` instead. */
    fun failed(): Boolean = sources()?.let { hash(it) }?.let { it == failedHash } ?: true

    fun sources(): Path? = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.pluginPath?.resolve("delve")?.takeIf { Files.isRegularFile(it.resolve("go.mod")) }

    fun hash(sources: Path): String? = runCatching { Files.readString(sources.resolve("SOURCE-HASH")).trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** [directory]: the delve part of the plugin data ([GoPluginData.delve]), one subdirectory per hash of the sources. */
    fun binaryPath(directory: Path, hash: String): Path = directory.resolve(hash).resolve(GoCli.executableName("dlv"))

    /** [quiet]: the build at project open, two packages at a time ([QUIET_PARALLELISM]) so that indexing and highlighting keep most of the cores. */
    fun buildArguments(output: String, quiet: Boolean = false): List<String> =
        listOf("build") + (if (quiet) listOf("-p", QUIET_PARALLELISM.toString()) else emptyList()) + listOf("-mod=vendor", "-trimpath", "-o", output, "./cmd/dlv")

    /** `-p` of the quiet build; also its GOMAXPROCS, which the compiler processes inherit (their own backend threads). */
    const val QUIET_PARALLELISM = 2

    /** Offline and independent of the user's workspace: the vendored modules, the installed toolchain (no auto-download of the one go.mod names). */
    val BUILD_ENVIRONMENT: Map<String, String> = mapOf("GOFLAGS" to "-mod=vendor", "GOWORK" to "off", "GOTOOLCHAIN" to "local")

    /** The delve of the shipped sources for another machine ([goos]/[goarch]), next to the one of this machine: same hash, same lifetime. */
    fun crossBinaryPath(directory: Path, hash: String, goos: String, goarch: String): Path = directory.resolve(hash).resolve("dlv-$goos-$goarch")

    /**
     * The shipped delve built for [goos]/[goarch] (an SSH host), building it now when missing: `CGO_ENABLED=0`, which delve on linux
     * needs no cgo for (checked on 1.27.2, linux/amd64 and arm64, ~15 s). Blocking: the caller is a background task with [indicator].
     */
    @Throws(ExecutionException::class)
    fun crossBuilt(goos: String, goarch: String, indicator: ProgressIndicator): File {
        val sources = sources() ?: throw ExecutionException("The plugin has no delve sources to build a debugger for $goos/$goarch")
        val hash = hash(sources) ?: throw ExecutionException("The delve sources of the plugin have no SOURCE-HASH")
        val target = crossBinaryPath(GoPluginData.delve(), hash, goos, goarch)
        if (Files.isRegularFile(target)) return target.toFile()
        val go = GoCli.findExecutable() ?: throw ExecutionException("The 'go' executable is not found: it builds delve for $goos/$goarch")
        indicator.text = "Building delve for $goos/$goarch"
        Files.createDirectories(target.parent)
        val temporary = target.resolveSibling(target.fileName.toString() + ".tmp")
        val environment = BUILD_ENVIRONMENT + mapOf("GOOS" to goos, "GOARCH" to goarch, "CGO_ENABLED" to "0")
        val command = GoCli.toolCommandLine(go, sources.toString(), *buildArguments(temporary.toString()).toTypedArray()).withEnvironment(environment)
        val output = CapturingProcessHandler(command).runProcessWithProgressIndicator(indicator, 600_000)
        indicator.checkCanceled()
        if (output.exitCode != 0 || !Files.isRegularFile(temporary)) {
            throw ExecutionException("delve is not built for $goos/$goarch (exit ${output.exitCode}): " + (output.stderr + output.stdout).trim().lines().take(5).joinToString(" / "))
        }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        GoPluginLog.info(GoDebuggerLogs.CATEGORY, "The bundled delve is built for $goos/$goarch: $target")
        return target.toFile()
    }

    /** The built binary of the shipped sources, when it is there. */
    fun binary(): File? {
        val sources = sources() ?: return null
        val hash = hash(sources) ?: return null
        return binaryPath(GoPluginData.delve(), hash).toFile().takeIf { it.isFile }
    }

    /** Whether the binary is still to be built: not there, not being built, not failed this session. */
    private fun needed(): Boolean {
        val hash = sources()?.let(::hash) ?: return false
        return !building.get() && failedHash != hash && !Files.isRegularFile(binaryPath(GoPluginData.delve(), hash))
    }

    /**
     * The build at project open: after indexing (`go build` took the cores the indexer needed, seen in the sandbox logs) and with limited
     * parallelism. Debug does not wait for this: it calls [ensureBuilt] itself when the binary is missing.
     */
    fun ensureBuiltWhenSmart(project: Project) {
        if (!needed()) return
        val dumb = DumbService.getInstance(project)
        if (dumb.isDumb) GoPluginLog.info(GoDebuggerLogs.CATEGORY, "The bundled delve build is deferred until smart mode")
        val asked = System.currentTimeMillis()
        dumb.runWhenSmart {
            if (project.isDisposed) return@runWhenSmart
            val waited = System.currentTimeMillis() - asked
            if (waited > 100) GoPluginLog.info(GoDebuggerLogs.CATEGORY, "The bundled delve build starts after $waited ms of indexing")
            ensureBuilt(project, quiet = true)
        }
    }

    /**
     * Builds the binary in a background task (its progress is in the status bar) unless it is there, being built, or failed this session.
     * [quiet]: the build nobody waits for, with limited parallelism; Debug builds at full speed.
     */
    fun ensureBuilt(project: Project, quiet: Boolean = false) {
        val sources = sources() ?: return
        val hash = hash(sources) ?: return
        val target = binaryPath(GoPluginData.delve(), hash)
        if (Files.isRegularFile(target) || failedHash == hash || !building.compareAndSet(false, true)) return
        object : Task.Backgroundable(project, "Building delve", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    build(sources, hash, target, indicator, quiet)
                } finally {
                    building.set(false)
                }
            }
        }.queue()
    }

    private fun build(sources: Path, hash: String, target: Path, indicator: ProgressIndicator, quiet: Boolean) {
        val go = GoCli.findExecutable() ?: run {
            failedHash = hash
            GoPluginLog.warn(GoDebuggerLogs.CATEGORY, "The bundled delve is not built: go is not found")
            return
        }
        // a binary built where nothing may run is no debugger: GoPluginDataCheck tells the user where to move the plugin data
        if (GoExecutionProbe.check(target.parent.parent) == GoExecutionProbe.Result.NOT_EXECUTABLE) {
            failedHash = hash
            GoPluginLog.warn(GoDebuggerLogs.CATEGORY, "The bundled delve is not built: programs cannot run in ${target.parent.parent} (${GoExecutionProbe.reason(target.parent.parent).orEmpty()})")
            return
        }
        indicator.text = "Building delve from the plugin's sources"
        Files.createDirectories(target.parent)
        val temporary = target.resolveSibling(target.fileName.toString() + ".tmp")
        val environment = if (quiet && System.getenv("GOMAXPROCS").isNullOrEmpty()) BUILD_ENVIRONMENT + ("GOMAXPROCS" to QUIET_PARALLELISM.toString()) else BUILD_ENVIRONMENT
        val command = GoCli.toolCommandLine(go, sources.toString(), *buildArguments(temporary.toString(), quiet).toTypedArray()).withEnvironment(environment)
        val started = System.currentTimeMillis()
        val output = CapturingProcessHandler(command).runProcessWithProgressIndicator(indicator, 600_000)
        if (output.isCancelled) return
        if (output.exitCode != 0 || !Files.isRegularFile(temporary)) {
            failedHash = hash
            val reason = (output.stderr + output.stdout).trim().lines().take(5).joinToString(" / ")
            GoPluginLog.warn(GoDebuggerLogs.CATEGORY, "The bundled delve is not built (exit ${output.exitCode}): $reason")
            return
        }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        target.parent.parent.toFile().listFiles()?.filter { it.isDirectory && it.name != hash }?.forEach { it.deleteRecursively() }
        GoPluginLog.info(GoDebuggerLogs.CATEGORY, "The bundled delve is built in ${System.currentTimeMillis() - started} ms (${if (quiet) "-p $QUIET_PARALLELISM" else "full parallelism"}): $target")
    }
}

/**
 * On project open: the bundled delve gets built once per machine and per version of its sources, before the first Debug needs it — and again
 * when the binary was lost (the system directory cleaned, an antivirus took it), since [GoBundledDelve.ensureBuilt] checks the file itself.
 */
class GoBundledDelveActivity : ProjectActivity {
    // a project without Go files builds nothing; the build starts when Go files appear (GoProjectPresence.resume)
    override suspend fun execute(project: Project) {
        if (GoProjectPresence.hasGoFiles(project)) GoBundledDelve.ensureBuiltWhenSmart(project)
    }
}
