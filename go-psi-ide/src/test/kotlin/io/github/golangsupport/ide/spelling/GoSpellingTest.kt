package io.github.golangsupport.ide.spelling

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SyntaxTraverser
import com.intellij.spellchecker.inspections.SpellCheckingInspection
import com.intellij.spellchecker.inspections.SpellCheckingInspection.SpellCheckingScope
import com.intellij.spellchecker.inspections.Splitter
import com.intellij.spellchecker.tokenizer.LanguageSpellchecking
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy
import com.intellij.spellchecker.tokenizer.TokenConsumer
import io.github.golangsupport.ide.GoIdeTestBase
import io.github.golangsupport.lang.GoLanguage

/**
 * What the spellchecker reads in Go. [checked] is the text of every range the strategy hands over (before the platform splitters,
 * which also drop words of three letters and less); [words] what the splitters make of it.
 */
class GoSpellingTest : GoIdeTestBase() {

    private fun tokens(file: PsiFile, scopes: Set<SpellCheckingScope>, consume: (String, TextRange, Splitter) -> Unit) {
        val consumer = object : TokenConsumer() {
            override fun consumeToken(element: PsiElement, text: String, useRename: Boolean, offset: Int, rangeToCheck: TextRange, splitter: Splitter) =
                consume(text, rangeToCheck, splitter)
        }
        for (element in SyntaxTraverser.psiTraverser(file)) {
            // The way the inspection asks: the strategy of the element's language, its scope check, its tokenizer.
            val strategy = SpellcheckingStrategy.getSpellcheckingStrategy(element) ?: continue
            if (!strategy.elementFitsScope(element, scopes)) continue
            SpellCheckingInspection.tokenize(element, consumer, scopes)
        }
    }

    private fun checked(text: String, scopes: Set<SpellCheckingScope> = SpellCheckingScope.entries.toSet()): List<String> {
        val out = ArrayList<String>()
        tokens(myFixture.configureByText("s.go", text), scopes) { t, range, _ -> out += range.substring(t) }
        return out
    }

    private fun words(text: String, scopes: Set<SpellCheckingScope> = SpellCheckingScope.entries.toSet()): List<String> {
        val out = ArrayList<String>()
        tokens(myFixture.configureByText("w.go", text), scopes) { t, range, splitter -> splitter.split(t, range) { out += it.substring(t) } }
        return out
    }

    fun testRegisteredForGo() {
        assertTrue(LanguageSpellchecking.INSTANCE.allForLanguage(GoLanguage).any { it is GoSpellcheckingStrategy })
    }

    fun testDeclaredIdentifiersOnly() {
        val text = """
            package parsers

            import fmtx "fmt"

            type httpRequestParser struct {
            	io.Reader
            	max_retries int
            }

            func (p *httpRequestParser) parseHeader(lineBuf []byte) int {
            	countValue := len(lineBuf)
            	fmtx.Println(countValue)
            	return countValue
            }
        """.trimIndent()
        // Declarations only: not the package, the import alias, the embedded type or the uses.
        assertEquals(listOf("httpRequestParser", "max_retries", "parseHeader", "p", "lineBuf", "countValue"), checked(text))
        // Split by case and underscores.
        assertEquals(listOf("http", "Request", "Parser", "retries", "parse", "Header", "line", "count", "Value"), words(text))
    }

    fun testComments() {
        val text = """
            //go:build linux
            // +build linux

            // Package demo shows [fmt.Println] and [*Thing] at https://go.dev/doc/commnt here.
            package demo

            //go:generate stringer -type=Kind
            //nolint:errcheck // reasn
            //export Exported
            // Uses `badd.Code` too.
            //	indented := codee()
            var x = 1 /* blok */
        """.trimIndent()
        assertEquals(
            listOf(" Package demo shows ", " and ", " at ", " here.", " Uses ", " too.", "x", " blok "),
            checked(text),
        )
        assertEquals(listOf("Package", "demo", "shows", "here", "Uses", "blok"), words(text))
    }

    fun testStringsAndRunes() {
        val text = """
            package demo

            import "github.com/someorg/somepkg"

            type T struct {
            	Name string `json:"nmae,omitempty"`
            }

            var a = "Hello\nWrold %-8s %v\t%d%%"
            var b = `raw tekst`
            var c = 'x'
        """.trimIndent()
        // Not the import path, the struct tag or the rune; escapes and verbs are blanked out.
        assertEquals(listOf("T", "Name", "a", "Hello  Wrold" + " ".repeat(14), "b", "raw tekst", "c"), checked(text))
        assertEquals(listOf("Name", "Hello", "Wrold", "tekst"), words(text))
    }

    fun testCgoPreambleIsNotProse() {
        val text = "package demo\n\n// #include <stdio.h>\n// static void helloo() {}\nimport \"C\"\n\n// Realy prose.\nvar v = 1\n"
        assertEquals(listOf(" Realy prose."), checked(text, setOf(SpellCheckingScope.Comments)))
    }

    fun testScopes() {
        val text = "package demo\n\n// Wrold is spelt wrongly.\nfunc helloWrold() string { return \"hello wrold\" }\n"
        assertEquals(listOf("Wrold", "spelt", "wrongly"), words(text, setOf(SpellCheckingScope.Comments)))
        assertEquals(listOf("hello", "wrold"), words(text, setOf(SpellCheckingScope.Literals)))
        assertEquals(listOf("hello", "Wrold"), words(text, setOf(SpellCheckingScope.Code)))
    }

    fun testTextHelpers() {
        assertEquals(listOf(TextRange(2, 8)), GoSpellingText.commentRanges("// hello"))
        assertEquals(emptyList<TextRange>(), GoSpellingText.commentRanges("//go:embed x"))
        assertEquals(emptyList<TextRange>(), GoSpellingText.commentRanges("// nolint:all"))
        assertEquals("\"a  b    c  \"", GoSpellingText.maskString("\"a\\nb\\x41c%s\""))
        assertEquals(TextRange(1, 4), GoSpellingText.stringContent("`abc`"))
        assertNull(GoSpellingText.stringContent("\"\""))
    }
}
