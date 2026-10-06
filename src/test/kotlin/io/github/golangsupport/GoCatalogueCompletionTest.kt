package io.github.golangsupport

import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.catalogue.GoCatalogueService
import io.github.golangsupport.catalogue.GoModuleSymbols
import io.github.golangsupport.catalogue.GoPackageSymbols
import io.github.golangsupport.catalogue.GoSymbol
import io.github.golangsupport.catalogue.GoSymbolIndex
import io.github.golangsupport.lang.GoDeclarationKind

/** The catalogue in completion lists: smart rows of the expected type (probes 12, 14, 24), every package of a name after `json.` (probes 10, 10b). */
class GoCatalogueCompletionTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        fun fn(name: String, signature: String) = GoSymbol(name, GoDeclarationKind.FUNCTION, signature)
        GoCatalogueService.getInstance(project).useModulesForTests(listOf(GoModuleSymbols("std-test", true, listOf(
            GoPackageSymbols("strings", "strings", listOf(fn("Count", "(s, substr string) int"), fn("Index", "(s, substr string) int"), fn("ToUpper", "(s string) string"))),
            GoPackageSymbols("unicode/utf8", "utf8", listOf(fn("RuneLen", "(r rune) int"))),
            GoPackageSymbols("example.test/jsonx", "jsonx", listOf(fn("Marshal", "(v any) ([]byte, error)"))),
            GoPackageSymbols("example.test/jsonx/v2", "jsonx", listOf(fn("Marshal", "(in any, opts ...Options) (out []byte, err error)"), fn("MarshalWrite", "(out io.Writer, in any) error"))),
        ))))
    }

    override fun tearDown() {
        try {
            GoCatalogueService.getInstance(project).useModulesForTests(emptyList())
        } finally {
            super.tearDown()
        }
    }

    private fun lines(vararg lines: String) = lines.joinToString("\n") + "\n"

    private fun entry(element: LookupElement): GoSymbolIndex.Entry? = element.`object` as? GoSymbolIndex.Entry

    private fun rows(): List<String> = myFixture.lookupElements.orEmpty().mapNotNull { e -> entry(e)?.let { "${it.pack.importPath}.${it.symbol.name}" } }

    fun testSmartOffersCatalogueFunctionsOfTheExpectedType() {
        myFixture.configureByText("p.go", lines("package p", "", "import \"strings\"", "", "func f() {", "\tvar total int", "\ttotal = <caret>", "\t_ = strings.ToUpper", "}"))
        myFixture.complete(CompletionType.SMART)
        val all = myFixture.lookupElementStrings.orEmpty()
        val rows = rows()
        // the package the file imports first; a string is not an int
        assertEquals(listOf("strings.Count", "strings.Index", "unicode/utf8.RuneLen"), rows)
        // below what the file itself has of that type
        assertTrue(all.toString(), all.indexOf("total") in 0 until all.indexOf("Count"))
        val presentation = LookupElementPresentation.renderElement(myFixture.lookupElements!!.first { entry(it)?.symbol?.name == "Count" })
        assertEquals("strings.Count", presentation.itemText)
        assertEquals("strings", presentation.typeText)
    }

    fun testSmartCatalogueRowWritesTheCallAndTheImport() {
        myFixture.configureByText("p.go", lines("package p", "", "func f(ch chan int) {", "\tch <- <caret>", "}"))
        myFixture.complete(CompletionType.SMART)
        val lookup = myFixture.lookup
        assertNotNull(lookup)
        lookup.currentItem = myFixture.lookupElements!!.first { entry(it)?.symbol?.name == "RuneLen" }
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("ch <- utf8.RuneLen()"))
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("\"unicode/utf8\""))
    }

    fun testSmartWithAStringExpectedHasNoIntRows() {
        myFixture.configureByText("p.go", lines("package p", "", "func f() string {", "\treturn <caret>", "}"))
        myFixture.complete(CompletionType.SMART)
        assertEquals(listOf("strings.ToUpper"), rows())
    }

    fun testEveryPackageOfANameAfterItsDot() {
        myFixture.configureByText("p.go", lines("package p", "", "func f() {", "\tjsonx.Mar<caret>", "}"))
        myFixture.completeBasic()
        val rows = rows()
        assertTrue(rows.toString(), "example.test/jsonx/v2.Marshal" in rows && "example.test/jsonx/v2.MarshalWrite" in rows)
        val v2 = myFixture.lookupElements!!.first { entry(it)?.pack?.importPath == "example.test/jsonx/v2" && entry(it)?.symbol?.name == "Marshal" }
        assertEquals("example.test/jsonx/v2", LookupElementPresentation.renderElement(v2).typeText)
        myFixture.lookup.currentItem = v2
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("\tjsonx.Marshal()\n"))
        assertTrue(text, text.contains("\"example.test/jsonx/v2\""))
    }

    fun testAnImportedPackageIsLeftToTheMembers() {
        myFixture.configureByText("p.go", lines("package p", "", "import \"strings\"", "", "func f() {", "\tstrings.Cou<caret>", "}"))
        myFixture.completeBasic()
        assertTrue(rows().toString(), rows().none { it.startsWith("strings.") })
    }
}
