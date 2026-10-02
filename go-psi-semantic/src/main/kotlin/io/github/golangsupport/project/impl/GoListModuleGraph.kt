package io.github.golangsupport.project.impl

import com.intellij.openapi.diagnostic.logger
import io.github.golangsupport.project.api.GoModule
import io.github.golangsupport.project.api.GoModuleGraph
import io.github.golangsupport.project.api.GoModuleVersion
import org.jetbrains.annotations.ApiStatus
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The authoritative fallback: `go list -m -json -e all` run in the module directory. Used only
 * when a toolchain exists and the pure resolution could not read some go.mod files from the
 * module cache. Never call on the EDT (it starts a process and waits for it).
 *
 * The command runs with `GOFLAGS=-mod=readonly` so the user's go.mod is never rewritten; it may
 * download missing go.mod files into the module cache using the user's `GOPROXY`.
 */
@ApiStatus.Internal
object GoListModuleGraph {
    private val LOG = logger<GoListModuleGraph>()
    private const val TIMEOUT_SECONDS = 120L

    /** Output of a finished process. */
    data class ProcessOutput(val exitCode: Int, val stdout: String, val stderr: String)

    /** Runs [goBinary] with [args] in [dir]; null on timeout or start failure. */
    fun runGo(goBinary: Path, dir: Path, args: List<String>, extraEnv: Map<String, String> = emptyMap()): ProcessOutput? {
        val pb = ProcessBuilder(listOf(goBinary.toString()) + args).directory(dir.toFile())
        pb.environment().putAll(extraEnv)
        return try {
            val process = pb.start()
            process.outputStream.close()
            val out = StringBuilder()
            val err = StringBuilder()
            val outReader = Thread { out.append(process.inputStream.bufferedReader().readText()) }.apply { isDaemon = true; start() }
            val errReader = Thread { err.append(process.errorStream.bufferedReader().readText()) }.apply { isDaemon = true; start() }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                LOG.warn("go-psi: '${args.joinToString(" ")}' timed out in $dir")
                return null
            }
            outReader.join(5000)
            errReader.join(5000)
            ProcessOutput(process.exitValue(), out.toString(), err.toString())
        } catch (e: Exception) {
            LOG.info("go-psi: cannot run $goBinary: ${e.message}")
            null
        }
    }

    /** Runs `go list -m -json -e all` in [moduleDir] and converts the result; null on failure. */
    fun load(goBinary: Path, moduleDir: Path): GoModuleGraph? {
        val out = runGo(goBinary, moduleDir, listOf("list", "-m", "-json", "-e", "all"), mapOf("GOFLAGS" to "-mod=readonly")) ?: return null
        if (out.exitCode != 0 && out.stdout.isBlank()) {
            LOG.info("go-psi: go list -m failed in $moduleDir: ${out.stderr.take(500)}")
            return null
        }
        return parse(out.stdout)
    }

    /** Converts `go list -m -json` output into a graph. Main-module directives are read from their go.mod. */
    fun parse(json: String): GoModuleGraph {
        val modules = mutableListOf<GoModule>()
        val missing = mutableListOf<GoModuleVersion>()
        val objects = MiniJson.parseStream(json).filterIsInstance<Map<*, *>>()
        val mainCount = objects.count { it["Main"] == true }
        for (o in objects) {
            val path = o["Path"] as? String ?: continue
            val version = o["Version"] as? String
            val replace = o["Replace"] as? Map<*, *>
            val dirText = (replace?.get("Dir") as? String) ?: (o["Dir"] as? String)
            val goModText = (replace?.get("GoMod") as? String) ?: (o["GoMod"] as? String)
            val goVersion = (replace?.get("GoVersion") as? String) ?: (o["GoVersion"] as? String)
            val isMain = o["Main"] == true
            if (o["Error"] != null && version != null) missing += GoModuleVersion(path, version)
            val goModFile = goModText?.let { Path.of(it) }
            val mod = goModFile?.takeIf { Files.isRegularFile(it) }?.let { GoModFileParser.parseGoMod(Files.readString(it)) }
            modules += GoModule(
                path = path,
                version = if (isMain) null else version,
                dir = dirText?.let { Path.of(it) },
                goModFile = goModFile,
                goVersion = goVersion,
                isMain = isMain,
                isWorkspaceMember = isMain && mainCount > 1,
                isIndirect = o["Indirect"] == true,
                replacement = replace?.let { GoModuleVersion(it["Path"] as? String ?: path, it["Version"] as? String) },
                requires = mod?.requires.orEmpty(),
                replaces = mod?.replaces.orEmpty(),
                excludes = mod?.excludes.orEmpty(),
                retracts = mod?.retracts.orEmpty(),
                tools = mod?.tools.orEmpty(),
                deprecated = (o["Deprecated"] as? String) ?: mod?.deprecated,
            )
        }
        val mains = modules.filter { it.isMain }
        val others = modules.filterNot { it.isMain }.sortedBy { it.path }
        return GoModuleGraph(mains, mains + others, null, false, null, missing, GoModuleGraph.Source.GO_LIST)
    }

    /** `go env -json` as a map, null on failure. */
    fun goEnv(goBinary: Path, dir: Path): Map<String, String>? {
        val out = runGo(goBinary, dir, listOf("env", "-json")) ?: return null
        if (out.exitCode != 0) return null
        val parsed = try {
            MiniJson.parse(out.stdout) as? Map<*, *>
        } catch (e: MiniJson.JsonException) {
            LOG.info("go-psi: cannot parse go env -json: ${e.message}")
            null
        } ?: return null
        return parsed.entries.mapNotNull { (k, v) -> (k as? String)?.let { key -> (v as? String)?.let { key to it } } }.toMap()
    }
}
