package io.github.golangsupport.project.impl

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import io.github.golangsupport.project.api.GoToolchainInfo
import io.github.golangsupport.project.api.GoToolchainProvider
import io.github.golangsupport.project.api.GoVersion
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.TestOnly
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Detects the Go toolchain without user configuration:
 *
 * 1. `GOROOT` from the environment, when it points to a Go installation;
 * 2. `go` on `PATH` (its GOROOT is the parent of `bin`), refined by `go env -json` which runs once on
 *    a pooled thread (never on the EDT) and is cached for the application lifetime;
 * 3. the default install locations (`C:\Program Files\Go`, `/usr/local/go`, `/usr/lib/go`).
 *
 * The version comes from `$GOROOT/VERSION` (or `GOVERSION` once `go env` answered). Without the
 * `go env` answer the remaining values come from the process environment, the `go env -w` file
 * (`GOENV`) and host defaults. [modificationTracker] changes when the refined values arrive.
 */
@ApiStatus.Internal
class DefaultGoToolchainProvider : GoToolchainProvider {
    private val pure = AtomicReference<GoToolchainInfo?>()
    private val pureComputed = AtomicBoolean(false)
    private val refined = AtomicReference<GoToolchainInfo?>()
    private val refining = AtomicBoolean(false)

    /** Changes when a background `go env` refinement replaced the pure answer. */
    override val modificationTracker: ModificationTracker get() = tracker
    private val tracker = SimpleModificationTracker()

    /** Marks toolchain-derived caches stale (tests). */
    internal fun invalidate() = tracker.incModificationCount()

