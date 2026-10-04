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
 * one tool, and `ssh://` hosts work the same). `dlv dap` listens there on a unix socket in a private temporary directory and is reached
 * with `ssh -W <socket>`, whose stdin and stdout are the DAP connection: delve runs the user's code on request, so no TCP port, which any
 * user of the host could connect to, and none here.
 *
 * Layout there, all of it `0700` and refused when the directory is writable by others (they could swap the delve that runs as the user):
 * `<directory>/bin/dlv-<os>-<arch>-<hash>`, `<directory>/runs/<run|test>-<package hash>/` with the program and the files copied with it.
 */
object GoSsh {
    /** Under the home directory there; programs do not run from /tmp on hosts that mount it noexec. */
    const val DEFAULT_DIRECTORY = ".cache/go-project-support"

    /** The printed start of the probe, so that a login banner or a motd before it is skipped. */
    private const val PROBE_MARK = "go-project-support-probe"

    /** What the setup prints when the directory is writable by others. */
    const val UNSAFE_MARK = "go-project-support-unsafe"

    private val OPTIONS = listOf("-o", "BatchMode=yes", "-o", "ConnectTimeout=15", "-o", "ServerAliveInterval=15")

    /** The OpenSSH client: on PATH, or the one Windows ships in System32\OpenSSH. */
    fun executable(): String? = PathEnvironmentVariableUtil.findInPath(if (SystemInfo.isWindows) "ssh.exe" else "ssh")?.path
        ?: System.getenv("SystemRoot")?.let { "$it\\System32\\OpenSSH\\ssh.exe" }?.takeIf { SystemInfo.isWindows && java.io.File(it).isFile }

    /**
     * Why [host] cannot be given to ssh, or null. A host starting with `-` would be read as an option (`-oProxyCommand=...` runs a command
     * here), and run configurations come with projects (the `.run` directory); `--` before the host is the second guard.
     */
    fun invalidHost(host: String): String? = when {
        host.isBlank() -> "The SSH host is empty"
        host.startsWith("-") -> "The SSH host cannot start with '-': $host"
        host.any { it.isWhitespace() || it.isISOControl() } -> "The SSH host cannot contain spaces or control characters: $host"
        else -> null
    }

    /** `ssh host 'command'`; the command is run by `sh` there whatever the login shell is (fish, csh). */
    fun commandLine(ssh: String, host: String, script: String): GeneralCommandLine =
        GeneralCommandLine(listOf(ssh, "-T") + OPTIONS + listOf("--", checked(host), "sh -c " + quote(script))).withCharset(StandardCharsets.UTF_8)

    /** `ssh -W <socket> host`: the streams of the process are a connection to that unix socket there. */
    fun tunnelCommandLine(ssh: String, host: String, socket: String): GeneralCommandLine =
        GeneralCommandLine(listOf(ssh, "-T") + OPTIONS + listOf("-W", socket, "--", checked(host))).withCharset(StandardCharsets.UTF_8)

    private fun checked(host: String): String = host.also { invalidHost(it)?.let { reason -> throw IllegalArgumentException(reason) } }

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

    /** Where the copies of delve live: one per platform and version of its sources. */
    fun delvePath(directory: String, binaryName: String, hash: String): String = "$directory/bin/$binaryName-${hash.take(12)}"

    /**
     * The directory there of the package of a configuration (`runs/run-1a2b3c`), one per package: the program runs in it, so the files copied
     * with it (`testdata/`, `config.yaml`) are where `os.ReadFile("testdata/x")` looks, and two packages never share a `testdata`.
     */
    fun runDirectory(directory: String, packagePath: String, test: Boolean): String =
        "$directory/runs/" + (if (test) "test-" else "run-") + Integer.toHexString(packagePath.replace('\\', '/').lowercase().hashCode())

    /** The program in its [runDirectory]: named after the package (`shop`, `store.test`), as `ps` there shows it. */
    fun programName(packagePath: String, test: Boolean): String =
        packagePath.replace('\\', '/').trimEnd('/').substringAfterLast('/').ifEmpty { "program" } + if (test) ".test" else ""

    /**
     * The directories of a debug, private to the user: created with `umask 077`, and the base [directory] refused when its mode lets the
     * group or others write (`ls -ld`: the 6th and the 9th character; a sticky /tmp is refused too). [own]: the default directory, which is
     * the plugin's and is made private (`chmod 700`) rather than refused; a directory the user named is not changed behind them.
     */
    fun setupScript(directory: String, runDirectory: String, own: Boolean): String {
        val dir = quote(directory)
        return "umask 077; mkdir -p $dir || exit 1; " + (if (own) "chmod 700 $dir; " else "") +
            "case \"\$(ls -ld $dir)\" in ?????w*|????????w*) echo $UNSAFE_MARK; exit 3;; esac; " +
            "mkdir -p ${quote("$directory/bin")} ${quote("$directory/runs")} ${quote(runDirectory)} && chmod 700 ${quote("$directory/bin")} ${quote("$directory/runs")} ${quote(runDirectory)}"
    }

