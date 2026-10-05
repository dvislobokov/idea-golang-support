package io.github.golangsupport.monitor

import com.google.gson.JsonObject
import io.github.golangsupport.debugger.json
import io.github.golangsupport.debugger.objects
import io.github.golangsupport.debugger.string

/** A frame of a goroutine dump: `main.worker(...)` and `/app/main.go:12`; [file] is null when the runtime (or delve) gives no source. */
data class GoStackFrame(val function: String, val file: String?, val line: Int)

/**
 * A goroutine of a dump: `goroutine 18 [chan receive, 2 minutes, locked to thread]:` and its frames. [state] is empty when delve dumped it:
 * its DAP threads carry no wait reason.
 */
data class GoGoroutine(
    val id: Int, val state: String, val waitMinutes: Int? = null, val lockedToThread: Boolean = false,
    val frames: List<GoStackFrame> = emptyList(), val createdBy: GoStackFrame? = null, val createdIn: Int? = null,
)

/**
 * Goroutine dumps in the runtime's format (what SIGQUIT and an unrecovered panic print, `runtime/traceback.go`): read, written, summed up.
 * Pure, except [collect], which asks a DAP adapter through the function it is given.
 */
object GoGoroutineDump {
    // `goroutine 7 gp=0xc000007c00 m=nil [GC worker (idle)]:` with GOTRACEBACK=system or higher (Go 1.23+), `goroutine 1 [running]:` otherwise
    private val HEADER = Regex("""^goroutine (\d+)(?: gp=\S+)?(?: m=\S+)?(?: mp=\S+)? \[([^\]]*)]:\s*$""")
    private val LOCATION = Regex("""^\s+(.+?):(\d+)(?: \+0x[0-9a-fA-F]+)?\s*$""")
    private val CREATED = Regex("""^created by (\S+?)(?: in goroutine (\d+))?\s*$""")
    private val MINUTES = Regex("""^(\d+) minutes?$""")

