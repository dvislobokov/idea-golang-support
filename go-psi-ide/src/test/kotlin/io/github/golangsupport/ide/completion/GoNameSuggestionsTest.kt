package io.github.golangsupport.ide.completion

import io.github.golangsupport.ide.completion.GoNameSuggestions.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The name rules of [GoNameSuggestions] (PLAN.md G3 "parameter names by type", level 3 "variable name hints"). */
class GoNameSuggestionsTest {

    @Test
    fun parameterNamesAsGoLand() {
        assertEquals("err", GoNameSuggestions.parameterName("error"))
        assertEquals("string2", GoNameSuggestions.parameterName("string"))
        assertEquals("int2", GoNameSuggestions.parameterName("int"))
        assertEquals("base", GoNameSuggestions.parameterName("Base"))
        assertEquals("ctx", GoNameSuggestions.parameterName("context.Context"))
        assertEquals("t", GoNameSuggestions.parameterName("time.Time"))
        assertEquals("t", GoNameSuggestions.parameterName("*testing.T"))
        assertEquals("httpClient", GoNameSuggestions.parameterName("HTTPClient"))
        assertEquals("set", GoNameSuggestions.parameterName("Set[K]"))
    }

    @Test
    fun takenParameterNameGetsADigit() {
        assertEquals("base2", GoNameSuggestions.parameterName("Base") { it == "base" })
        assertEquals("base3", GoNameSuggestions.parameterName("Base") { it == "base" || it == "base2" })
        assertEquals("type2", GoNameSuggestions.parameterName("Type"))
    }

    @Test
    fun namesForTypes() {
        assertEquals(listOf("base", "b"), GoNameSuggestions.namesForType("Base"))
        assertEquals(listOf("f", "file"), GoNameSuggestions.namesForType("*os.File"))
        assertEquals(listOf("httpClient", "client", "h"), GoNameSuggestions.namesForType("HTTPClient"))
        assertEquals(listOf("responseWriter", "writer", "r"), GoNameSuggestions.namesForType("api.ResponseWriter"))
        assertEquals(listOf("shapes"), GoNameSuggestions.namesForType("[]Shape"))
        assertEquals(listOf("entries"), GoNameSuggestions.namesForType("[]*Entry"))
        assertEquals(listOf("data", "buf", "b"), GoNameSuggestions.namesForType("[]byte"))
        assertEquals(listOf("i", "n"), GoNameSuggestions.namesForType("int"))
        assertEquals(listOf("s", "str"), GoNameSuggestions.namesForType("string"))
        assertEquals(listOf("err"), GoNameSuggestions.namesForType("error"))
        assertEquals(listOf("m", "users"), GoNameSuggestions.namesForType("map[string]*User"))
        assertEquals(listOf("ch"), GoNameSuggestions.namesForType("chan int"))
        assertEquals(listOf("fn", "f"), GoNameSuggestions.namesForType("func(int) bool"))
        assertEquals(emptyList<String>(), GoNameSuggestions.namesForType("?"))
    }

    @Test
    fun namesForExpressions() {
        assertEquals(listOf("area"), GoNameSuggestions.namesForExpression("Area", Source.CALL))
        assertEquals(listOf("name"), GoNameSuggestions.namesForExpression("GetName", Source.CALL))
        assertEquals(listOf("server"), GoNameSuggestions.namesForExpression("NewServer", Source.CALL))
        assertEquals(listOf("config"), GoNameSuggestions.namesForExpression("parseConfig", Source.CALL))
        assertEquals(emptyList<String>(), GoNameSuggestions.namesForExpression("Open", Source.CALL))
        assertEquals(listOf("n"), GoNameSuggestions.namesForExpression("len", Source.CALL))
        assertEquals(listOf("typ"), GoNameSuggestions.namesForExpression("Type", Source.CALL))
        assertEquals(listOf("name"), GoNameSuggestions.namesForExpression("Name", Source.VALUE))
        assertEquals(listOf("name"), GoNameSuggestions.namesForExpression("names", Source.ELEMENT))
        assertEquals(emptyList<String>(), GoNameSuggestions.namesForExpression("status", Source.ELEMENT))
    }

    @Test
    fun singularAndPlural() {
        assertEquals("name", GoNameSuggestions.singular("names"))
        assertEquals("entry", GoNameSuggestions.singular("entries"))
        assertEquals("box", GoNameSuggestions.singular("boxes"))
        assertEquals("match", GoNameSuggestions.singular("matches"))
        assertEquals("user", GoNameSuggestions.singular("userList"))
        assertEquals("child", GoNameSuggestions.singular("children"))
        assertNull(GoNameSuggestions.singular("class"))
        assertNull(GoNameSuggestions.singular("data"))
        assertEquals("shapes", GoNameSuggestions.plural("shape"))
        assertEquals("entries", GoNameSuggestions.plural("entry"))
        assertEquals("keys", GoNameSuggestions.plural("key"))
        assertEquals("boxes", GoNameSuggestions.plural("box"))
    }