    /** Exit 0 when [path] is there and runnable: a delve of the same sources is not copied again. */
    fun existsScript(path: String): String = "test -x " + quote(path)

    /** Stdin into [path], made runnable by the user alone; through a temporary name, so a broken copy never looks like a whole one. */
    fun uploadScript(path: String): String {
        val temporary = "$path.part"
        return "umask 077; cat > ${quote(temporary)} && chmod 700 ${quote(temporary)} && mv -f ${quote(temporary)} ${quote(path)}"
    }

    /**
     * `dlv dap` there, on a unix socket in a fresh `mktemp -d` (`0700`, short enough for the 108 bytes of a socket path), removed after. It
     * lives as long as the ssh that started it: delve runs in the background, and a watcher on the stdin of the session kills it when that
     * ends (the IDE closed the session, or the connection broke); without a terminal there is no SIGHUP to do it. The stdin of the watcher is
     * given explicitly: a non-interactive shell gives the background commands /dev/null.
     */
    fun serverScript(delve: String, directory: String, anyGoVersion: Boolean, log: Boolean): String {
        val arguments = listOf("dap", "--listen=unix:\$d/dlv.sock") + (if (anyGoVersion) listOf("--check-go-version=false") else emptyList()) +
            (if (log) listOf("--log", "--log-output=dap,debugger") else emptyList())
        return "umask 077; d=\$(mktemp -d) || exit 1; cd ${quote(directory)} || exit 1; exec 3<&0; " +
            "${quote(delve)} ${arguments.joinToString(" ")} </dev/null 2>&1 & p=\$!; " +
            "(cat <&3 >/dev/null; kill \$p 2>/dev/null) >/dev/null 2>&1 & " +
            "wait \$p; rm -rf \"\$d\""
    }

    private val LISTENING = Regex("""DAP server listening at:\s*(/\S+)""")

    /** `DAP server listening at: /tmp/tmp.x/dlv.sock`, the line delve prints for a unix socket (seen live on 1.27.2). */
    fun socketPath(line: String): String? = LISTENING.find(line)?.groupValues?.get(1)

    /** What ssh says when it cannot get in (the last lines of its output), with the way out for the usual refusals. */
    fun failure(output: String): String {
        val text = output.lines().map(String::trim).filter { it.isNotEmpty() }.takeLast(3).joinToString(" / ").ifEmpty { "no output" }
        val hint = when {
            "Host key verification failed" in output -> "the host is not in known_hosts yet: connect once from a terminal (ssh host) and accept its key"
            "Permission denied" in output -> "a key is needed, prompts are off: ssh-copy-id, or add the public key to ~/.ssh/authorized_keys there"
            "administratively prohibited" in output -> "the SSH server forbids forwarding: AllowStreamLocalForwarding must not be 'no' in its sshd_config"
            else -> null
        }
        return if (hint == null) text else "$text ($hint)"
    }

    /** A line of "Files to copy": `local` or `local=there`. */
    class FileEntry(val local: String, val remote: String?)

