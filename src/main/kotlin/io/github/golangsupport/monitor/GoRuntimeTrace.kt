package io.github.golangsupport.monitor

/**
 * What the Go runtime prints to stderr under `GODEBUG=gctrace=1,schedtrace=1000`: no code in the program, only an environment variable
 * at its start. Checked on Go 1.24.7:
 *
 * `gc 4 @1.761s 0%: 0+0+0 ms clock, 0+0/0/0+0 ms cpu, 36->36->36 MB, 36 MB goal, 0 MB stacks, 0 MB globals, 12 P`
 * `SCHED 1000ms: gomaxprocs=12 idleprocs=12 threads=8 spinningthreads=0 needspinning=0 idlethreads=5 runqueue=0 [0 0 0 0]`
 * `init os @2.7 ms, 0 ms clock, 2352 bytes, 32 allocs`
 */
sealed class GoRuntimeEvent {
    /** One garbage collection; the sizes are in megabytes as the runtime prints them; [clockMs] is the wall time of its three phases. */
    class Gc(
        val number: Int, val atSeconds: Double, val cpuPercent: Int, val clockMs: Double,
        val heapBeforeMb: Double, val heapAfterMb: Double, val liveMb: Double, val goalMb: Double, val stacksMb: Double, val procs: Int,
    ) : GoRuntimeEvent()

    /** The scheduler once a second; [queued] is the global run queue with the local ones added. */
    class Sched(val atMs: Long, val procs: Int, val idleProcs: Int, val threads: Int, val idleThreads: Int, val spinningThreads: Int, val queued: Int) : GoRuntimeEvent()

    /** The initialization of one package at start-up (`inittrace=1`). */
    class Init(val packagePath: String, val atMs: Double, val clockMs: Double, val bytes: Long, val allocs: Long) : GoRuntimeEvent()
}

object GoRuntimeTrace {
    /** The environment variable of a run with telemetry. Not `scheddetail`: it lists every goroutine every second. */
    const val GODEBUG = "gctrace=1,schedtrace=1000"

    private val GC = Regex(
        """^gc (\d+) @([\d.]+)s (\d+)%: ([\d.+]+) ms clock, [\d.+/]+ ms cpu, ([\d.]+)->([\d.]+)->([\d.]+) MB, ([\d.]+) MB goal, ([\d.]+) MB stacks, [\d.]+ MB globals, (\d+) P""",
    )
    private val SCHED = Regex("""^SCHED (\d+)ms: gomaxprocs=(\d+) idleprocs=(\d+) threads=(\d+) spinningthreads=(\d+)(?: needspinning=\d+)? idlethreads=(\d+) runqueue=(\d+) \[([\d ]*)]""")
    private val INIT = Regex("""^init (\S+) @([\d.]+) ms, ([\d.]+) ms clock, (\d+) bytes, (\d+) allocs""")

    /** Cheap check before the regexes: almost every line of a program is something else. */
    fun isTraceLine(line: String): Boolean = line.startsWith("gc ") || line.startsWith("SCHED ") || line.startsWith("init ")

    fun parse(line: String): GoRuntimeEvent? {
        val text = line.trimEnd()
        if (!isTraceLine(text)) return null
        GC.find(text)?.let { m ->
            val g = m.groupValues
            return GoRuntimeEvent.Gc(
                g[1].toInt(), g[2].toDouble(), g[3].toInt(), g[4].split('+').sumOf { it.toDoubleOrNull() ?: 0.0 },
                g[5].toDouble(), g[6].toDouble(), g[7].toDouble(), g[8].toDouble(), g[9].toDouble(), g[10].toInt(),
            )
        }
        SCHED.find(text)?.let { m ->
            val g = m.groupValues
            val local = g[8].trim().split(' ').filter { it.isNotEmpty() }.sumOf { it.toInt() }
            return GoRuntimeEvent.Sched(g[1].toLong(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[6].toInt(), g[5].toInt(), g[7].toInt() + local)
        }
        INIT.find(text)?.let { m ->
            val g = m.groupValues
            return GoRuntimeEvent.Init(g[1], g[2].toDouble(), g[3].toDouble(), g[4].toLong(), g[5].toLong())
        }
        return null
    }
}

/**
 * The latest word of the runtime about a program: what the charts show once a second. Written from the reader of the process, read from
 * the sampler; the numbers are small, a copy under a lock is enough.
 */
class GoRuntimeState {
    private var gc: GoRuntimeEvent.Gc? = null
    private var sched: GoRuntimeEvent.Sched? = null
    private var collections = 0
    private var pauseMsSinceRead = 0.0
    private var collectionsSinceRead = 0

    class Snapshot(
        val liveHeapMb: Double?, val goalMb: Double?, val heapBeforeMb: Double?, val gcCpuPercent: Int?, val collections: Int,
        /** Since the previous snapshot: what happened in the last second. */
        val pauseMs: Double, val collectionsPerSecond: Int,
        val threads: Int?, val idleThreads: Int?, val procs: Int?, val idleProcs: Int?, val queued: Int?,
    )

    @Synchronized
    fun accept(event: GoRuntimeEvent) {
        when (event) {
            is GoRuntimeEvent.Gc -> {
                gc = event
                collections++
                collectionsSinceRead++
                pauseMsSinceRead += event.clockMs
            }
            is GoRuntimeEvent.Sched -> sched = event
            is GoRuntimeEvent.Init -> Unit
        }
    }

    @Synchronized
    fun read(): Snapshot = Snapshot(
        gc?.liveMb, gc?.goalMb, gc?.heapBeforeMb, gc?.cpuPercent, collections, pauseMsSinceRead, collectionsSinceRead,
        sched?.threads, sched?.idleThreads, sched?.procs, sched?.idleProcs, sched?.queued,
    ).also {
        pauseMsSinceRead = 0.0
        collectionsSinceRead = 0
    }

    @Synchronized
    fun hasData(): Boolean = gc != null || sched != null
}
