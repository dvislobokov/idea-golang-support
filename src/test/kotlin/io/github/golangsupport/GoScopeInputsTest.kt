package io.github.golangsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.lang.GoKeywordTemplates
import io.github.golangsupport.lang.GoScopeInputs
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.settings.GoSettings

/** The keyword templates on the PSI path ([GoScopeInputs]): what is in scope by type, not by a regular expression over the lines above. */
class GoScopeInputsTest : BasePlatformTestCase() {
    private var languageServer = true

    // a file opened in an editor starts gopls, where it is installed: not in a test
    override fun setUp() {
        super.setUp()
        languageServer = GoSettings.getInstance().languageServerEnabled
        GoSettings.getInstance().languageServerEnabled = false
    }

    override fun tearDown() {
        try {
            GoSettings.getInstance().languageServerEnabled = languageServer
        } finally {
            super.tearDown()
        }
    }

    private fun context(name: String, source: String): GoKeywordTemplates.Context? {
        val file = myFixture.configureByText(name, source) as GoFile
        return GoKeywordTemplates.contextAt(file, myFixture.editor.document.immutableCharSequence, myFixture.caretOffset)
    }

    private fun items(name: String, source: String): List<GoKeywordTemplates.Item> = GoKeywordTemplates.items(context(name, source) ?: error("no context"))

    fun testSlicesMapsChannelsAndTheContextByTheirTypes() {
        val context = context("a.go", """
            package a

            import "context"

            type Item struct{}

            func handle(ctx context.Context, items []Item, byID map[int]Item) error {
            	done := make(chan struct{})
            	var names []string
            	fo<caret>
            }
        """.trimIndent())!!
        assertNotNull("the PSI path", context.scope)
        assertEquals(listOf("names", "items"), context.slices)
        assertEquals(listOf("byID"), context.maps)
        assertEquals(listOf("done"), context.channels)
        assertTrue(context.hasContext)
    }

    /** The regex took any `ctx` in sight for a context; the PSI knows its type. */
    fun testAVariableNamedCtxOfAnotherTypeIsNoContext() {
        val labels = items("b.go", "package a\n\nfunc run(ctx int, n []int) {\n\tse<caret>\n}\n").map { it.label }
        assertFalse(labels.toString(), labels.any { "ctx.Done" in it })
        assertTrue("nothing to select on: the plain select stays", "select {...}" in labels)
        val local = context("c.go", "package a\n\nfunc run() {\n\tctx := 5\n\t_ = ctx\n\tfo<caret>\n}\n")!!
        assertFalse(local.hasContext)
        assertTrue("a slice by its named type", context("d.go", "package a\n\ntype Items []int\n\nfunc run(xs Items) {\n\tfo<caret>\n}\n")!!.slices == listOf("xs"))
    }

    /** A block hides the names of the function: `items` that is an int here is not ranged over. */
    fun testAnInnerDeclarationHidesAnOuterOne() {
        val context = context("e.go", "package a\n\nfunc run(items []int) {\n\tif true {\n\t\titems := 3\n\t\t_ = items\n\t\tfo<caret>\n\t}\n}\n")!!
        assertEquals(emptyList<String>(), context.slices)
    }

    fun testTheMethodsOfTOnlyInATestFunction() {
        val test = items("x_test.go", "package a\n\nimport \"testing\"\n\nfunc TestTotal(t *testing.T) {\n\tt.<caret>\n}\n").map { it.label }
        assertTrue(test.toString(), test.any { it.startsWith("t.Run(") } && "t.Parallel()" in test)
        val helper = items("y_test.go", "package a\n\nfunc helper(t int) {\n\tfo<caret>\n}\n").map { it.label }
        assertFalse("`t` is an int here", helper.any { it.startsWith("t.Run(") })
        val nested = items("z_test.go", "package a\n\nimport \"testing\"\n\nfunc FuzzX(f *testing.F) {\n\tf.Fuzz(func(t *testing.T, s string) {\n\t\tt.<caret>\n\t})\n}\n").map { it.label }
        assertTrue("the `t` of a function literal: $nested", "t.Parallel()" in nested)
    }