    fun parse(text: CharSequence): List<GoGoroutine> {
        val result = ArrayList<GoGoroutine>()
        var current: GoGoroutine? = null
        var pendingFunction: String? = null
        var pendingCreated: Pair<String, Int?>? = null
        fun flush() {
            current?.let { result += it }
            current = null
            pendingFunction = null
            pendingCreated = null
        }
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r')
            val header = HEADER.matchEntire(line)
            if (header != null) {
                flush()
                current = header(header.groupValues[1].toInt(), header.groupValues[2])
                continue
            }
            val goroutine = current ?: continue
            if (line.isBlank()) { flush(); continue }
            val location = LOCATION.matchEntire(line)
            // frames are a line of the function and an indented line of its place
            if (location != null) {
                val file = location.groupValues[1]
                val number = location.groupValues[2].toInt()
                val created = pendingCreated
                val function = pendingFunction
                current = when {
                    created != null -> goroutine.copy(createdBy = GoStackFrame(created.first, file, number), createdIn = created.second)
                    function != null -> goroutine.copy(frames = goroutine.frames + GoStackFrame(function, file, number))
                    else -> goroutine
                }
                pendingFunction = null
                pendingCreated = null
                continue
            }
            val created = CREATED.matchEntire(line)
            if (created != null) {
                pendingCreated = created.groupValues[1] to created.groupValues[2].toIntOrNull()
                continue
            }
            // `...additional frames elided...` and the like are not frames
            if (line.startsWith("...")) continue
            pendingFunction = functionName(line)
        }
        flush()
        return result
    }

    /** `net/http.(*Server).Serve(0xc000..., {0x...})` -> `net/http.(*Server).Serve`: the arguments are the last parentheses. */
    fun functionName(line: String): String {
        val text = line.trim()
        if (!text.endsWith(")")) return text
        var depth = 0
        for (index in text.indices.reversed()) {
            when (text[index]) {
                ')' -> depth++
                '(' -> if (--depth == 0) return text.substring(0, index)
            }
        }
        return text
    }

    private fun header(id: Int, bracket: String): GoGoroutine {
        val parts = bracket.split(", ").map(String::trim)
        val minutes = parts.firstNotNullOfOrNull { MINUTES.matchEntire(it)?.groupValues?.get(1)?.toInt() }
        return GoGoroutine(id, parts.firstOrNull().orEmpty(), minutes, lockedToThread = "locked to thread" in parts)
    }

    /** Back in the runtime's format, so the console filters link `file.go:12` and Analyze Stack Trace reads it again. */
    fun format(goroutines: List<GoGoroutine>): String = buildString {
        for (goroutine in goroutines) {
            val details = listOfNotNull(goroutine.state.takeIf { it.isNotEmpty() }, goroutine.waitMinutes?.let { if (it == 1) "1 minute" else "$it minutes" }, "locked to thread".takeIf { goroutine.lockedToThread })
            append("goroutine ").append(goroutine.id)
            if (details.isNotEmpty()) append(" [").append(details.joinToString(", ")).append(']')
            append(":\n")
            for (frame in goroutine.frames) appendFrame(frame.function + "(...)", frame)
            goroutine.createdBy?.let { appendFrame("created by ${it.function}" + (goroutine.createdIn?.let { id -> " in goroutine $id" } ?: ""), it) }
            append('\n')
        }
    }

    private fun StringBuilder.appendFrame(title: String, frame: GoStackFrame) {
        append(title).append('\n')
        if (frame.file != null) append('\t').append(frame.file).append(':').append(frame.line).append('\n')
    }

    /** `12 goroutines: 8 chan receive, 3 select, 1 running`: the states, the most frequent first; delve's dump has none to count. */
    fun summary(goroutines: List<GoGoroutine>): String {
        val states = goroutines.map { it.state }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        val count = "${goroutines.size} goroutine" + if (goroutines.size == 1) "" else "s"
        return if (states.isEmpty()) count else count + ": " + states.joinToString(", ") { "${it.value} ${it.key}" }
    }

    /** The dump through a DAP adapter that has the program stopped: `threads`, then `stackTrace` of each, [depth] frames deep. */
    fun collect(depth: Int, request: (String, JsonObject) -> JsonObject): List<GoGoroutine> {
        val threads = request("threads", JsonObject()).objects("threads")
        return threads.mapNotNull { thread ->
            val id = thread.get("id")?.takeIf { it.isJsonPrimitive }?.asInt ?: return@mapNotNull null
            // a goroutine that is gone by now answers with an error: listed without frames rather than failing the dump
            val frames = runCatching { request("stackTrace", json("threadId" to id, "startFrame" to 0, "levels" to depth)).objects("stackFrames") }.getOrDefault(emptyList())
            GoGoroutine(id, "", frames = frames.map(::frameOf))
        }.sortedBy { it.id }
    }

    /** A DAP stack frame as a dump frame. */
    fun frameOf(frame: JsonObject): GoStackFrame =
        GoStackFrame(frame.string("name").orEmpty(), frame.getAsJsonObject("source")?.string("path")?.replace('\\', '/'), frame.get("line")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0)
}

/** Where Dump Goroutines takes the goroutines from, given what is running. */
enum class GoDumpSource { DEBUG_SESSION, PROCESS, CHOOSE, NONE }

class GoDumpAvailability(val source: GoDumpSource, val description: String) {
    val enabled: Boolean get() = source != GoDumpSource.NONE
}

/** Dump Goroutines: what it acts on and how. Pure. */
object GoDumpGoroutines {
    /**
     * A paused Go debug session is asked through its own delve; else the process of the selected Run tab, the one Go process that runs,
     * or a choice among several. A debug session that runs cannot give stacks: delve answers `stackTrace` only when the program is stopped.
     */
    fun availability(debugSession: Boolean, debugSuspended: Boolean, selectedRunIsGo: Boolean, runningProcesses: Int): GoDumpAvailability = when {
        debugSession && debugSuspended -> GoDumpAvailability(GoDumpSource.DEBUG_SESSION, "The goroutines of the paused debug session with their stacks, in a console tab")
        selectedRunIsGo || runningProcesses == 1 -> GoDumpAvailability(GoDumpSource.PROCESS, "The goroutines of the running Go program with their stacks")
        runningProcesses > 1 -> GoDumpAvailability(GoDumpSource.CHOOSE, "Choose a running Go program and dump its goroutines")
        debugSession -> GoDumpAvailability(GoDumpSource.NONE, "Pause the program to dump its goroutines")
        else -> GoDumpAvailability(GoDumpSource.NONE, "No Go program started from a run configuration is running")
    }

    /** Windows has no SIGQUIT: delve attaches there always; elsewhere the setting decides. */
    fun viaDelve(setting: Boolean, windows: Boolean): Boolean = windows || setting
}
