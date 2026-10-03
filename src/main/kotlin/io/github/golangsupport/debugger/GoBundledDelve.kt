package io.github.golangsupport.debugger

import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.golangsupport.cli.GoCli
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

    /** Whether the build of the shipped sources failed in this session (no go, an old toolchain): the caller offers `go install` instead. */
    fun failed(): Boolean = sources()?.let { hash(it) }?.let { it == failedHash } ?: true

    fun sources(): Path? = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.pluginPath?.resolve("delve")?.takeIf { Files.isRegularFile(it.resolve("go.mod")) }

    fun hash(sources: Path): String? = runCatching { Files.readString(sources.resolve("SOURCE-HASH")).trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    fun binaryPath(systemDirectory: Path, hash: String): Path = systemDirectory.resolve("go-plugin").resolve("delve").resolve(hash).resolve(GoCli.executableName("dlv"))

    fun buildArguments(output: String): List<String> = listOf("build", "-mod=vendor", "-trimpath", "-o", output, "./cmd/dlv")

    /** Offline and independent of the user's workspace: the vendored modules, the installed toolchain (no auto-download of the one go.mod names). */
    val BUILD_ENVIRONMENT: Map<String, String> = mapOf("GOFLAGS" to "-mod=vendor", "GOWORK" to "off", "GOTOOLCHAIN" to "local")

    /** The built binary of the shipped sources, when it is there. */
    fun binary(): File? {
        val sources = sources() ?: return null
        val hash = hash(sources) ?: return null
        return binaryPath(PathManager.getSystemDir(), hash).toFile().takeIf { it.isFile }
    }

    /** Builds the binary in a background task (its progress is in the status bar) unless it is there, being built, or failed this session. */
    fun ensureBuilt(project: Project) {
        val sources = sources() ?: return
        val hash = hash(sources) ?: return
        val target = binaryPath(PathManager.getSystemDir(), hash)
        if (Files.isRegularFile(target) || failedHash == hash || !building.compareAndSet(false, true)) return
        object : Task.Backgroundable(project, "Building delve", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    build(sources, hash, target, indicator)
                } finally {
                    building.set(false)
                }
            }
        }.queue()
    }

    private fun build(sources: Path, hash: String, target: Path, indicator: ProgressIndicator) {
        val go = GoCli.findExecutable() ?: run {
            failedHash = hash
            GoPluginLog.warn(GoDebuggerLogs.CATEGORY, "The bundled delve is not built: go is not found")
            return
        }
        indicator.text = "Building delve from the plugin's sources"
        Files.createDirectories(target.parent)
        val temporary = target.resolveSibling(target.fileName.toString() + ".tmp")
        val command = GoCli.toolCommandLine(go, sources.toString(), *buildArguments(temporary.toString()).toTypedArray()).withEnvironment(BUILD_ENVIRONMENT)
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
        GoPluginLog.info(GoDebuggerLogs.CATEGORY, "The bundled delve is built: $target")
    }
}

/**
 * On project open: the bundled delve gets built once per machine and per version of its sources, before the first Debug needs it — and again
 * when the binary was lost (the system directory cleaned, an antivirus took it), since [GoBundledDelve.ensureBuilt] checks the file itself.
 */
class GoBundledDelveActivity : ProjectActivity {
    override suspend fun execute(project: Project) = GoBundledDelve.ensureBuilt(project)
}
