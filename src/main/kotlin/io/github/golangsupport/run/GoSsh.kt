package io.github.golangsupport.run

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.util.SystemInfo
import java.nio.charset.StandardCharsets

/**
 * Debug on an SSH host, the pure part: the commands of the system OpenSSH client (keys, agent and ~/.ssh/config work as in a terminal;
 * `BatchMode` makes a missing key an error instead of a password prompt nobody sees), the scripts run there and what their output means.
 *
 * The plan: `uname` tells the platform; the program and delve are built here for it and copied there through `ssh 'cat > file'` (no scp:
 * one tool, and `ssh://` hosts work the same); `dlv dap` listens on 127.0.0.1 there and is reached with `ssh -W`, whose stdin and stdout
 * are the DAP connection: no port is opened here, none to the network there.
 */
object GoSsh {
    /** Under the home directory there; programs do not run from /tmp on hosts that mount it noexec. */
    const val DEFAULT_DIRECTORY = ".cache/go-project-support"

    /** The printed start of the probe, so that a login banner or a motd before it is skipped. */
    private const val PROBE_MARK = "go-project-support-probe"

    private val OPTIONS = listOf("-o", "BatchMode=yes", "-o", "ConnectTimeout=15", "-o", "ServerAliveInterval=15")

    /** The OpenSSH client: on PATH, or the one Windows ships in System32\OpenSSH. */
    fun executable(): String? = PathEnvironmentVariableUtil.findInPath(if (SystemInfo.isWindows) "ssh.exe" else "ssh")?.path
        ?: System.getenv("SystemRoot")?.let { "$it\\System32\\OpenSSH\\ssh.exe" }?.takeIf { SystemInfo.isWindows && java.io.File(it).isFile }

    /** `ssh host 'command'`; the command is run by `sh` there whatever the login shell is (fish, csh). */
    fun commandLine(ssh: String, host: String, script: String): GeneralCommandLine =
        GeneralCommandLine(listOf(ssh, "-T") + OPTIONS + listOf(host, "sh -c " + quote(script))).withCharset(StandardCharsets.UTF_8)

    /** `ssh -W 127.0.0.1:port host`: the streams of the process are a TCP connection to that port there. */
    fun tunnelCommandLine(ssh: String, host: String, port: Int): GeneralCommandLine =
        GeneralCommandLine(listOf(ssh, "-T") + OPTIONS + listOf("-W", "127.0.0.1:$port", host)).withCharset(StandardCharsets.UTF_8)

    /** POSIX single quotes: nothing inside is expanded. */
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** The first look at the host: its platform and its home directory. */
    val PROBE_SCRIPT = "echo $PROBE_MARK; uname -sm; echo \"\$HOME\""

    class Host(val goos: String, val goarch: String, val home: String)

    /** The answer to [PROBE_SCRIPT]: `Linux x86_64` / `/home/me`; null when it is not one (the reason is the text itself). */
    fun probe(output: String): Host? {
        val lines = output.lines().map(String::trim).dropWhile { it != PROBE_MARK }.drop(1).filter { it.isNotEmpty() }
        if (lines.size < 2) return null
        val (goos, goarch) = platform(lines[0]) ?: return null
        return Host(goos, goarch, lines[1])
    }

    /** `uname -sm` -> GOOS/GOARCH; null for a platform delve does not debug. */
    fun platform(uname: String): Pair<String, String>? {
        val parts = uname.trim().split(Regex("\\s+"))
        if (parts.size < 2) return null
        val goos = when (parts[0].lowercase()) {
            "linux" -> "linux"
            "darwin" -> "darwin"
            "freebsd" -> "freebsd"
            else -> return null
        }
        val goarch = when (parts[1].lowercase()) {
            "x86_64", "amd64" -> "amd64"
            "aarch64", "arm64" -> "arm64"
            "i386", "i686" -> "386"
            "ppc64le" -> "ppc64le"
            "riscv64" -> "riscv64"
            else -> return null
        }
        return goos to goarch
    }

    /** [directory] of the configuration as an absolute path there: relative ones are under [home]. */
    fun directory(directory: String?, home: String): String {
        val dir = directory?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_DIRECTORY
        return when {
            dir.startsWith("/") -> dir.trimEnd('/')
            dir == "~" -> home
            else -> home.trimEnd('/') + "/" + dir.removePrefix("~/").trimEnd('/')
        }
    }

    /** Exit 0 when [path] is there and runnable: a delve of the same sources is not copied again. */
    fun existsScript(path: String): String = "test -x " + quote(path)

    /** Stdin into [path], made runnable; through a temporary name, so a broken copy never looks like a whole one. */
    fun uploadScript(path: String): String {
        val dir = path.substringBeforeLast('/')
        val temporary = "$path.part"
        return "mkdir -p ${quote(dir)} && cat > ${quote(temporary)} && chmod +x ${quote(temporary)} && mv -f ${quote(temporary)} ${quote(path)}"
    }

    /**
     * `dlv dap` there, on 127.0.0.1:[port] (0: a free port, which delve prints). It lives as long as the ssh that started it: delve runs in
     * the background, and a watcher on the stdin of the session kills it when that ends (the IDE closed the session, or the connection
     * broke); without a terminal there is no SIGHUP to do it. The stdin of the watcher is given explicitly: a non-interactive shell gives
     * the background commands /dev/null.
     */
    fun serverScript(delve: String, directory: String, port: Int, anyGoVersion: Boolean, log: Boolean): String {
        val arguments = listOf("dap", "--listen=127.0.0.1:$port") + (if (anyGoVersion) listOf("--check-go-version=false") else emptyList()) +
            (if (log) listOf("--log", "--log-output=dap,debugger") else emptyList())
        return "cd ${quote(directory)} || exit 1; exec 3<&0; " +
            "${quote(delve)} ${arguments.joinToString(" ")} </dev/null 2>&1 & p=\$!; " +
            "(cat <&3 >/dev/null; kill \$p 2>/dev/null) >/dev/null 2>&1 & " +
            "wait \$p"
    }

    /** `bind: address already in use` of delve: the port of the configuration is taken there. */
    fun portTaken(line: String): Boolean = "address already in use" in line

    /** What ssh says when it cannot get in (the last lines of its output), with the way out for the usual refusals of `BatchMode`. */
    fun failure(output: String): String {
        val text = output.lines().map(String::trim).filter { it.isNotEmpty() }.takeLast(3).joinToString(" / ").ifEmpty { "no output" }
        val hint = when {
            "Host key verification failed" in output -> "the host is not in known_hosts yet: connect once from a terminal (ssh host) and accept its key"
            "Permission denied" in output -> "a key is needed, prompts are off: ssh-copy-id, or add the public key to ~/.ssh/authorized_keys there"
            else -> null
        }
        return if (hint == null) text else "$text ($hint)"
    }

    /** A file name there for the program of a configuration: one per package, overwritten by the next debug of it. */
    fun programName(packagePath: String, test: Boolean): String =
        (if (test) "test-" else "run-") + Integer.toHexString(packagePath.replace('\\', '/').lowercase().hashCode()) + if (test) ".test" else ""
}
