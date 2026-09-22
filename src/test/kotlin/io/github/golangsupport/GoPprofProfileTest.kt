package io.github.golangsupport

import io.github.golangsupport.monitor.GoPprofRaw
import io.github.golangsupport.monitor.GoPprofViews
import io.github.golangsupport.monitor.PprofSampleType
import org.junit.Assert.assertEquals
import org.junit.Test

/** `go tool pprof -raw` of Go 1.24, as it printed the profiles of the pprof demo (shortened). */
class GoPprofProfileTest {
    private val cpu = """
        PeriodType: cpu nanoseconds
        Period: 10000000
        Time: 2026-09-22 14:52:01.123 +0300 MSK
        Duration: 3.00
        Samples:
        samples/count cpu/nanoseconds
                  1   10000000: 1 2 3
                  2   20000000: 4 3
                  1   10000000: 5 5 3
        Locations
             1: 0x9dbf51 M=1 runtime.(*mheap).allocMSpanLocked C:/Program Files/Go/src/runtime/mheap.go:1131:0 s=1113
             2: 0x9dc000 M=1 runtime.mallocgc C:/Program Files/Go/src/runtime/malloc.go:1000:0 s=900
             3: 0xc0db4a M=1 main.main.func1 C:/p/main.go:24:0 s=21
             4: 0xc0db00 M=1 main.work C:/p/main.go:40:0 s=38
                     main.helper C:/p/main.go:31:0 s=30
             5: 0xc0dc00 M=1 main.fib C:/p/main.go:50:0 s=49
        Mappings
        1: 0x400000/0xd6c000/0x0 C:/p/p.exe  [FN]
    """.trimIndent()

    @Test fun parsesSamplesAndInlinedFrames() {
        val profile = GoPprofRaw.parse(cpu)
        assertEquals(listOf(PprofSampleType("samples", "count"), PprofSampleType("cpu", "nanoseconds")), profile.types)
        assertEquals("3.00", profile.duration)
        assertEquals(1, profile.defaultIndex)
        assertEquals(40_000_000L, profile.total(1))
        assertEquals(listOf("runtime.(*mheap).allocMSpanLocked", "runtime.mallocgc", "main.main.func1"), profile.samples[0].stack.map { it.function })
        // the inlined main.work is inside main.helper: the leaf first, its caller next
        assertEquals(listOf("main.work", "main.helper", "main.main.func1"), profile.samples[1].stack.map { it.function })
        assertEquals("C:/Program Files/Go/src/runtime/mheap.go", profile.samples[0].stack[0].file)
        assertEquals(1131, profile.samples[0].stack[0].line)
    }

    @Test fun topAndFlame() {
        val profile = GoPprofRaw.parse(cpu)
        val top = GoPprofViews.top(profile, 1)
        assertEquals("main.work", top[0].function)
        assertEquals(20_000_000L, top[0].flat)
        val func1 = top.single { it.function == "main.main.func1" }
        assertEquals(0L, func1.flat)
        assertEquals(40_000_000L, func1.cum)
        // recursion counts once
        assertEquals(10_000_000L, top.single { it.function == "main.fib" }.cum)

        val root = GoPprofViews.flame(profile, 1)
        assertEquals(40_000_000L, root.value)
        val main = root.children.getValue("main.main.func1")
        assertEquals(listOf("main.helper", "runtime.mallocgc", "main.fib"), main.sortedChildren().map { it.function })
        assertEquals("main.fib", main.children.getValue("main.fib").children.values.single().function)
    }

    @Test fun heapWithLabels() {
        val heap = """
            PeriodType: space bytes
            Period: 524288
            Samples:
            alloc_objects/count alloc_space/bytes inuse_objects/count inuse_space/bytes
                   5461     524336       5461     524336: 1 2
                           bytes:[96]
                      1      65536          0          0: 2
                           bytes:[65536]
            Locations
                 1: 0xb7cade M=1 compress/flate.newDeflateFast C:/Go/src/compress/flate/deflatefast.go:64:0 s=63
                         compress/flate.(*compressor).init C:/Go/src/compress/flate/deflate.go:586:0 s=569
                 2: 0xc0db4a M=1 main.main C:/p/main.go:10:0 s=9
        """.trimIndent()
        val profile = GoPprofRaw.parse(heap)
        assertEquals(listOf("alloc_objects", "alloc_space", "inuse_objects", "inuse_space"), profile.types.map { it.type })
        assertEquals(3, profile.defaultIndex)
        assertEquals(2, profile.samples.size)
        assertEquals(listOf("compress/flate.newDeflateFast", "compress/flate.(*compressor).init", "main.main"), profile.samples[0].stack.map { it.function })
        assertEquals(524336L, profile.total(3))
        assertEquals(589872L, profile.total(1))
        // nothing in use for the second sample: not in the graph of inuse_space
        assertEquals(1, GoPprofViews.flame(profile, 3).children.size)
    }

    @Test fun formats() {
        assertEquals("1.50s", GoPprofViews.format(1_500_000_000, "nanoseconds"))
        assertEquals("20.0ms", GoPprofViews.format(20_000_000, "nanoseconds"))
        assertEquals("12.3k", GoPprofViews.format(12_345, "count"))
        assertEquals("25.0%", GoPprofViews.percent(1, 4))
    }
}
