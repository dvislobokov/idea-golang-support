package io.github.golangsupport.benchmark

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale

/**
 * Shared benchmark harness. The source file is compiled into the test source set of every module
 * that has benchmarks (`sourceSets.test.kotlin.srcDir("../tools/benchmark")`), because test
 * classes of one module are not visible to the others.
 *
 * A benchmark runs [WARMUPS] untimed and [ITERATIONS] timed iterations of a body, takes the
 * median, derives a unit metric (`median / quantity`) and compares the median with
 * `testData/benchmark/thresholds.json`:
 *
 * - the run fails when `median > stored medianMs * 1.50 * tolerance`
 * - `-Dgopsi.benchmark.tolerance=2.0` multiplies the allowed bound (CI on slower machines)
 * - `-Dgopsi.benchmark.update=true` writes the entry when it is missing or the run is faster
 * - without a stored entry and without the flag the run only reports
 *
 * Every benchmark prints `BENCH <name>: median=<ms> <unit>=<value> (threshold <value>)`.
 */
object BenchmarkSupport {
    const val WARMUPS = 3
    const val ITERATIONS = 5
    const val BASE_TOLERANCE = 1.50 // shared machines: 30% flapped between runs

    data class Entry(val medianMs: Double, val unit: String, val value: Double)

    private val lock = Any()

    fun goroot(): Path = Paths.get(System.getProperty("gopsi.goroot")?.takeIf { it.isNotBlank() } ?: "C:\\Program Files\\Go")

    fun testDataDir(): Path {
        System.getProperty("gopsi.testDataPath")?.takeIf { it.isNotBlank() }?.let { return Paths.get(it) }
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "testData")
            if (candidate.isDirectory) return candidate.toPath()
            dir = dir.parentFile
        }
        error("Cannot locate testData; set -Dgopsi.testDataPath")
    }

    fun thresholdsFile(): Path = testDataDir().resolve("benchmark").resolve("thresholds.json")

    private val update: Boolean get() = System.getProperty("gopsi.benchmark.update")?.let { it.isEmpty() || it.toBoolean() } ?: false
    private val tolerance: Double get() = System.getProperty("gopsi.benchmark.tolerance")?.toDoubleOrNull() ?: 1.0

    /** Reads a `$GOROOT/src` file, line endings normalized. */
    fun readGoroot(relative: String): String =
        Files.readString(goroot().resolve("src").resolve(relative)).replace("\r\n", "\n")

    /** All `.go` files directly in `$GOROOT/src/<dir>` (sorted), as (file name, text). */
    fun goFilesIn(dir: String): List<Pair<String, String>> =
        Files.list(goroot().resolve("src").resolve(dir)).use { s ->
            s.filter { it.fileName.toString().endsWith(".go") }.sorted().toList()
        }.map { it.fileName.toString() to Files.readString(it).replace("\r\n", "\n") }

    /**
     * Runs the benchmark and checks the threshold. [quantity] is the amount of work per iteration
     * in the unit's denominator (MB, thousand refs, ...); use 1.0 for plain milliseconds.
     * [prepare] runs untimed before every iteration (warm-up included) with its index.
     */
    fun run(name: String, unit: String, quantity: Double, prepare: (Int) -> Unit = {}, body: () -> Unit): Entry =
        runTimed(name, unit, quantity, prepare) { timed(body) }

    /**
     * Like [run], but [body] measures itself and returns the milliseconds of iteration `i`: for
     * editing benchmarks whose iterations interleave untimed edits and commits with timed segments
     * (time a segment with [timed]). [prepare] and a GC run untimed before every iteration.
     */
    fun runTimed(name: String, unit: String, quantity: Double, prepare: (Int) -> Unit = {}, body: (Int) -> Double): Entry {
        val times = ArrayList<Double>()
        for (i in 0 until WARMUPS + ITERATIONS) {
            prepare(i)
            System.gc() // untimed; keeps GC pauses of earlier iterations out of the measurement
            val ms = body(i)
            if (i >= WARMUPS) times += ms
        }
        times.sort()
        val median = times[times.size / 2]
        val measured = Entry(median, unit, if (quantity > 0) median / quantity else median)
        check(name, measured)
        return measured
    }

    /** Milliseconds spent in [block]. */
    inline fun timed(block: () -> Unit): Double {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / 1_000_000.0
    }

    /** Median of [values] (not empty). */
    fun median(values: List<Double>): Double = values.sorted()[values.size / 2]

    /** Prints a line without a threshold: `BENCH <name>: <text> (report only)`. */
    fun report(name: String, text: String) = println("BENCH $name: $text (report only)")

    fun format(v: Double): String = fmt(v)

    private fun check(name: String, measured: Entry) {
        val file = thresholdsFile()
        synchronized(lock) {
            val stored = read(file)
            val threshold = stored[name]
            println(
                "BENCH $name: median=${fmt(measured.medianMs)} ${measured.unit}=${fmt(measured.value)} " +
                    "(threshold ${threshold?.let { fmt(it.value) } ?: "none"})",
            )
            if (update && (threshold == null || measured.medianMs < threshold.medianMs)) {
                stored[name] = measured
                write(file, stored)
            }
            if (threshold != null) {
                val allowed = threshold.medianMs * BASE_TOLERANCE * tolerance
                if (measured.medianMs > allowed) {
                    throw AssertionError(
                        "Benchmark $name regressed: median ${fmt(measured.medianMs)} ms > allowed ${fmt(allowed)} ms " +
                            "(stored ${fmt(threshold.medianMs)} ms, +${((BASE_TOLERANCE - 1) * 100).toInt()}%, tolerance x$tolerance)",
                    )
                }
            }
        }
    }

    private fun fmt(v: Double): String = String.format(Locale.ROOT, if (Math.abs(v) < 1.0) "%.3f" else "%.2f", v)

    private val entry = Regex(
        "\"([^\"]+)\"\\s*:\\s*\\{\\s*\"medianMs\"\\s*:\\s*([-0-9.eE]+)\\s*,\\s*\"unit\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"value\"\\s*:\\s*([-0-9.eE]+)\\s*\\}",
    )

    private fun read(file: Path): MutableMap<String, Entry> {
        val result = sortedMapOf<String, Entry>()
        if (!Files.exists(file)) return result
        for (m in entry.findAll(Files.readString(file))) {
            result[m.groupValues[1]] = Entry(m.groupValues[2].toDouble(), m.groupValues[3], m.groupValues[4].toDouble())
        }
        return result
    }

    private fun write(file: Path, entries: Map<String, Entry>) {
        Files.createDirectories(file.parent)
        val text = entries.entries.joinToString(",\n", "{\n", "\n}\n") { (k, e) ->
            "  \"$k\": {\"medianMs\": ${fmt(e.medianMs)}, \"unit\": \"${e.unit}\", \"value\": ${fmt(e.value)}}"
        }
        Files.writeString(file, text)
    }
}
