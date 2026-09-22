package io.github.golangsupport.monitor

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI

/** A socket a process listens on: the local address as the system prints it (`127.0.0.1`, `0.0.0.0`, `::`, `::1`) and the port. */
data class ListeningSocket(val address: String, val port: Int)

/**
 * The TCP ports a process listens on, from the operating system: `netstat -ano` on Windows, `/proc` on Linux, `lsof` on macOS. Nothing
 * of the program is needed; the list is what a pprof endpoint is looked for on.
 */
object GoListeningPorts {
    /** Blocks for a moment (a process on Windows and macOS). Not for EDT. */
    fun of(pids: Set<Long>): List<ListeningSocket> = runCatching {
        when {
            SystemInfo.isWindows -> parseNetstat(run("netstat", "-ano", "-p", "TCP") + "\n" + run("netstat", "-ano", "-p", "TCPv6"), pids)
            SystemInfo.isLinux -> linux(pids)
            else -> pids.flatMap { parseLsof(run("lsof", "-nP", "-a", "-iTCP", "-sTCP:LISTEN", "-p", it.toString())) }
        }
    }.getOrDefault(emptyList()).distinct()

    /**
     * `  TCP    127.0.0.1:16060        0.0.0.0:0              LISTENING       38232`: a listening socket is the one whose foreign address is
     * `0.0.0.0:0` or `[::]:0`; the word of its state depends on the language of Windows (ПРОСЛУШИВАНИЕ), the addresses do not.
     */
    fun parseNetstat(text: String, pids: Set<Long>): List<ListeningSocket> = text.lineSequence().mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 5 || !parts[0].equals("TCP", ignoreCase = true)) return@mapNotNull null
        if (parts[2] != "0.0.0.0:0" && parts[2] != "[::]:0" && parts[2] != "*:*") return@mapNotNull null
        val pid = parts.last().toLongOrNull() ?: return@mapNotNull null
        if (pid !in pids) return@mapNotNull null
        socket(parts[1])
    }.toList()

    /** `127.0.0.1:16060`, `[::1]:6060`, `*:6060` -> the address and the port. */
    fun socket(text: String): ListeningSocket? {
        val colon = text.lastIndexOf(':')
        if (colon <= 0) return null
        val port = text.substring(colon + 1).toIntOrNull() ?: return null
        val address = text.substring(0, colon).removePrefix("[").removeSuffix("]").let { if (it == "*") "0.0.0.0" else it }
        return ListeningSocket(address, port)
    }

    /** `lsof -nP -iTCP -sTCP:LISTEN`: `app 123 me 7u IPv4 0x… 0t0 TCP 127.0.0.1:6060 (LISTEN)`. */
    fun parseLsof(text: String): List<ListeningSocket> = text.lineSequence().mapNotNull { line ->
        if (!line.contains("(LISTEN)")) return@mapNotNull null
        val name = line.substringBefore(" (LISTEN)").trim().split(Regex("\\s+")).lastOrNull() ?: return@mapNotNull null
        socket(name)
    }.toList()

    /** `/proc/net/tcp`: `sl local_address rem_address st … inode`, the address in hex, state `0A` listening. Inode -> the socket. */
    fun parseProcNetTcp(text: String, ipv6: Boolean): Map<Long, ListeningSocket> = text.lineSequence().drop(1).mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 10 || parts[3] != "0A") return@mapNotNull null
        val (hexAddress, hexPort) = parts[1].split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
        val address = when {
            !ipv6 -> hexAddress.chunked(2).reversed().joinToString(".") { it.toInt(16).toString() }
            hexAddress.trimStart('0').isEmpty() -> "::"
            hexAddress == "00000000000000000000000001000000" -> "::1"
            else -> "::"
        }
        (parts[9].toLongOrNull() ?: return@mapNotNull null) to ListeningSocket(address, hexPort.toInt(16))
    }.toMap()

    private fun linux(pids: Set<Long>): List<ListeningSocket> {
        val sockets = parseProcNetTcp(File("/proc/net/tcp").readText(), false) + parseProcNetTcp(runCatching { File("/proc/net/tcp6").readText() }.getOrDefault(""), true)
        val inodes = pids.flatMap { pid ->
            File("/proc/$pid/fd").listFiles().orEmpty().mapNotNull { runCatching { java.nio.file.Files.readSymbolicLink(it.toPath()).toString() }.getOrNull() }
                .mapNotNull { Regex("""socket:\[(\d+)]""").matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }
        }
        return inodes.mapNotNull(sockets::get)
    }

    private fun run(vararg command: String): String = ExecUtil.execAndGetOutput(GeneralCommandLine(*command), 5_000).stdout
}

