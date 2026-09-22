package io.github.golangsupport.monitor

/** One frame of a stack: the function, and where in its file the sample is. */
data class PprofFrame(val function: String, val file: String, val line: Int)

/** One sample: a value per sample type, and its stack from the leaf (where it was taken) to the root. */
class PprofSample(val values: LongArray, val stack: List<PprofFrame>)

/** A sample type of a profile: `cpu` in `nanoseconds`, `inuse_space` in `bytes`, `samples` in `count`. */
data class PprofSampleType(val type: String, val unit: String) {
    override fun toString(): String = "$type ($unit)"
}

class PprofProfile(val types: List<PprofSampleType>, val samples: List<PprofSample>, val duration: String?) {
    /** What pprof shows by default: the last type (`cpu`, `inuse_space`, `delay`). */
    val defaultIndex: Int get() = (types.size - 1).coerceAtLeast(0)

    fun total(index: Int): Long = samples.sumOf { it.values.getOrElse(index) { 0 } }
}

/**
 * `go tool pprof -raw`: the profile as text, so that the IDE needs neither protobuf nor Graphviz.
 *
 * ```
 * Samples:
 * samples/count cpu/nanoseconds
 *           2   20000000: 25 26 27 28 4
 *                 bytes:[96]
 * Locations
 *      1: 0x9dbf51 M=1 runtime.(*mheap).allocMSpanLocked C:/Program Files/Go/src/runtime/mheap.go:1131:0 s=1113
 *              compress/flate.(*compressor).init C:/Program Files/Go/src/compress/flate/deflate.go:586:0 s=569
 * Mappings
 * ```
 * A sample lists its locations from the leaf; a location with more lines has functions inlined into it, the innermost first.
 */
object GoPprofRaw {
    private val SAMPLE = Regex("""^\s*([\d\s]+):((?:\s+\d+)*)\s*$""")
    private val LOCATION = Regex("""^\s*(\d+): 0x[0-9a-fA-F]+(?: M=\d+)?(?: (.*))?$""")
    private val LINE = Regex("""^(\S+) (.+):(\d+):\d+(?: s=\d+)?$""")

    fun parse(text: String): PprofProfile {
        val types = ArrayList<PprofSampleType>()
        val rawSamples = ArrayList<Pair<LongArray, List<Int>>>()
        val locations = HashMap<Int, MutableList<PprofFrame>>()
        var duration: String? = null
        var section = ""
        var currentLocation: Int? = null
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            when {
                line.startsWith("Duration:") -> duration = line.substringAfter(':').trim()
                line == "Samples:" -> { section = "header"; continue }
                line == "Locations" -> { section = "locations"; continue }
                line == "Mappings" -> { section = "mappings"; continue }
            }
            when (section) {
                "header" -> {
                    types += line.trim().split(Regex("\\s+")).map { PprofSampleType(it.substringBefore('/'), it.substringAfter('/', "count")) }
                    section = "samples"
                }
                "samples" -> {
                    val match = SAMPLE.matchEntire(line) ?: continue // label lines: `bytes:[96]`
                    val values = match.groupValues[1].trim().split(Regex("\\s+")).map { it.toLongOrNull() ?: 0 }.toLongArray()
                    val ids = match.groupValues[2].trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toInt() }
                    rawSamples += values to ids
                }
                "locations" -> {
                    val location = LOCATION.matchEntire(line)
                    if (location != null) {
                        currentLocation = location.groupValues[1].toInt()
                        val frames = locations.getOrPut(currentLocation) { ArrayList() }
                        location.groupValues[2].takeIf { it.isNotBlank() }?.let { frame(it)?.let(frames::add) }
                    } else {
                        // a function inlined at the location above: its caller, the next frame outwards
                        val id = currentLocation ?: continue
                        frame(line.trim())?.let { locations.getOrPut(id) { ArrayList() }.add(it) }
                    }
                }
            }
        }
        val samples = rawSamples.map { (values, ids) -> PprofSample(values, ids.flatMap { locations[it].orEmpty() }) }
        return PprofProfile(types, samples, duration)
    }

    private fun frame(text: String): PprofFrame? {
        val match = LINE.matchEntire(text.trim()) ?: return text.trim().takeIf { it.isNotEmpty() }?.let { PprofFrame(it.substringBefore(' '), "", 0) }
        return PprofFrame(match.groupValues[1], match.groupValues[2], match.groupValues[3].toInt())
    }
}

/** A row of Top: a function with what it spent itself (flat) and with its callees (cum). */
class PprofTopRow(val function: String, val file: String, val line: Int, val flat: Long, val cum: Long)

/** A node of the flame graph: a function called along one path, the sum of the samples below it. */
class PprofFlameNode(val function: String, val frame: PprofFrame?) {
    var value: Long = 0
    val children: LinkedHashMap<String, PprofFlameNode> = LinkedHashMap()

    /** Children with the biggest first: the order the graph draws them in, left to right. */
    fun sortedChildren(): List<PprofFlameNode> = children.values.sortedByDescending { it.value }
}

object GoPprofViews {
    fun top(profile: PprofProfile, index: Int): List<PprofTopRow> {
        val flat = LinkedHashMap<String, Long>()
        val cum = LinkedHashMap<String, Long>()
        val where = HashMap<String, PprofFrame>()
        for (sample in profile.samples) {
            val value = sample.values.getOrElse(index) { 0 }
            if (value == 0L || sample.stack.isEmpty()) continue
            val leaf = sample.stack.first()
            flat.merge(leaf.function, value, Long::plus)
            // a recursive function counts once per sample
            for (function in sample.stack.map { it.function }.toSet()) cum.merge(function, value, Long::plus)
            for (frame in sample.stack) where.putIfAbsent(frame.function, frame)
        }
        return cum.keys.map { function ->
            val frame = where[function]
            PprofTopRow(function, frame?.file.orEmpty(), frame?.line ?: 0, flat[function] ?: 0, cum.getValue(function))
        }.sortedWith(compareByDescending<PprofTopRow> { it.flat }.thenByDescending { it.cum })
    }

    /** The stacks from the root down: `main.main` above what it calls, the leaves at the bottom of each column. */
    fun flame(profile: PprofProfile, index: Int): PprofFlameNode {
        val root = PprofFlameNode("all", null)
        for (sample in profile.samples) {
            val value = sample.values.getOrElse(index) { 0 }
            if (value == 0L) continue
            root.value += value
            var node = root
            for (frame in sample.stack.asReversed()) {
                node = node.children.getOrPut(frame.function) { PprofFlameNode(frame.function, frame) }
                node.value += value
            }
        }
        return root
    }

    /** `12.34s`, `850ms`, `1.5MB`, `12.3k`: a value in its unit. */
    fun format(value: Long, unit: String): String = when (unit) {
        "nanoseconds" -> when {
            value >= 1_000_000_000 -> String.format(java.util.Locale.ROOT, "%.2fs", value / 1e9)
            value >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1fms", value / 1e6)
            value >= 1_000 -> String.format(java.util.Locale.ROOT, "%.1fµs", value / 1e3)
            else -> "${value}ns"
        }
        "bytes" -> ChartFormats.bytes(value.toDouble())
        else -> when {
            value >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1fM", value / 1e6)
            value >= 10_000 -> String.format(java.util.Locale.ROOT, "%.1fk", value / 1e3)
            else -> value.toString()
        }
    }

    fun percent(value: Long, total: Long): String = if (total == 0L) "0%" else String.format(java.util.Locale.ROOT, "%.1f%%", value * 100.0 / total)
}
