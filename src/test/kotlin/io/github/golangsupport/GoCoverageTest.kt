package io.github.golangsupport

import io.github.golangsupport.testing.GoCoverProfile
import io.github.golangsupport.testing.GoCoverageFormat
import io.github.golangsupport.testing.GoFileCoverage
import io.github.golangsupport.testing.LineCoverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The cover profile as `go test -coverprofile -covermode=count` writes it (Go 1.24). */
class GoCoverageTest {
    private val profile = """
        mode: count
        example.com/playground/store/order.go:38.29,40.32 2 5
        example.com/playground/store/order.go:40.32,42.3 1 10
        example.com/playground/store/order.go:43.2,43.14 1 5
        example.com/playground/store/order.go:46.31,47.21 1 0
        example.com/playground/store/order.go:47.21,49.3 1 0
        example.com/playground/store/order.go:50.2,50.12 1 3
        example.com/playground/store/order.go:38.29,40.32 2 2
        example.com/playground/cmd/shop/main.go:13.13,15.2 2 0
    """.trimIndent()

    @Test fun parse() {
        val parsed = GoCoverProfile.parse(profile)
        assertEquals("count", parsed.mode)
        assertEquals(8, parsed.blocks.size)
        val order = parsed.byFile.getValue("example.com/playground/store/order.go")
        // the same block of two packages is one block with the counts added
        assertEquals(6, order.size)
        assertEquals(7L, order.first { it.startLine == 38 }.count)
    }

    @Test fun lines() {
        // lines 1-based: 42 is the `}` of the loop, 49 of the if
        val text = List(60) { "	code()" }.toMutableList().apply { this[41] = "	}"; this[48] = "	}" }
        val order = GoFileCoverage.of(GoCoverProfile.parse(profile).byFile.getValue("example.com/playground/store/order.go"), text)
        assertEquals(LineCoverage.COVERED, order.lines[38])
        assertEquals(LineCoverage.COVERED, order.lines[41])
        // `}` of a block alone on its line is not code
        assertNull(order.lines[42])
        assertEquals(LineCoverage.UNCOVERED, order.lines[46])
        // line 47 ends one block that did not run and starts another that did not either
        assertEquals(LineCoverage.UNCOVERED, order.lines[47])
        assertEquals(LineCoverage.COVERED, order.lines[50])
        assertEquals(10L, order.hits[41])
        assertEquals(7, order.statements)
        assertEquals(5, order.coveredStatements)
        assertEquals("71.4%", GoCoverageFormat.percent(order.percent))
        val partial = GoFileCoverage.of(GoCoverProfile.parse("mode: set\na/b.go:5.10,5.20 1 1\na/b.go:5.22,5.40 1 0\n").byFile.getValue("a/b.go"))
        assertEquals(LineCoverage.PARTIAL, partial.lines[5])
    }

    @Test fun pathsAndModes() {
        val modules = mapOf("example.com/playground" to "C:/p", "example.com/playground/tools" to "C:/p/tools")
        assertEquals("C:/p/store/order.go", GoCoverageFormat.localPath("example.com/playground/store/order.go", modules))
        assertEquals("C:/p/tools/gen/main.go", GoCoverageFormat.localPath("example.com/playground/tools/gen/main.go", modules))
        assertNull(GoCoverageFormat.localPath("github.com/other/x.go", modules))
        assertNull(GoCoverageFormat.localPath("example.com/playgroundx/a.go", modules))
        assertEquals(listOf("-covermode=count"), GoCoverageFormat.modeArguments(listOf("-count=1")))
        assertEquals(listOf("-covermode=atomic"), GoCoverageFormat.modeArguments(listOf("-race")))
        assertEquals(emptyList<String>(), GoCoverageFormat.modeArguments(listOf("-covermode=set")))
        assertEquals("100%", GoCoverageFormat.percent(100.0))
        assertEquals("0%", GoCoverageFormat.percent(0.0))
    }
}
