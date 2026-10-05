package io.github.golangsupport

import io.github.golangsupport.build.GoOptimizationDecision
import io.github.golangsupport.build.GoOptimizationKind.BOUNDS
import io.github.golangsupport.build.GoOptimizationKind.ESCAPE
import io.github.golangsupport.build.GoOptimizationKind.INLINING
import io.github.golangsupport.build.GoOptimizationOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The diagnostics of `go build -gcflags='-m=2 -d=ssa/check_bce/debug=1'` as the Go Optimization window groups them. */
class GoOptimizationOutputTest {
    private val sample = """
        # example.com/app/internal/store
        internal/store/store.go:12:6: can inline (*Store).Len with cost 4 as: method(s *Store) func() int { return len(s.items) }
        internal/store/store.go:20:6: cannot inline (*Store).Load: function too complex: cost 120 exceeds budget 80
        internal/store/store.go:20:7: leaking param: s
        internal/store/store.go:21:10: make([]string, 0, n) escapes to heap:
        internal/store/store.go:21:10:   flow: {heap} = &{storage for make([]string, 0, n)}:
        internal/store/store.go:21:10:     from make([]string, 0, n) (non-constant size) at internal/store/store.go:21:10
        internal/store/store.go:25:3: moved to heap: buf
        internal/store/store.go:30:14: Found IsInBounds
        internal/store/store.go:31:9: Found IsSliceInBounds
        # example.com/app
        .\main.go:9:13: inlining call to fmt.Println
        .\main.go:9:13: inlining call to fmt.Println
        ./main.go:8:2: s does not escape
        ./main.go:11:1: declared and not used: x
    """.trimIndent()

    @Test
    fun decisionsAreGroupedByKindAndResolvedAgainstTheDirectoryOfTheCommand() {
        val store = "C:/work/app/internal/store/store.go"
        val main = "C:/work/app/main.go"
        assertEquals(
            listOf(
                GoOptimizationDecision(store, 12, 6, INLINING, "can inline (*Store).Len with cost 4"),
                GoOptimizationDecision(store, 20, 6, INLINING, "cannot inline (*Store).Load: function too complex: cost 120 exceeds budget 80"),
                GoOptimizationDecision(store, 20, 7, ESCAPE, "leaking param: s"),
                GoOptimizationDecision(store, 21, 10, ESCAPE, "make([]string, 0, n) escapes to heap"),
                GoOptimizationDecision(store, 25, 3, ESCAPE, "moved to heap: buf"),
                GoOptimizationDecision(store, 30, 14, BOUNDS, "Found IsInBounds"),
                GoOptimizationDecision(store, 31, 9, BOUNDS, "Found IsSliceInBounds"),
                GoOptimizationDecision(main, 9, 13, INLINING, "inlining call to fmt.Println"),
                GoOptimizationDecision(main, 8, 2, ESCAPE, "s does not escape"),
            ),
            GoOptimizationOutput.parse(sample, "C:/work/app"),
        )
    }

    @Test
    fun absolutePathsOfWindowsAndUnixAreKept() {
        assertEquals("C:/other/x.go", GoOptimizationOutput.parse("C:\\other\\x.go:3:2: moved to heap: v", "C:/work").single().file)
        assertEquals(3, GoOptimizationOutput.parse("C:\\other\\x.go:3:2: moved to heap: v", "C:/work").single().line)
        assertEquals("C:/work/x.go", GoOptimizationOutput.resolve("./sub/../x.go", "C:/work"))
    }

    @Test
    fun otherMessagesAreNotDecisions() {
        assertNull(GoOptimizationOutput.kindOf("declared and not used: x"))
        assertNull(GoOptimizationOutput.kindOf("undefined: foo"))
        assertEquals(ESCAPE, GoOptimizationOutput.kindOf("parameter p leaks to {heap} with derefs=0"))
    }

    @Test
    fun theBoundsChecksAreAskedForOnlyWhenWanted() {
        assertEquals(listOf("build", "-tags=a", "-gcflags=-m=2", "./..."), GoOptimizationOutput.arguments(listOf("-tags=a"), boundsChecks = false))
        assertEquals(listOf("build", "-gcflags=-m=2 -d=ssa/check_bce/debug=1", "./..."), GoOptimizationOutput.arguments(emptyList(), boundsChecks = true))
    }

    @Test
    fun errorsAreTheLinesThatAreNotDecisions() {
        val output = """
            # example.com/playground/internal/money
            internal\money\money.go:6:2: "net/url" imported and not used
            internal\money\money.go:31:2: declared and not used: tables
            cmd\shop\main.go:20:14: ... argument does not escape
            cmd\shop\main.go:20:15: "arguments:" escapes to heap
            cmd\shop\main.go:5:6: can inline run with cost 3 as: func() { x := 1 }
            cmd\shop\main.go:5:6:   flow: ~r0 = &x:
            too many errors
        """.trimIndent()
        assertEquals(
            listOf(
                """internal\money\money.go:6:2: "net/url" imported and not used""",
                """internal\money\money.go:31:2: declared and not used: tables""",
                "too many errors",
            ),
            GoOptimizationOutput.errors(output),
        )
    }
}
