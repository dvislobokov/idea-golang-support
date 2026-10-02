package io.github.golangsupport.ide.completion

import io.github.golangsupport.ide.completion.api.GoCompletionCandidate
import io.github.golangsupport.ide.completion.api.GoCompletionRanker
import io.github.golangsupport.ide.completion.api.GoCompletionRankingContext

/** Deterministic ranking: expected type first, then scope distance; the `GoCompletionRanker` extension point. */
class GoRankingCompletionTest : GoCompletionTestBase() {

    fun testArgumentOfExpectedTypeFirst() {
        val items = lookups("""
            package main

            func takeString(s string) {}

            func main() {
                aCount := 1
                aName := "x"
                takeString(a<caret>)
                _ = aCount
            }
        """)
        assertEquals("aName", items.first())
    }

    fun testReturnValueOfExpectedTypeFirst() {
        val items = lookups("""
            package main

            type Shape interface{ Area() float64 }
            type Square struct{}

            func (Square) Area() float64 { return 0 }

            func pick(bValue int, bShape Square, bText string) Shape {
                return b<caret>
            }
        """)
        assertEquals("bShape", items.first())
    }

    fun testAssignmentTargetTypeFirstIncludingFunctionResults() {
        val items = lookups("""
            package main

            func cName() string { return "" }
            func cCount() int { return 0 }

            func main() {
                var n int
                n = c<caret>
            }
        """)
        assertEquals("cCount", items.first())
    }

    fun testBinaryOperandAndStructFieldValue() {
        val items = lookups("""
            package main

            type Opt struct{ Size int; Label string }

            func main() {
                dSize := 1
                dLabel := ""
                _ = Opt{Label: d<caret>}
                _ = dSize
            }
        """)
        assertEquals("dLabel", items.first())
        val compare = lookups("""
            package main

            func main() {
                eSize := 1
                eLabel := ""
                if eLabel == e<caret> {
                }
                _ = eSize
            }
        """)
        assertEquals("eLabel", compare.first())
    }

    fun testSwitchCaseAndSendStatementExpectations() {
        val cases = lookups("""
            package main

            type Color int

            const (
                fRed Color = iota
                fGreen
            )

            func main() {
                fCount := 1
                var c Color
                switch c {
                case f<caret>:
                }
                _ = fCount
            }
        """)
        assertTrue("constants of the tag type first: $cases", cases.indexOf("fCount") > maxOf(cases.indexOf("fRed"), cases.indexOf("fGreen")))
        val sends = lookups("""
            package main

            func main() {
                gNum := 1
                gText := "x"
                ch := make(chan string)
                ch <- g<caret>
                _ = gNum
            }
        """)
        assertEquals("gText", sends.first())
    }

    fun testLocalsBeforeParametersBeforePackageLevelBeforeUniverse() {
        val items = lookups("""
            package main

            var cpkg = 1

            func run(cparam int) {
                clocal := 1
                _ = c<caret>
                _ = clocal
            }
        """)
        val order = items.filter { it in setOf("clocal", "cparam", "cpkg", "cap") }
        assertEquals(listOf("clocal", "cparam", "cpkg", "cap"), order)
    }

    fun testImportedBeforeUnimportedPackages() {
        val items = lookups("""
            package main

            import "strings"

            func main() {
                str<caret>
            }
        """)
        val order = items.filter { it == "strings" || it == "strconv" }
        assertEquals(listOf("strings", "strconv"), order)
    }

    fun testRegisteredRankerOrdersAfterExpectedType() {
        val ranker = object : GoCompletionRanker {
            override fun rank(context: GoCompletionRankingContext, candidates: List<GoCompletionCandidate>): List<Double> =
                candidates.map { if (it.lookupString == "zLast") 10.0 else 0.0 }
        }
        GoCompletionRanker.EP_NAME.point.registerExtension(ranker, testRootDisposable)
        val items = lookups("""
            package main

            func main() {
                aFirst := 1
                zLast := 2
                _ = <caret>
                _, _ = aFirst, zLast
            }
        """)
        assertEquals("zLast", items.first())
    }

    fun testRankerReceivesFeatures() {
        var seen: List<GoCompletionCandidate> = emptyList()
        var seenContext: GoCompletionRankingContext? = null
        val ranker = object : GoCompletionRanker {
            override fun rank(context: GoCompletionRankingContext, candidates: List<GoCompletionCandidate>): List<Double>? {
                seen = candidates
                seenContext = context
                return null
            }
        }
        GoCompletionRanker.EP_NAME.point.registerExtension(ranker, testRootDisposable)
        lookups("""
            package main

            func f(x int) {}

            func main() {
                kNum := 1
                kStr := ""
                f(k<caret>)
                _ = kStr
            }
        """)
        val num = seen.first { it.lookupString == "kNum" }
        assertEquals("LOCAL", num.kind)
        assertEquals(GoScopeLevel.LOCAL, num.scopeLevel)
        assertEquals(2, num.expectedTypeMatch)
        assertEquals(0, seen.first { it.lookupString == "kStr" }.expectedTypeMatch)
        assertEquals("EXPRESSION", seenContext!!.positionKind)
        assertEquals("int", seenContext?.expectedType)
    }
}
