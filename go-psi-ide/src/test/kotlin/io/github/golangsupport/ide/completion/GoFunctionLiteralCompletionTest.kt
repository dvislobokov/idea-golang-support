package io.github.golangsupport.ide.completion

import com.intellij.codeInsight.completion.PrioritizedLookupElement

/** The `func(...) ... {}` literal in basic completion wherever a function is expected, and `func() {}()` after `go` / `defer` (as GoLand). */
class GoFunctionLiteralCompletionTest : GoCompletionTestBase() {

    private val header = """
        package main

        type Handler func(name string) error

        type Server struct {
            OnStart Handler
        }

        func Register(path string, h Handler) {}

        func compare(a, b int) bool { return a < b }

    """

    fun testArgumentOfFunctionTypeOffersTheLiteralFirst() {
        val items = complete(header + """
            func main() {
                Register("/", <caret>)
            }
        """)
        assertNotNull(items)
        assertEquals("func(name string) error {}", myFixture.lookupElementStrings!!.first())
    }

    fun testPrefixFuMatchesTheLiteralAndInsertsItWithTheCaretInTheBody() {
        complete(header + """
            func main() {
                Register("/", fu<caret>)
            }
        """)
        select("func(name string) error {}")
        myFixture.checkResult(go(header + """
            func main() {
                Register("/", func(name string) error {<caret>})
            }
        """))
    }

    fun testReturnAssignmentAndFieldValueOfFunctionType() {
        assertContainsAll(complete(header + "func pick() func(a int, b int) bool {\n    return <caret>\n}\n")!!.map { it.lookupString }, "func(a int, b int) bool {}")
        assertContainsAll(complete(header + "func main() {\n    var h Handler = <caret>\n    _ = h\n}\n")!!.map { it.lookupString }, "func(name string) error {}")
        assertContainsAll(complete(header + "func main() {\n    s := Server{OnStart: <caret>}\n    _ = s\n}\n")!!.map { it.lookupString }, "func(name string) error {}")
    }

    fun testGoAndDeferOfferTheCalledLiteral() {
        complete(header + "func main() {\n    defer fu<caret>\n}\n")
        select("func() {}()")
        myFixture.checkResult(go(header + "func main() {\n    defer func() {<caret>}()\n}\n"))
        assertContainsAll(complete(header + "func main() {\n    go <caret>\n}\n")!!.map { it.lookupString }, "func() {}()")
    }

    /** The host's catalogue gives 2.0 to a name beginning with the prefix; a fitting item of ours must carry more (seen live). */
    fun testFittingItemsCarryAPriorityAboveABareNameMatch() {
        val items = complete(header + """
            func main() {
                fuel := 1
                Register("/", fu<caret>)
                _ = fuel
            }
        """)!!
        val priority = { s: String -> items.first { it.lookupString == s }.`as`(PrioritizedLookupElement.CLASS_CONDITION_KEY)!!.priority }
        assertEquals(GoLookupPriority.STARTS_WITH + GoLookupPriority.IDENTICAL_TYPE, priority("func(name string) error {}"))
        assertEquals(GoLookupPriority.STARTS_WITH, priority("fuel"))
    }

    /** The signature of `http.HandleFunc` names no parameters: the literal names them after the types (seen live: `p0`, `p1` before; `w`, `r` are the idiomatic names of the table). */
    fun testUnnamedParametersOfAStdlibSignatureAreNamedAfterTheirTypes() {
        val items = complete("""
            package main

            import "net/http"

            func main() {
                http.HandleFunc("/", fu<caret>)
            }
        """)!!.map { it.lookupString }
        assertEquals("func(w http.ResponseWriter, r *http.Request) {}", items.first())
    }

    fun testNoLiteralWhereNoFunctionIsExpected() {
        val items = complete(header + "func main() {\n    var n int = <caret>\n    _ = n\n}\n")!!.map { it.lookupString }
        assertTrue(items.none { it.startsWith("func(") })
    }
}