/** A pprof endpoint that answered: the base URL (`http://127.0.0.1:6060/debug/pprof/`) and whether expvar is next to it. */
data class GoPprofEndpoint(val url: String, val hasExpvar: Boolean) {
    fun profileUrl(kind: GoPprofKind): String = url + kind.path
}

/** What a pprof endpoint serves, and how long it takes: the CPU profile and the trace record for [seconds]. */
enum class GoPprofKind(val title: String, val path: String, val seconds: Int = 0, val trace: Boolean = false) {
    CPU("CPU 30s", "profile?seconds=30", 30),
    HEAP("Heap", "heap"),
    ALLOCS("Allocs", "allocs"),
    GOROUTINES("Goroutines", "goroutine"),
    MUTEX("Mutex", "mutex"),
    BLOCK("Block", "block"),
    TRACE("Trace 5s", "trace?seconds=5", 5, trace = true);

    override fun toString(): String = title
}

object GoPprofProbe {
    const val PREFIX = "/debug/pprof/"
    private const val TIMEOUT_MS = 400

    /** The host to ask for a listening address: a wildcard means any, `127.0.0.1` answers; an address of its own is asked as it is. */
    fun host(address: String): String = when (address) {
        "0.0.0.0", "::", "" -> "127.0.0.1"
        else -> if (address.contains(':')) "[$address]" else address
    }

    /** The index page of `net/http/pprof`, not some other page that happens to be at that path. */
    fun isIndex(body: String): Boolean = body.contains("Types of profiles available") || (body.contains("goroutine?debug=") && body.contains("heap?debug="))

    /** The first of [sockets] that serves pprof at the standard path. Blocks: a short HTTP request per port. Not for EDT. */
    fun find(sockets: List<ListeningSocket>): GoPprofEndpoint? {
        for (socket in sockets.sortedBy { if (it.port == 6060) 0 else 1 }) {
            val base = "http://${host(socket.address)}:${socket.port}$PREFIX"
            val body = get(base, TIMEOUT_MS, 64 * 1024) ?: continue
            if (!isIndex(body)) continue
            val vars = get("http://${host(socket.address)}:${socket.port}/debug/vars", TIMEOUT_MS, 4096)?.trimStart()?.startsWith("{") == true
            return GoPprofEndpoint(base, vars)
        }
        return null
    }

    /**
     * Why a mutex or block profile would open empty, from its text form (`?debug=1`): the runtime does not record them unless the
     * program says so (`sampling period=0`, or a header without a single `cycles count @ …` line). Null when it has samples.
     */
    fun emptyReason(kind: GoPprofKind, debugText: String): String? {
        if (kind != GoPprofKind.MUTEX && kind != GoPprofKind.BLOCK) return null
        val sample = Regex("""^\d+ \d+ @""")
        if (debugText.lineSequence().any { sample.containsMatchIn(it) }) return null
        return when {
            kind == GoPprofKind.MUTEX && debugText.contains("sampling period=0") ->
                "The program does not record mutex contention: add <code>runtime.SetMutexProfileFraction(5)</code> at its start (1 in 5 events)."
            kind == GoPprofKind.MUTEX -> "No mutex contention has been recorded yet."
            else -> "No blocking has been recorded: the program records it only after <code>runtime.SetBlockProfileRate(1)</code> (every event; a bigger rate in nanoseconds samples less)."
        }
    }

