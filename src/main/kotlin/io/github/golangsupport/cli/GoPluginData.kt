package io.github.golangsupport.cli

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.SystemInfo
import io.github.golangsupport.settings.GoSettings
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Where the plugin keeps its own files: the delve it builds, the tools `go install` puts there, the catalogue of symbols, the temporary
 * directory of `go run` / `go test` builds and, when the directory is chosen by the user, the logs. One place, because on some machines
 * programs may run from one directory only (seen at a user's company: on their Linux only under `/home/work/<user>`); the default is
 * the system directory of the IDE. Settings | Go | Tools → Plugin data directory.
 */
object GoPluginData {
    fun defaultRoot(): Path = Path.of(PathManager.getSystemPath(), "go-support")

    /** The directory chosen in the settings, or [defaultRoot]. */
    fun root(): Path = GoSettings.getInstance().pluginDataDirectory.trim().takeIf { it.isNotEmpty() }?.let { Path.of(it) } ?: defaultRoot()

    val isCustom: Boolean get() = GoSettings.getInstance().pluginDataDirectory.isNotBlank()

    /** The parts of [root]: what moves with it. */
    val PARTS = listOf("delve", "bin", "catalogue", "tmp", "logs")

    fun delve(): Path = root().resolve("delve")
    fun bin(): Path = root().resolve("bin")
    fun catalogue(): Path = root().resolve("catalogue")
    fun tmp(): Path = root().resolve("tmp")

    /**
     * `GOBIN` for `go install` of the tools when the user chose the directory (the default GOPATH/bin may be where nothing can run), and
     * `GOTMPDIR` when the system temporary directory cannot run programs: `go run` and `go test` execute what they build there.
     * The user's own GOBIN / GOTMPDIR win.
     */
    fun goEnvironment(install: Boolean): Map<String, String> = buildMap {
        if (install && isCustom && System.getenv("GOBIN").isNullOrEmpty()) put("GOBIN", bin().toString())
        if (System.getenv("GOTMPDIR").isNullOrEmpty() && !SystemInfo.isWindows && GoExecutionProbe.cached(systemTemp()) == GoExecutionProbe.Result.NOT_EXECUTABLE &&
            GoExecutionProbe.cached(root()) == GoExecutionProbe.Result.OK
        ) put("GOTMPDIR", tmp().also { runCatching { Files.createDirectories(it) } }.toString())
    }

    fun systemTemp(): Path = Path.of(System.getProperty("java.io.tmpdir"))

    /** Copies the parts of [from] into [to] and removes them from [from]; returns the parts that could not be moved. */
    fun move(from: Path, to: Path): List<String> {
        if (from.normalize() == to.normalize()) return emptyList()
        val failed = ArrayList<String>()
        for (part in PARTS) {
            val source = from.resolve(part).toFile()
            if (!source.exists()) continue
            val target = to.resolve(part).toFile()
            val copied = runCatching { source.copyRecursively(target, overwrite = true) { _, _ -> OnErrorAction.SKIP } }.getOrDefault(false)
            if (!copied) { failed += part; continue }
            // the copy keeps the executable bit only where the file system says so: put it back on the binaries
            target.walkTopDown().filter { it.isFile && (it.parentFile.name == "bin" || it.name == GoCli.executableName("dlv")) }.forEach { it.setExecutable(true) }
            source.deleteRecursively()
        }
        return failed
    }
}

/**
 * Whether programs can be started from a directory: a copy of a small system executable (`true`) is made there, marked executable and
 * run. A `noexec` mount, an execution policy (fapolicyd, SELinux) or a missing permission make it fail. Windows is not checked.
 */
object GoExecutionProbe {
    enum class Result { OK, NOT_EXECUTABLE, UNKNOWN }

    private val results = ConcurrentHashMap<Path, Pair<Result, String>>()

    /** The result of an earlier [check] of [dir] in this session, or null. */
    fun cached(dir: Path): Result? = results[dir.normalize()]?.first

    /** The reason of the last failed [check] of [dir], for the notification. */
    fun reason(dir: Path): String? = results[dir.normalize()]?.second?.takeIf { it.isNotEmpty() }

    /** Runs the probe (blocking, a fraction of a second); not on the EDT. */
    fun check(dir: Path): Result {
        if (SystemInfo.isWindows) return Result.OK.also { results[dir.normalize()] = it to "" }
        val sample = listOf("/bin/true", "/usr/bin/true").map(::File).firstOrNull { it.isFile && it.canExecute() }
        val (result, reason) = if (sample == null) Result.UNKNOWN to "no /bin/true to copy" else probe(dir, sample)
        results[dir.normalize()] = result to reason
        return result
    }

    private fun probe(dir: Path, sample: File): Pair<Result, String> {
        val copy = try {
            Files.createDirectories(dir)
            Files.copy(sample.toPath(), dir.resolve(".exec-probe-${ProcessHandle.current().pid()}"), StandardCopyOption.REPLACE_EXISTING).toFile()
        } catch (e: Exception) {
            return Result.NOT_EXECUTABLE to "cannot write there: ${GoPluginLog.describe(e)}"
        }
        return try {
            if (!copy.setExecutable(true)) return Result.NOT_EXECUTABLE to "cannot mark a file executable there"
            val output = CapturingProcessHandler(GeneralCommandLine(copy.path).withWorkDirectory(dir.toFile())).runProcess(5_000)
            if (output.exitCode == 0 && !output.isTimeout) Result.OK to "" else Result.NOT_EXECUTABLE to "a program there exited with ${output.exitCode} ${output.stderr.trim()}".trim()
        } catch (e: Exception) {
            Result.NOT_EXECUTABLE to GoPluginLog.describe(e)
        } finally {
            copy.delete()
        }
    }
}
