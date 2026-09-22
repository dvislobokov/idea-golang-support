package io.github.golangsupport.monitor

import com.intellij.execution.process.OSProcessUtil
import com.intellij.execution.process.ProcessInfo
import io.github.golangsupport.cli.GoCli
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * What a Go executable says about itself (`go version -m app.exe`): the build info the linker puts into every binary. That is how a Go
 * program among the processes of the machine is told from the others, without anything in its code.
 *
 * ```
 * app.exe: go1.24.7
 *         path    example.com/playground/cmd/shop
 *         mod     example.com/playground  (devel)
 *         dep     github.com/google/uuid  v1.6.0  h1:...
 *         build   -race=true
 *         build   vcs.revision=3f2a...
 * ```
 */
class GoBuildInfo(val goVersion: String, val path: String?, val module: String?, val moduleVersion: String?, val settings: Map<String, String>) {
    val isRace: Boolean get() = settings["-race"] == "true"
    val revision: String? get() = settings["vcs.revision"]?.take(8)

    /** `example.com/playground/cmd/shop (go1.24.7, 3f2a1b4c, race)`. */
    fun describe(): String {
        val notes = listOfNotNull(goVersion, revision, "race".takeIf { isRace })
        return (path ?: module ?: "Go program") + " (" + notes.joinToString(", ") + ")"
    }

    companion object {
        /** Null when the output is not the one of a Go binary (`not a Go executable`, `not an executable file`). */
        fun parse(output: String): GoBuildInfo? {
            val lines = output.lines().map { it.trimEnd('\r') }.filter { it.isNotBlank() }
            val first = lines.firstOrNull() ?: return null
            if (!first.contains(": go") && !first.contains(": devel")) return null
            val version = first.substringAfterLast(": ").trim()
            var path: String? = null
            var module: String? = null
            var moduleVersion: String? = null
            val settings = LinkedHashMap<String, String>()
            for (line in lines.drop(1)) {
                val parts = line.trim().split(Regex("\\s+"))
                when (parts.firstOrNull()) {
                    "path" -> path = parts.getOrNull(1)
                    "mod" -> { module = parts.getOrNull(1); moduleVersion = parts.getOrNull(2) }
                    "build" -> parts.getOrNull(1)?.let { setting -> settings[setting.substringBefore('=')] = setting.substringAfter('=', "") }
                }
            }
            return GoBuildInfo(version, path, module, moduleVersion, settings)
        }
    }
}

/** A running Go program of the machine. */
class GoProcess(val pid: Int, val executable: String, val commandLine: String, val info: GoBuildInfo) {
    val name: String get() = info.path?.substringAfterLast('/') ?: File(executable).nameWithoutExtension
    override fun toString(): String = "$name ($pid) — ${info.describe()}"
}

/**
 * The Go programs among the processes of the machine. `go version -m` is asked once per executable (path and size): the list of
 * processes is asked for again and again, the binaries do not change.
 */
object GoProcesses {
    /** Executable -> its build info, or [NOT_GO]: a concurrent map keeps no nulls. */
    private val cache = ConcurrentHashMap<String, Any>()
    private val NOT_GO = Any()

    /** Blocks: a `go version -m` per executable not seen before. Not for EDT. */
    fun list(goExecutable: String): List<GoProcess> = OSProcessUtil.getProcessList().mapNotNull { process -> of(goExecutable, process) }

    private fun of(go: String, process: ProcessInfo): GoProcess? {
        if (process.pid.toLong() == ProcessHandle.current().pid()) return null
        val executable = executableOf(process) ?: return null
        val file = File(executable)
        if (!file.isFile) return null
        val key = "$executable:${file.length()}:${file.lastModified()}"
        val cached = cache[key] ?: buildInfo(go, executable)?.also { cache[key] = it } ?: return null
        val info = cached as? GoBuildInfo ?: return null
        return GoProcess(process.pid, executable, process.commandLine, info)
    }

    /** The path of the executable: the platform has none on Windows (seen live), the JVM or the command line has. */
    fun executableOf(process: ProcessInfo): String? =
        process.executableCannonicalPath.orElse(null)?.takeIf { it.isNotBlank() }
            ?: ProcessHandle.of(process.pid.toLong()).flatMap { it.info().command() }.orElse(null)?.takeIf { it.isNotBlank() }
            ?: executableOf(process.commandLine)

    /** `"C:\Program Files\x\y.exe" -flag` or `C:\p\y.exe serve`: the first word, the quotes or the `.exe` telling where it ends. */
    fun executableOf(commandLine: String): String? {
        val line = commandLine.trim()
        if (line.isEmpty()) return null
        if (line.startsWith('"')) return line.substring(1).substringBefore('"').takeIf { it.isNotEmpty() }
        val exe = Regex("""^(.+?\.exe)(\s|$)""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)
        return exe ?: line.substringBefore(' ')
    }

    /** The build info, [NOT_GO] for a binary go has looked at and rejected, null when go itself could not be run (nothing to remember). */
    private fun buildInfo(go: String, executable: String): Any? {
        val output = runCatching { GoCli.execute(GoCli.toolCommandLine(go, null, "version", "-m", executable), 10_000) }.getOrNull() ?: return null
        // `could not read Go build info from x: not a Go executable` is an answer; anything else (go missing, a timeout) is not
        val answered = output.exitCode == 0 || (output.stderr + output.stdout).contains("could not read Go build info")
        if (output.isTimeout || !answered) return null
        return GoBuildInfo.parse(output.stdout) ?: NOT_GO
    }
}