    /** The text of a GET, at most [limit] bytes; null for anything but 200 or no answer in time. */
    fun get(url: String, timeoutMs: Int, limit: Int): String? = runCatching {
        val connection = URI(url).toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        try {
            if (connection.responseCode != 200) return null
            connection.inputStream.use { String(it.readNBytes(limit), Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /** Writes the profile at [url] to [target]; [timeoutMs] covers the seconds a CPU profile or a trace records for. */
    fun download(url: String, target: File, timeoutMs: Int) {
        val connection = URI(url).toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
        connection.connectTimeout = 2_000
        connection.readTimeout = timeoutMs
        try {
            val code = connection.responseCode
            if (code != 200) {
                val text = connection.errorStream?.use { String(it.readNBytes(2048), Charsets.UTF_8) }.orEmpty().trim()
                throw java.io.IOException("$url answered $code${if (text.isEmpty()) "" else ": $text"}")
            }
            connection.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * `goroutine?debug=1`: the goroutines grouped by their stacks.
 *
 * ```
 * 5 @ 0x5e4b0e 0x5e89c7 0x7cd3fd 0x5ec6a1
 * #	0x5e89c6	time.Sleep+0x166	C:/Program Files/Go/src/runtime/time.go:338
 * #	0x7cd3fc	main.main.func1+0x1c	C:/p/main.go:11
 * ```
 */
object GoGoroutineDump {
    class Frame(val function: String, val location: String)

    class Group(val count: Int, val frames: List<Frame>) {
        /** The first frame of the program: where the goroutines are, past the runtime and the standard library that park them. */
        val ownFrame: Frame? get() = frames.firstOrNull { !isLibrary(it.function) } ?: frames.firstOrNull()

        /** What the goroutines wait in: the top frame, when it is the runtime (`time.Sleep`, `runtime.gopark`, `sync.(*Mutex).Lock`). */
        val waitingIn: String? get() = frames.firstOrNull()?.function?.takeIf { isLibrary(it) && it != ownFrame?.function }

        val title: String get() = "$count × ${ownFrame?.function ?: "?"}" + (waitingIn?.let { "  — in $it" } ?: "")
    }

    /**
     * The standard library (and golang.org/x): its import paths have no dot in their first element (`internal/poll`, `net/http`), a
     * module has one (`example.com/app`), and `main` is the program.
     */
    fun isLibrary(function: String): Boolean {
        val lastSlash = function.lastIndexOf('/')
        val packagePath = function.substring(0, function.indexOf('.', lastSlash + 1).let { if (it < 0) function.length else it })
        if (packagePath == "main" || packagePath.startsWith("main.")) return false
        if (packagePath.startsWith("golang.org/x/")) return true
        return !packagePath.substringBefore('/').contains('.')
    }

    fun parse(text: String): List<Group> {
        val groups = ArrayList<Group>()
        var count = -1
        val frames = ArrayList<Frame>()
        fun flush() {
            if (count > 0) groups += Group(count, frames.toList())
            count = -1
            frames.clear()
        }
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            val header = Regex("""^(\d+) @ """).find(line)
            when {
                header != null -> { flush(); count = header.groupValues[1].toInt() }
                line.startsWith("#") && count > 0 -> {
                    val parts = line.removePrefix("#").trim().split('\t').map { it.trim() }.filter { it.isNotEmpty() }
                    if (parts.size >= 3) frames += Frame(parts[1].substringBeforeLast("+0x"), parts[2])
                }
                line.isBlank() -> flush()
            }
        }
        flush()
        return groups.sortedByDescending { it.count }
    }

    /** `goroutine profile: total 8`. */
    fun total(text: String): Int? = Regex("""goroutine profile: total (\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
}