    /** `local[=there]` a line each; blank lines and `#` comments skipped. */
    fun fileEntries(text: String?): List<FileEntry> = text.orEmpty().lines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }.map { line ->
        val separator = line.indexOf('=')
        if (separator <= 0) FileEntry(line, null) else FileEntry(line.substring(0, separator).trim(), line.substring(separator + 1).trim().ifEmpty { null })
    }

    /**
     * The place there of a line without `=there`: its local path when that is relative and stays below the package directory (`certs/ca.pem`
     * is read as `certs/ca.pem` there, as here), otherwise the file's [name].
     */
    fun defaultRemote(local: String, name: String): String =
        local.replace('\\', '/').takeUnless { ':' in it || it.startsWith("/") }?.let { remotePath(it, name) } ?: name

    /**
     * Where a copied file or directory goes there, relative to the run directory: by default [localName], or the relative path given; null
     * for a path outside it (absolute, `~`, `..` above it). Run configurations come with projects, so a shared one must not write
     * `~/.ssh/authorized_keys` there: the copy stays in the run directory.
     */
    fun remotePath(remote: String?, localName: String): String? {
        val path = remote?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: localName
        if (path.startsWith("/") || path.startsWith("~")) return null
        val parts = ArrayDeque<String>()
        for (part in path.split('/')) when (part) {
            "", "." -> {}
            ".." -> parts.removeLastOrNull() ?: return null
            else -> parts.addLast(part)
        }
        return parts.joinToString("/").ifEmpty { null }
    }

    /** [path] with its links resolved is [root] or under it; false for a path that does not exist. */
    fun isInside(path: java.nio.file.Path, root: java.nio.file.Path): Boolean = runCatching { path.toRealPath().startsWith(root) }.getOrDefault(false)

    /** A file to send: its path in the run directory, its size and modification time for the [fingerprint]. */
    class Copied(val remote: String, val size: Long, val modified: Long)

    /** The set of copied files as one value: the same files at the same paths, unchanged, are not sent again. */
    fun fingerprint(files: List<Copied>): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        files.sortedBy { it.remote }.forEach { digest.update("${it.remote}|${it.size}|${it.modified}\n".toByteArray()) }
        return digest.digest().take(12).joinToString("") { "%02x".format(it) }
    }

    /** What the last copy into [runDirectory] was ([fingerprint]); empty when there was none. */
    fun markerScript(runDirectory: String): String = "cat ${quote("$runDirectory/.files")} 2>/dev/null; true"

    /** A tar stream on stdin (entries relative to [runDirectory], see [remotePath]) unpacked into it, then the marker. */
    fun unpackScript(runDirectory: String, fingerprint: String): String =
        "umask 077; tar -x -f - -C ${quote(runDirectory)} && printf %s ${quote(fingerprint)} > ${quote("$runDirectory/.files")}"
}

/**
 * A ustar stream of our own: the files go straight into the stdin of ssh, with the modes chosen here (`0600` files, `0700` directories:
 * configs and keys are readable by the user alone; Windows has no modes to carry). The platform's tar writer takes a file and the modes
 * of the file system.
 */
class GoTarWriter(private val out: java.io.OutputStream) {
    /** A directory entry; [path] has no leading slash. */
    fun directory(path: String, modified: Long) = header(path.trimEnd('/') + "/", 0, modified, DIRECTORY_MODE, '5')

    /** A regular file of [size] bytes from [input]. */
    fun file(path: String, size: Long, modified: Long, input: java.io.InputStream) {
        header(path, size, modified, FILE_MODE, '0')
        val copied = input.copyTo(out)
        check(copied == size) { "$path changed while being copied: $copied bytes, not $size" }
        val padding = (BLOCK - size % BLOCK) % BLOCK
        out.write(ByteArray(padding.toInt()))
    }

    /** The two empty blocks that end an archive. */
    fun finish() {
        out.write(ByteArray(BLOCK * 2))
        out.flush()
    }

    private fun header(path: String, size: Long, modified: Long, mode: Int, type: Char) {
        val (prefix, name) = split(path)
        val block = ByteArray(BLOCK)
        put(block, 0, 100, name)
        octal(block, 100, 8, mode.toLong())
        octal(block, 108, 8, 0)
        octal(block, 116, 8, 0)
        octal(block, 124, 12, size)
        octal(block, 136, 12, modified / 1000)
        block.fill(' '.code.toByte(), 148, 156)
        block[156] = type.code.toByte()
        put(block, 257, 6, "ustar\u0000")
        put(block, 263, 2, "00")
        put(block, 345, 155, prefix)
        val checksum = block.sumOf { it.toInt() and 0xff }
        put(block, 148, 8, "%06o".format(checksum) + "\u0000 ")
        out.write(block)
    }

    companion object {
        const val BLOCK = 512
        const val FILE_MODE = 0b110_000_000 // 0600
        const val DIRECTORY_MODE = 0b111_000_000 // 0700

        /** ustar keeps a path in a name of 100 bytes and a prefix of 155, split at a slash. */
        fun split(path: String): Pair<String, String> {
            if (path.toByteArray(Charsets.UTF_8).size <= 100) return "" to path
            for (i in path.indices.reversed()) {
                if (path[i] != '/') continue
                val prefix = path.substring(0, i)
                val name = path.substring(i + 1)
                if (prefix.toByteArray(Charsets.UTF_8).size <= 155 && name.toByteArray(Charsets.UTF_8).size in 1..100) return prefix to name
            }
            throw IllegalArgumentException("The path is too long for tar: $path")
        }

        private fun put(block: ByteArray, offset: Int, length: Int, text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            System.arraycopy(bytes, 0, block, offset, minOf(bytes.size, length))
        }

        private fun octal(block: ByteArray, offset: Int, length: Int, value: Long) =
            put(block, offset, length, java.lang.Long.toOctalString(value).padStart(length - 1, '0') + "\u0000")
    }
}