    @Test
    fun wordsAndDecapitalize() {
        assertEquals(listOf("HTTP", "Client"), GoNameSuggestions.words("HTTPClient"))
        assertEquals(listOf("response", "Writer"), GoNameSuggestions.words("responseWriter"))
        assertEquals("url", GoNameSuggestions.decapitalize("URL"))
        assertEquals("id", GoNameSuggestions.decapitalize("ID"))
        assertEquals("httpClient", GoNameSuggestions.decapitalize("HTTPClient"))
        assertEquals("base", GoNameSuggestions.decapitalize("Base"))
    }

    @Test
    fun uniqueNames() {
        assertEquals(listOf("j", "n"), GoNameSuggestions.uniqueAll(listOf("i", "n")) { it == "i" })
        assertEquals(listOf("name", "name2"), GoNameSuggestions.uniqueAll(listOf("name", "name")) { false })
        assertEquals(listOf("s2"), GoNameSuggestions.uniqueAll(listOf("s")) { it == "s" })
    }
}

/** Completion of names being declared ([GoNameCompletion]). */
class GoNameCompletionTest : GoCompletionTestBase() {

    private val decls = """
        package main

        import "context"

        type Base struct{}

        type Circle struct{}
    """.trimIndent()

    // --- parameters ---

    fun testParameterNamesByType() {
        val items = lookups("$decls\n\nfunc g(<caret>) {}")
        assertContainsAll(items, "err error", "string2 string", "base Base", "circle Circle", "context")
        assertContainsNone(items, "error", "string", "Base", "func", "ctx context.Context")
    }

    fun testParameterAfterAnotherTakesTheNextDigit() {
        val items = lookups("$decls\n\nfunc g(base Base, <caret>) {}")
        assertContainsAll(items, "base2 Base", "circle Circle")
    }

    fun testParameterOfAMethodAndALiteral() {
        assertContainsAll(lookups("$decls\n\nfunc (b Base) m(<caret>) {}"), "circle Circle")
        assertContainsAll(lookups("$decls\n\nvar f = func(<caret>) {}"), "circle Circle")
    }

    fun testImportedTypeWithQualifierAfterAPrefix() {
        // The only match is inserted directly.
        checkInsert("$decls\n\nfunc g(ct<caret>) {}", "ctx context.Context", "$decls\n\nfunc g(ctx context.Context<caret>) {}")
        // The type name finds it too, below the types of the package.
        val items = lookups("$decls\n\nfunc g(C<caret>) {}")
        assertContainsAll(items, "circle Circle", "ctx context.Context")
        assertTrue(items.indexOf("circle Circle") < items.indexOf("ctx context.Context"))
    }

    fun testParameterInsertsNameAndType() = checkInsert(
        "$decls\n\nfunc g(<caret>) {}", "base Base",
        "$decls\n\nfunc g(base Base<caret>) {}",
    )

    fun testParameterTypeAfterANameIsAnOrdinaryType() {
        val items = lookups("$decls\n\nfunc g(b <caret>) {}")
        assertContainsAll(items, "Base", "string", "error")
        assertContainsNone(items, "base Base", "err error")
    }

    fun testResultsAndVariadicKeepOrdinaryTypes() {
        assertContainsNone(lookups("$decls\n\nfunc g() (<caret>) {}"), "base Base")
        assertContainsNone(lookups("$decls\n\nfunc g(xs ...<caret>) {}"), "base Base")
    }

    fun testNotOfferedInABodyExpression() {
        val items = lookups("$decls\n\nfunc g() {\n    x := <caret>\n    _ = x\n}")
        assertContainsNone(items, "base Base", "err error")
    }

    // --- variables ---

    fun testVarNameFromItsType() {
        val items = lookups("$decls\n\nfunc g() {\n    var <caret> Circle\n}")
        assertEquals(listOf("circle", "c"), items)
    }

    fun testParameterNameBeforeItsType() {
        val items = lookups("$decls\n\nfunc g(<caret> Base) {}")
        assertEquals(listOf("base", "b"), items)
    }

    fun testShortVarNameFromTheCall() {
        val items = lookups(
            """
            package main

            type Circle struct{}

            func (c Circle) Area() float64 { return 0 }

            func g(c Circle) {
                <caret> := c.Area()
            }
            """,
        )
        assertEquals("area", items.first())
        assertContainsAll(items, "f")
        assertContainsNone(items, "c")
    }

    private fun loop(header: String) = "package main\n\nfunc g(names []string) {\n    for $header := range names {\n    }\n}"

    fun testRangeIndexIsI() = checkInsert(loop("<caret>"), "i", loop("i<caret>"))

    fun testRangeValueIsTheSingular() {
        assertEquals("name", lookups(loop("_, <caret>")).first())
    }

    private fun nested(header: String) =
        "package main\n\nfunc g(names []string) {\n    for i := range names {\n        for $header := range names {\n        }\n        _ = i\n    }\n}"

    fun testNestedLoopIndexMovesOn() = checkInsert(nested("<caret>"), "j", nested("j<caret>"))

    fun testVarNameInsertsTheName() = checkInsert(
        "$decls\n\nfunc g() {\n    var ci<caret> Circle\n}", null,
        "$decls\n\nfunc g() {\n    var circle<caret> Circle\n}",
    )
}