    fun testSelectWaitsOnTheContextTheChannelsAndTheTimersInScopeInThatOrder() {
        val items = items("s.go", """
            package a

            import (
            	"context"
            	"time"
            )

            func pump(ctx context.Context, in <-chan int, out chan<- int, ticker *time.Ticker) error {
            	se<caret>
            }
        """.trimIndent())
        val select = items.single { it.keyword == "select" && it.typeText == "cases in scope" }
        assertEquals("select {\ncase <-ctx.Done():\n\treturn \$RESULT\$\ncase v := <-in:\n\t\$END\$\ncase <-ticker.C:\ncase out <- \$VALUE\$:\n}", select.template)
        assertEquals(listOf("RESULT", "VALUE"), GoKeywordTemplates.stops(select.template))
        assertEquals("ctx.Err()", select.stops.toMap()["RESULT"])
        assertFalse("the plain select gives way", items.any { it.label == "select {...}" })
        val loop = items.single { it.typeText == "select loop" }
        assertEquals("for", loop.keyword)
        assertTrue(loop.template, loop.template.startsWith("for {\n\tselect {\n\tcase <-ctx.Done():\n\t\treturn \$RESULT\$\n\tcase v := <-in:\n\t\t\$END\$\n"))
        assertTrue("a send-only channel is not ranged over", items.none { it.label.contains("range out") })
    }

    fun testTheContextUnderItsOwnName() {
        val items = items("n.go", "package a\n\nimport \"context\"\n\nfunc run(c context.Context) {\n\tse<caret>\n}\n")
        val select = items.single { it.typeText == "cases in scope" }
        assertTrue(select.template, select.template.startsWith("select {\ncase <-c.Done():\n\treturn \$RESULT\$\n\$END\$\n}"))
        assertEquals("c.Err()", select.stops.toMap()["RESULT"])
    }

    fun testAChannelInScopeGivesARangeAReceiveAndAClose() {
        val labels = items("ch.go", "package a\n\nfunc produce() {\n\tch := make(chan int)\n\tfo<caret>\n}\n").map { it.label }
        assertTrue(labels.toString(), "for v := range ch {...}" in labels && "v, ok := <-ch" in labels && "close(ch)" in labels && "defer close(ch)" in labels)
        assertTrue("the make items", "ch := make(chan T, n)" in labels && "s := make([]T, 0, n)" in labels)
        val closed = items("cl.go", "package a\n\nfunc produce() {\n\tch := make(chan int)\n\tfo<caret>\n\tclose(ch)\n}\n").map { it.label }
        assertTrue("v, ok := <-ch" in closed)
        assertFalse("closed already: $closed", "close(ch)" in closed)
        val given = items("gv.go", "package a\n\nfunc consume(ch chan int) {\n\tfo<caret>\n}\n").map { it.label }
        assertTrue("v, ok := <-ch" in given)
        assertFalse("not made here: $given", "close(ch)" in given)
        val number = items("int.go", "package a\n\nfunc run() {\n\tch := 3\n\t_ = ch\n\tfo<caret>\n}\n").map { it.label }
        assertFalse(number.toString(), number.any { "ch" in it && ("<-" in it || "close" in it || "range ch" in it) })
    }

    /** The methods of the types of the file from the semantic layer, the interfaces of the project with their signatures. */
    fun testTheMissingMethodsOfAnInterfaceFromTheSemanticLayer() {
        myFixture.addFileToProject("store/priced.go", "package store\n\ntype Priced interface {\n\tTotal() int\n\tCurrency(code string) string\n}\n")
        val file = myFixture.configureByText("order.go", "package store\n\ntype Order struct{}\n\nfunc (o *Order) Total() int { return 0 }\n\nfu<caret>\n") as GoFile
        val context = GoKeywordTemplates.contextAt(file, myFixture.editor.document.immutableCharSequence, myFixture.caretOffset)!!
        assertEquals(setOf("Total"), context.methods["Order"])
        val interfaces = GoScopeInputs.interfaces(file, context.methods)!!
        val items = GoKeywordTemplates.interfaceItems(context, interfaces)
        assertEquals(listOf("func (o *Order) Currency(code string) string {...}"), items.map { it.label })
    }

    fun testOutsideAFunctionThePsiHasNoScope() {
        val file = myFixture.configureByText("t.go", "package a\n\nvar x = 1\n<caret>\n") as GoFile
        assertNull(GoScopeInputs.at(file, myFixture.caretOffset))
    }
}
