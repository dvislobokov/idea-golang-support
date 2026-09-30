package io.github.golangsupport

import io.github.golangsupport.lang.GoKeywordTemplates
import io.github.golangsupport.lang.GoKeywordTemplates.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a keyword can begin, by the place of the caret; `|` marks the caret. */
class GoKeywordTemplatesTest {
    private fun context(source: String, test: Boolean = false): GoKeywordTemplates.Context? {
        val offset = source.indexOf('|')
        require(offset >= 0)
        return GoKeywordTemplates.contextAt(source.replace("|", ""), offset, test)
    }

    private fun labels(source: String, test: Boolean = false): List<String> = GoKeywordTemplates.items(context(source, test) ?: error("no context")).map { it.label }

    @Test
    fun theTopOfAFileOffersDeclarations() {
        val labels = labels("package main\n\nimport \"fmt\"\n\nty|\n\nfunc main() {}\n")
        assertTrue(labels.toString(), "type Name struct {...}" in labels && "type Name interface {...}" in labels && "const (... = iota)" in labels && "import (...)" in labels)
        assertFalse("main is there already", "func main() {...}" in labels)
        assertFalse("not a test file", labels.any { it.startsWith("func Test") })
        assertFalse("statements are not declarations", "if err != nil {...}" in labels)
    }

    @Test
    fun aMainPackageWithoutMainOffersIt() {
        assertTrue("func main() {...}" in labels("package main\n\nfu|\n"))
        assertFalse("func main() {...}" in labels("package store\n\nfu|\n"))
    }

    @Test
    fun aMethodIsOfferedForEveryTypeOfTheFile() {
        val labels = labels("package store\n\ntype Order struct{ id int }\n\ntype Priced interface{ Total() int }\n\nfunc (o Order) Total() int { return 0 }\n\nfu|\n")
        // a struct without methods gets a pointer receiver; the receiver of the type that has methods is the one they use
        assertTrue(labels.toString(), "func (o Order) name() {...}" in labels && "func (p Priced) name() {...}" in labels)
        assertTrue("func (s *Server) name() {...}" in labels("package a\n\ntype Server struct{}\n\nfu|\n"))
        assertTrue("func (s *Server) name() {...}" in labels("package a\n\ntype Server struct{}\n\nfunc (s *Server) Run() {}\n\nfu|\n"))
    }

    @Test
    fun theMissingMethodsOfAnInterfaceATypeHasBegunToImplement() {
        val context = context("package store\n\ntype Order struct{}\n\nfunc (o *Order) Total() int { return 0 }\n\ntype Note struct{}\n\nfu|\n")!!
        assertEquals(mapOf("Order" to setOf("Total")), context.methods)
        val priced = GoKeywordTemplates.InterfaceInfo("Priced", listOf("Total" to "() int", "Currency" to "() string"))
        val named = GoKeywordTemplates.InterfaceInfo("Named", listOf("Name" to "() string"))
        val items = GoKeywordTemplates.interfaceItems(context, listOf(priced, named))
        assertEquals(listOf("func (o *Order) Currency() string {...}"), items.map { it.label })
        assertEquals("missing method of Priced", items.single().typeText)
        assertTrue(items.single().template, items.single().template.startsWith("func (o *Order) Currency() string {\n"))
        // Note has no method of any interface, and a body is not the place
        assertTrue(GoKeywordTemplates.interfaceItems(context("package a\n\nfunc f() {\n\tfu|\n}\n")!!, listOf(priced)).isEmpty())
        assertTrue("the JSON item is a type item", GoKeywordTemplates.items(context).any { it.action == GoKeywordTemplates.JSON && it.keyword == "type" })
    }

    @Test
    fun aTestFileOffersTests() {
        val labels = labels("package store\n\nfu|\n", test = true)
        assertTrue(labels.toString(), "func TestName(t *testing.T) {...}" in labels && "func BenchmarkName(b *testing.B) {...}" in labels && "func FuzzName(f *testing.F) {...}" in labels)
        val table = GoKeywordTemplates.items(context("package store\n\nfu|\n", test = true)!!).first { it.label.contains("table") }
        assertTrue(table.template, table.template.contains("t.Run(tt.name") && "test" in table.lookups)
    }

    @Test
    fun aBodyOffersStatementsForTheNamesInSight() {
        val source = "package a\n\nfunc handle(ctx context.Context, items []Item, byID map[int]Item) error {\n\tdone := make(chan struct{})\n\tvar names []string\n\tfo|\n}\n"
        val context = context(source)!!
        assertEquals(Place.BODY, context.place)
        assertEquals(listOf("names", "items"), context.slices)
        assertEquals(listOf("byID"), context.maps)
        assertEquals(listOf("done"), context.channels)
        assertTrue(context.hasContext)
        val labels = labels(source)
        assertTrue(labels.toString(), "for i, name := range names {...}" in labels && "for i, item := range items {...}" in labels && "for k, v := range byID {...}" in labels && "for v := range done {...}" in labels)
        assertTrue("for { select { case <-ctx.Done(): ... } }" in labels && "select { case <-ctx.Done(): ... }" in labels)
        assertFalse("declarations are not statements", "type Name struct {...}" in labels)
        assertFalse("not a test", labels.any { it.startsWith("t.Run") })
    }

    @Test
    fun aTestBodyOffersTheMethodsOfT() {
        val source = "package a\n\nfunc TestTotal(t *testing.T) {\n\tt.|\n}\n"
        val labels = labels(source, test = true)
        assertTrue(labels.toString(), labels.any { it.startsWith("t.Run(") } && "t.Parallel()" in labels)
        assertEquals("t.", GoKeywordTemplates.typed(source.replace("|", ""), source.indexOf('|')))
        assertEquals("t.Ru", GoKeywordTemplates.typed("\tt.Ru", 5))
        assertEquals("Ru", GoKeywordTemplates.typed("\tst.Ru", 6))
    }

    @Test
    fun nothingWhereAKeywordCannotBegin() {
        assertNull("in the middle of a line", context("package a\n\nvar x = ty|\n"))
        assertNull("in a struct body", context("package a\n\ntype T struct {\n\tfo|\n}\n"))
        assertNull("in a signature", context("package a\n\nfunc f(fo|) {}\n"))
        assertNull("in a var initializer", context("package a\n\nvar f = func() {\n\tfo|\n}\n"))
        assertNotNull("indented at the top level is still the top level", context("package a\n\n  ty|\n"))
    }

    @Test
    fun theStopsOfATemplateAreInTheOrderOfTheText() {
        assertEquals(listOf("I", "N"), GoKeywordTemplates.stops("for \$I\$ := 0; \$I\$ < \$N\$; \$I\$++ {\n\t\$END\$\n}"))
        for (item in GoKeywordTemplates.items(context("package a\n\nfunc f(xs []int) {\n\tfo|\n}\n")!!)) {
            assertTrue(item.label, item.template.contains("\$END\$"))
        }
    }

    @Test
    fun theElementOfASlice() {
        assertEquals("item", GoKeywordTemplates.element("items"))
        assertEquals("entry", GoKeywordTemplates.element("entries"))
        assertEquals("box", GoKeywordTemplates.element("boxes"))
        assertEquals("v", GoKeywordTemplates.element("data"))
        assertEquals("x", GoKeywordTemplates.element("xs"))
        assertEquals("v", GoKeywordTemplates.element("class"))
    }
}
