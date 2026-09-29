package io.github.golangsupport

import io.github.golangsupport.debugger.GoValuePresentation
import io.github.golangsupport.lang.GoDeclarations
import io.github.golangsupport.lsp.GoplsHover
import io.github.golangsupport.testing.GoCoverage
import io.github.golangsupport.testing.GoLineCoverage
import io.github.golangsupport.testing.GoSubtests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoTestingTest {
    @Test fun subtestsOfATableAndOfRun() {
        val text = """
            package store

            func TestTotal(t *testing.T) {
                tests := []struct {
                    name  string
                    items []Item
                    want  int
                }{
                    {name: "empty", want: 0},
                    {name: "two items", items: []Item{{Price: 1}, {Price: 2}}, want: 3},
                    {"positional", nil, 0},
                }
                for _, tt := range tests {
                    t.Run(tt.name, func(t *testing.T) {})
                }
                t.Run("explicit", func(t *testing.T) {
                    t.Run("nested", func(t *testing.T) {})
                })
                t.Run(fmt.Sprintf("%d", 1), nil)
            }
            """.trimIndent()
        val function = GoDeclarations.scan(text).declarations.single { it.name == "TestTotal" }
        val subtests = GoSubtests.find(text, function)
        assertEquals(listOf("empty", "two_items", "explicit", "nested"), subtests.map { it.name })
        assertEquals("TestTotal/two_items", subtests[1].fullName)
        assertEquals("\"two items\"", text.substring(subtests[1].nameRange.startOffset, subtests[1].nameRange.endOffset))
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

    @Test fun declarationOfAHover() {
        assertEquals("func NewOrder(currency string) *Order", GoplsHover.declaration("```go\nfunc NewOrder(currency string) *Order\n```\n\nNewOrder makes an order."))
        assertEquals("var order *Order", GoplsHover.declaration("var order *Order"))
        assertNull(GoplsHover.declaration("```go\n```"))
    }
}