    override fun toolchainFor(project: Project?): GoToolchainInfo? {
        refined.get()?.let { return it }
        val info = pureInfo()
        val binary = info?.goBinary
        if (binary != null && refining.compareAndSet(false, true)) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val env = GoListModuleGraph.goEnv(binary, binary.parent)
                if (env != null) {
                    refined.set(fromGoEnv(env, binary, info))
                    LOG.info("go-psi: toolchain refined by 'go env -json': ${refined.get()?.goroot} ${refined.get()?.version}")
                    tracker.incModificationCount()
                    for (p in ProjectManager.getInstance().openProjects) {
                        if (!p.isDisposed) GoProjectModelTracker.getInstance(p).bump("toolchain refined by go env")
                    }
                } else {
                    LOG.info("go-psi: 'go env -json' failed; keeping the pure toolchain detection")
                }
            }
        }
        return info
    }

    private fun pureInfo(): GoToolchainInfo? {
        if (!pureComputed.get()) {
            pure.set(detectPure(System.getenv()))
            pureComputed.set(true)
            LOG.info("go-psi: toolchain detected without running go: ${pure.get()?.goroot} ${pure.get()?.version}")
        }
        return pure.get()
    }

    /** Pure detection followed by a synchronous `go env -json`; for tests and background callers only. */
    @TestOnly
    fun detectBlocking(): GoToolchainInfo? {
        val app: Application? = ApplicationManager.getApplication() // null in plain unit tests
        check(app?.isDispatchThread != true) { "go env must not run on the EDT" }
        val info = detectPure(System.getenv()) ?: return null
        val binary = info.goBinary ?: return info
        val env = GoListModuleGraph.goEnv(binary, binary.parent) ?: return info
        return fromGoEnv(env, binary, info)
    }

    companion object {
        private val LOG = logger<DefaultGoToolchainProvider>()
        private val IS_WINDOWS = System.getProperty("os.name").lowercase().startsWith("windows")
        private val GO_EXE = if (IS_WINDOWS) "go.exe" else "go"

        private val DEFAULT_ROOTS = listOf("C:\\Program Files\\Go", "/usr/local/go", "/usr/lib/go", "/opt/homebrew/opt/go/libexec")

        /** Pure detection from [processEnv] and the file system (no process is started). */
        @JvmStatic
        fun detectPure(processEnv: Map<String, String>): GoToolchainInfo? {
            val goEnvFile = goEnvFile(processEnv)?.let(::readGoEnvFile).orEmpty()
            val env = goEnvFile + processEnv.filterKeys { it.startsWith("GO") || it.startsWith("CGO") }
            val pathBinary = findOnPath(processEnv)
            val goroot = env["GOROOT"]?.let { Path.of(it) }?.takeIf(::isGoroot)
                ?: pathBinary?.let { realParent(it)?.parent }?.takeIf(::isGoroot)
                ?: DEFAULT_ROOTS.map { Path.of(it) }.firstOrNull { runCatching { isGoroot(it) }.getOrDefault(false) }
                ?: return null
            val binary = goroot.resolve("bin").resolve(GO_EXE).takeIf { Files.isRegularFile(it) } ?: pathBinary
            val version = readVersion(goroot)
            val home = System.getProperty("user.home")
            val gopath = (env["GOPATH"]?.split(File.pathSeparatorChar)?.filter { it.isNotBlank() }?.map { Path.of(it) })
                ?.takeIf { it.isNotEmpty() } ?: listOf(Path.of(home, "go"))
            val gomodcache = env["GOMODCACHE"]?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: gopath.first().resolve("pkg").resolve("mod")
            val goos = env["GOOS"]?.takeIf { it.isNotBlank() } ?: hostOs()
            val goarch = env["GOARCH"]?.takeIf { it.isNotBlank() } ?: hostArch()
            val cgo = when (env["CGO_ENABLED"]) {
                "1" -> true
                "0" -> false
                // Go enables cgo by default only for native builds with a C compiler on PATH.
                else -> goos == hostOs() && goarch == hostArch() && (findOnPath(processEnv, "gcc") != null || findOnPath(processEnv, "clang") != null)
            }
            return GoToolchainInfo(
                goroot = goroot, version = version, gopath = gopath, gomodcache = gomodcache, goos = goos, goarch = goarch,
                cgoEnabled = cgo, buildTags = tagsFromGoFlags(env["GOFLAGS"]), env = env, goBinary = binary,
            )
        }

        /** Builds the info from `go env -json` output. */
        @JvmStatic
        fun fromGoEnv(env: Map<String, String>, binary: Path, fallback: GoToolchainInfo?): GoToolchainInfo {
            val goroot = env["GOROOT"]?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: fallback?.goroot
            val gopath = env["GOPATH"]?.split(File.pathSeparatorChar)?.filter { it.isNotBlank() }?.map { Path.of(it) }
                ?.takeIf { it.isNotEmpty() } ?: fallback?.gopath.orEmpty()
            return GoToolchainInfo(
                goroot = goroot,
                version = env["GOVERSION"]?.let { GoVersion.parse(it) } ?: goroot?.let(::readVersion) ?: fallback?.version,
                gopath = gopath,
                gomodcache = env["GOMODCACHE"]?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: fallback?.gomodcache,
                goos = env["GOOS"] ?: fallback?.goos ?: hostOs(),
                goarch = env["GOARCH"] ?: fallback?.goarch ?: hostArch(),
                cgoEnabled = env["CGO_ENABLED"]?.let { it == "1" } ?: fallback?.cgoEnabled ?: false,
                buildTags = tagsFromGoFlags(env["GOFLAGS"]),
                env = env,
                goBinary = binary,
            )
        }

        /** `$GOROOT/VERSION` first line (`go1.27.1`). */
        @JvmStatic
        fun readVersion(goroot: Path): GoVersion? {
            val file = goroot.resolve("VERSION")
            if (!Files.isRegularFile(file)) return null
            return runCatching { GoVersion.parse(Files.readString(file)) }.getOrNull()
        }

        private fun isGoroot(p: Path): Boolean = Files.isDirectory(p.resolve("src")) && (Files.isRegularFile(p.resolve("VERSION")) || Files.isDirectory(p.resolve("bin")))

        private fun realParent(binary: Path): Path? = runCatching { binary.toRealPath().parent }.getOrNull()

        private fun findOnPath(env: Map<String, String>, name: String = "go"): Path? {
            val path = env["PATH"] ?: env["Path"] ?: return null
            val exe = if (IS_WINDOWS) "$name.exe" else name
            return path.split(File.pathSeparatorChar).filter { it.isNotBlank() }.map { Path.of(it.trim('"'), exe) }
                .firstOrNull { runCatching { Files.isRegularFile(it) }.getOrDefault(false) }
        }

        /** The `go env -w` file: `GOENV`, else `<os config dir>/go/env`. */
        private fun goEnvFile(env: Map<String, String>): Path? {
            env["GOENV"]?.let { return if (it == "off") null else Path.of(it) }
            val configDir = when {
                IS_WINDOWS -> env["APPDATA"]?.let { Path.of(it) }
                System.getProperty("os.name").lowercase().contains("mac") -> Path.of(System.getProperty("user.home"), "Library", "Application Support")
                else -> env["XDG_CONFIG_HOME"]?.let { Path.of(it) } ?: Path.of(System.getProperty("user.home"), ".config")
            } ?: return null
            return configDir.resolve("go").resolve("env")
        }

        private fun readGoEnvFile(file: Path): Map<String, String> {
            if (!runCatching { Files.isRegularFile(file) }.getOrDefault(false)) return emptyMap()
            return Files.readAllLines(file).mapNotNull { line ->
                val eq = line.indexOf('=')
                if (eq <= 0 || line.trimStart().startsWith("#")) null else line.substring(0, eq).trim() to line.substring(eq + 1).trim()
            }.toMap()
        }

        /** `-tags=a,b` (or `-tags=a b` quoted) from GOFLAGS. */
        @JvmStatic
        fun tagsFromGoFlags(goflags: String?): Set<String> {
            if (goflags.isNullOrBlank()) return emptySet()
            val flag = goflags.split(' ').firstOrNull { it.startsWith("-tags=") || it.startsWith("--tags=") } ?: return emptySet()
            return flag.substringAfter('=').split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }

        @JvmStatic
        fun hostOs(): String {
            val name = System.getProperty("os.name").lowercase()
            return when {
                name.startsWith("windows") -> "windows"
                name.contains("mac") || name.contains("darwin") -> "darwin"
                name.contains("freebsd") -> "freebsd"
                name.contains("openbsd") -> "openbsd"
                name.contains("netbsd") -> "netbsd"
                name.contains("sunos") || name.contains("solaris") -> "solaris"
                name.contains("aix") -> "aix"
                else -> "linux"
            }
        }

        @JvmStatic
        fun hostArch(): String = when (val arch = System.getProperty("os.arch").lowercase()) {
            "amd64", "x86_64" -> "amd64"
            "aarch64", "arm64" -> "arm64"
            "x86", "i386", "i486", "i586", "i686" -> "386"
            "ppc64le" -> "ppc64le"
            "s390x" -> "s390x"
            "riscv64" -> "riscv64"
            "loongarch64" -> "loong64"
            else -> arch
        }
    }
}
