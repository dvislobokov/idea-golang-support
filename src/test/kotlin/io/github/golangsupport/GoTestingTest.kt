package io.github.golangsupport

import io.github.golangsupport.debugger.GoGoroutineName
import io.github.golangsupport.debugger.GoValuePresentation
import io.github.golangsupport.testing.GoBenchmarks
import io.github.golangsupport.lsp.GoplsHover
import io.github.golangsupport.testing.GoCoverage
import io.github.golangsupport.testing.GoLineCoverage
import io.github.golangsupport.testing.GoSubtests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoTestingTest {
    @Test fun namesOfSubtests() {
        assertEquals("two_items", GoSubtests.subtestName("\"two items\""))
        assertNull(GoSubtests.subtestName("\"with %s\""))
        assertNull(GoSubtests.subtestName("\"esc\\n\""))
        assertEquals("raw_name", GoSubtests.subtestName("`raw name`"))
    }

    @Test fun coverageProfile() {
        val data = GoCoverage.parse(
            """
            mode: set
            example.com/app/store/order.go:10.20,12.2 1 1
            example.com/app/store/order.go:14.20,16.2 2 0
            example.com/app/store/order.go:16.2,18.2 1 1
            example.com/app/cmd/main.go:5.13,7.2 3 0
            """.trimIndent(),
        )
        assertEquals("set", data.mode)
        assertEquals(4, data.blocks.size)
        assertEquals(2.0 / 7 * 100, data.percent()!!, 0.001)
        assertEquals(50.0, data.percent { it.startsWith("example.com/app/store/") }!!, 0.001)
        assertNull(data.percent { it.startsWith("nothing/") })
        val lines = data.lines("example.com/app/store/order.go")
        assertEquals(GoLineCoverage.COVERED, lines[9])
        assertEquals(GoLineCoverage.UNCOVERED, lines[13])
        assertEquals(GoLineCoverage.PARTIAL, lines[15])
        assertEquals(GoLineCoverage.COVERED, lines[17])
        assertFalse(8 in lines)
        assertEquals("28.6%", GoCoverage.format(2.0 / 7 * 100))
    }

    @Test fun valuesOfDelveMadeReadable() {
        val bytes = GoValuePresentation.of("[]uint8", "[]uint8 len: 5, cap: 8, [104,101,108,108,111]")
        assertEquals("\"hello\"", bytes.value)
        assertEquals("[]byte len 5", bytes.type)
        val cut = GoValuePresentation.of("[]uint8", "[]uint8 len: 100, cap: 100, [104,105,...+98 more]")
        assertEquals("\"hi…\"", cut.value)
        assertEquals("[]uint8 len: 2, cap: 2, [0,255]", GoValuePresentation.of("[]uint8", "[]uint8 len: 2, cap: 2, [0,255]").value)
        assertEquals("\"boom\"", GoValuePresentation.of("error", "*errors.errorString {s: \"boom\"}").value)
        assertEquals("\"open x: no such file\"", GoValuePresentation.of("error(*fmt.wrapError)", "*{msg: \"open x: no such file\", err: error(*fs.PathError) *{...}}").value)
        assertEquals("*Order {items: []}", GoValuePresentation.of("*store.Order", "*Order {items: []}").value)
        assertTrue(GoValuePresentation.isCut("\"a long string...\"+13 more"))
        assertTrue(GoValuePresentation.isCut("[]int len: 100, cap: 100, [1,2,...+98 more]"))
        assertFalse(GoValuePresentation.isCut("\"short\""))
    }

    @Test fun benchmarkLines() {
        val result = GoBenchmarks.parseLine("BenchmarkTotal-12   \t 4899130\t       236.7 ns/op\t      48 B/op\t       1 allocs/op")!!
        assertEquals("BenchmarkTotal", result.name)
        assertEquals(12, result.procs)
        assertEquals(4899130L, result.iterations)
        assertEquals(236.7, result.nsPerOp!!, 0.001)
        assertEquals(48.0, result.bytesPerOp!!, 0.001)
        assertEquals(1.0, result.allocsPerOp!!, 0.001)
        val sub = GoBenchmarks.parseLine("BenchmarkParse/small-8  1000000  1052 ns/op  95.12 MB/s")!!
        assertEquals("BenchmarkParse/small", sub.name)
        assertEquals(95.12, sub.metrics["MB/s"]!!, 0.001)
        // `go test -json` sends the name with its tab in one event and the numbers in the next (seen live): a line is a line once it ends
        assertNull(GoBenchmarks.parseLine("BenchmarkTotal-12    \t"))
        assertNotNull(GoBenchmarks.parseLine("BenchmarkTotal-12    \t" + "904698324\t         1.386 ns/op\t       0 B/op\t       0 allocs/op"))
        assertNull(GoBenchmarks.parseLine("PASS"))
        assertNull(GoBenchmarks.parseLine("ok  \texample.com/p\t2.1s"))
        assertEquals(10.0, GoBenchmarks.delta(110.0, 100.0)!!, 0.001)
        assertNull(GoBenchmarks.delta(110.0, null))
        assertEquals("+10.0%", GoBenchmarks.formatDelta(10.0))
        assertEquals("1.05 µs/op", GoBenchmarks.format(1052.0, "ns/op"))
        assertEquals("48 B/op", GoBenchmarks.format(48.0, "B/op"))
    }

    @Test fun goroutineNames() {
        val running = GoGoroutineName.parse("[Go 1] main.main (Running)")
        assertEquals(1, running.id)
        assertEquals("main.main", running.function)
        assertEquals("Running", running.state)
        assertFalse(running.isRuntime)
        val parked = GoGoroutineName.parse("[Go 18] runtime.gopark")
        assertEquals("", parked.state)
        assertTrue(parked.isRuntime)
        assertEquals("Goroutine 5", GoGoroutineName.parse("Goroutine 5").function)
        val current = GoGoroutineName.parse("* [Go 1] store.(*Order).Total (Thread 39264)")
        assertEquals(1, current.id)
        assertEquals("store.(*Order).Total", current.function)
        assertEquals("Thread 39264", current.state)
        val summary = GoGoroutineName.summary(listOf(running, parked, GoGoroutineName.parse("[Go 19] runtime.gopark (Sleeping)")))
        assertEquals(listOf("runtime.gopark" to 2, "main.main" to 1), summary)
    }

    @Test fun declarationOfAHover() {
        assertEquals("func NewOrder(currency string) *Order", GoplsHover.declaration("```go\nfunc NewOrder(currency string) *Order\n```\n\nNewOrder makes an order."))
        assertEquals("var order *Order", GoplsHover.declaration("var order *Order"))
        assertNull(GoplsHover.declaration("```go\n```"))
    }
}
