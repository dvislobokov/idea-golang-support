package io.github.golangsupport.cli

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * One computation per key at a time: the callers that arrive while it runs wait for the same result instead of starting their own.
 * Seen live: 22–28 `go env -json` processes at every project open, each 0.2 s alone and 1 s and more in the storm.
 */
class SingleFlight<K : Any, V> {
    private class Flight<V>(val future: CompletableFuture<V>, val callers: AtomicInteger = AtomicInteger(1))

    private val running = ConcurrentHashMap<K, Flight<V>>()

    /** The result of [compute] for [key]: run on this thread, or awaited when another thread is running it. [onDone] gets the number of callers that shared it. */
    fun run(key: K, onDone: (callers: Int) -> Unit = {}, compute: () -> V): V {
        val mine = Flight<V>(CompletableFuture())
        val flight = running.putIfAbsent(key, mine)
        if (flight != null) {
            flight.callers.incrementAndGet()
            return try {
                flight.future.join()
            } catch (e: java.util.concurrent.CompletionException) {
                throw e.cause ?: e
            }
        }
        try {
            val value = compute()
            running.remove(key, mine)
            mine.future.complete(value)
            onDone(mine.callers.get())
            return value
        } catch (e: Throwable) {
            running.remove(key, mine)
            mine.future.completeExceptionally(e)
            throw e
        }
    }

    /** Whether a computation for [key] runs now. */
    fun isRunning(key: K): Boolean = running.containsKey(key)
}

/**
 * The answer of `go env -json` kept on disk between sessions, so that a project opens with the environment known at once (no process
 * in the first second, when indexing and highlighting compete for the cores) and the answer is checked in the background afterwards.
 * Pure: the key and the format; the file itself is [GoEnvironment]'s.
 */
object GoEnvDiskCache {
    const val FILE_NAME = "go-env.json"
    private const val FORMAT = 1

    /** Environment variables of the IDE process that change what `go env` answers. */
    val ENV_VARS = listOf("GOENV", "GOFLAGS", "GOROOT", "GOPATH", "GOTOOLCHAIN", "GOBIN", "GOPROXY", "GOOS", "GOARCH", "CGO_ENABLED", "GOMODCACHE", "GOEXPERIMENT")

    /** Values that differ on every run: GOGCCFLAGS names a fresh `go-build<random>` directory (seen live: every check counted as a change). */
    val VOLATILE = setOf("GOGCCFLAGS")

    fun sameAnswer(a: Map<String, String>, b: Map<String, String>): Boolean = a.filterKeys { it !in VOLATILE } == b.filterKeys { it !in VOLATILE }

    class Stored(val key: String, val values: Map<String, String>, val savedAt: Long)

    /**
     * What makes an answer stale: the `go` executable (path, size, modification time: an upgrade in place changes them), the go env file
     * (`go env -w` writes it) and the environment variables of [ENV_VARS].
     */
    fun key(executable: File, goEnvFile: File?, environment: Map<String, String>): String = buildString {
        append("format=").append(FORMAT)
        append("|go=").append(executable.path).append('#').append(executable.length()).append('#').append(executable.lastModified())
        append("|goenv=").append(goEnvFile?.path.orEmpty()).append('#').append(goEnvFile?.takeIf { it.isFile }?.lastModified() ?: -1L)
        for (name in ENV_VARS) append('|').append(name).append('=').append(environment[name].orEmpty())
    }

    /**
     * The go env file `go env -w` writes: `GOENV` of the environment, otherwise what the last answer said, otherwise `os.UserConfigDir()/go/env`.
     * Null for `GOENV=off`.
     */
    fun goEnvFile(environment: Map<String, String>, previous: Map<String, String>?, windows: Boolean, mac: Boolean, home: String): File? {
        val explicit = environment["GOENV"]?.takeIf { it.isNotEmpty() } ?: previous?.get("GOENV")?.takeIf { it.isNotEmpty() }
        if (explicit == "off") return null
        if (explicit != null) return File(explicit)
        val config = when {
            windows -> environment["AppData"] ?: environment["APPDATA"] ?: return null
            mac -> "$home/Library/Application Support"
            else -> environment["XDG_CONFIG_HOME"]?.takeIf { it.isNotEmpty() } ?: "$home/.config"
        }
        return File(File(config, "go"), "env")
    }

    fun encode(stored: Stored): String = JsonObject().apply {
        addProperty("key", stored.key)
        addProperty("savedAt", stored.savedAt)
        add("values", JsonObject().apply { stored.values.forEach { (k, v) -> addProperty(k, v) } })
    }.toString()

    fun decode(text: String): Stored? = runCatching {
        val root = JsonParser.parseString(text).asJsonObject
        val values = root.getAsJsonObject("values").entrySet().filter { it.value.isJsonPrimitive }.associate { it.key to it.value.asString }
        Stored(root.get("key").asString, values, root.get("savedAt").asLong)
    }.getOrNull()?.takeIf { it.values.isNotEmpty() }

    /** The stored answer when it was made under [key]. */
    fun valid(stored: Stored?, key: String): Stored? = stored?.takeIf { it.key == key }
}
