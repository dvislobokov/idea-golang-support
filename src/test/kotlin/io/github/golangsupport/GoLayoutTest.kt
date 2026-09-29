package io.github.golangsupport

import io.github.golangsupport.catalogue.GoModuleSymbols
import io.github.golangsupport.catalogue.GoPackageSymbols
import io.github.golangsupport.catalogue.GoSymbol
import io.github.golangsupport.catalogue.GoSymbolIndex
import io.github.golangsupport.lang.GoDeclarationKind
import io.github.golangsupport.lang.GoLayout
import io.github.golangsupport.lang.GoPrefixMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoLayoutTest {
    @Test fun theKeysOfTheOtherLayout() {
        assertEquals("println", GoLayout.latin("зкштедт"))
        assertEquals("fmt.Println", GoLayout.latin("аьеюЗкштедт"))
        assertEquals("http.Client{}", GoLayout.latin("реезюСдшутеХЪ"))
        assertEquals("NewRequest", GoLayout.latin("ТуцКуйгуые"))
        // what is Latin already, digits and signs stay
        assertEquals("fmt.Println(x1)", GoLayout.latin("fmt.Println(x1)"))
        assertEquals("err2", GoLayout.latin("укк2"))
        assertEquals('q', GoLayout.latin('й'))
        assertEquals('.', GoLayout.latin('ю'))
        assertEquals('{', GoLayout.latin('Х'))
        assertNull(GoLayout.latin('q'))
        assertNull(GoLayout.latin('1'))
        // a letter of another alphabet is no key of this layout
        assertNull(GoLayout.latin('є'))
    }

    private fun isText(code: String): Boolean = GoLayout.isText(code.replace("|", ""), code.indexOf('|'))

    @Test fun whereALetterIsALetter() {
        assertTrue(isText("\tfmt.Println(\"привет|\")"))
        assertTrue(isText("\tfmt.Println(\"|\")"))
        assertTrue(isText("\tname := \"не закрыта|"))
        assertTrue(isText("\tr := '|'"))
        assertTrue(isText("\t// комментарий|"))
        assertTrue(isText("\tx := 1 // в конце строки|\n\ty := 2"))
        assertTrue(isText("/*\n блок|\n*/\npackage main"))
        assertTrue(isText("/* не закрыт|"))
        assertTrue(isText("var q = `\n\tselect *\n\tиз таблицы|\n`"))
        assertTrue(isText("type T struct {\n\tName string `json:\"имя|\"`\n}"))
        assertTrue(isText("\ts := \"a \\\" b|\""))
    }

    @Test fun whereALetterIsAKey() {
        assertFalse(isText("\t|"))
        assertFalse(isText("\tfmt.Println(\"привет\")|"))
        assertFalse(isText("\tfmt.Println(\"привет\", |)"))
        assertFalse(isText("\t// комментарий\n\t|"))
        assertFalse(isText("/* блок */ |"))
        assertFalse(isText("var q = `raw` + |"))
        assertFalse(isText("\tr := 'я'\n\t|"))
        assertFalse(isText("\turl := \"http://x\" + |"))
        // a quote left open ends with its line
        assertFalse(isText("\tname := \"не закрыта\n\t|"))
        assertFalse(isText("\ta := b / c + |"))
    }

    @Test fun whatIsTypedAndWhatIsMeant() {
        val russian = GoPrefixMatcher("зкште")
        assertEquals("зкште", russian.prefix)
        assertEquals("print", russian.latin)
        assertTrue(russian.prefixMatches("Println"))
        assertTrue(russian.prefixMatches("printer"))
        // the matcher of the platform finds a word inside a name as well; what goes into the list is chosen by its beginning before that
        assertFalse(russian.prefixMatches("Fatal"))
        // whatever the case, and by the humps of a name
        assertTrue(GoPrefixMatcher("client").prefixMatches("Client"))
        assertTrue(GoPrefixMatcher("CLIENT").prefixMatches("Client"))
        assertTrue(GoPrefixMatcher("NR").prefixMatches("NewRequest"))
        assertTrue(GoPrefixMatcher("newreq").prefixMatches("NewRequest"))
        // the list that is open is narrowed with the next letter, which may be of the other layout as well
        val next = GoPrefixMatcher("зк").cloneWithPrefix("зкшт") as GoPrefixMatcher
        assertEquals("prin", next.latin)
        assertTrue(next.prefixMatches("Println"))
    }

    @Test fun aPackageAndItsNameAsOneWord() {
        fun pack(path: String, name: String, vararg functions: String) = GoPackageSymbols(path, name, functions.map { GoSymbol(it, GoDeclarationKind.FUNCTION, "(a ...any)") })
        val index = GoSymbolIndex(listOf(GoModuleSymbols("std", true, listOf(pack("fmt", "fmt", "Print", "Println", "Sprintf"), pack("log", "log", "Print", "Println")))))
        fun found(prefix: String, qualifier: String?): List<String> = index.find(prefix, 10, emptySet(), qualifier).map { "${it.pack.name}.${it.symbol.name}" }
        val latin = GoLayout.latin("аьеюЗкште")
        assertEquals("fmt.Print", latin)
        assertEquals(listOf("fmt.Print", "fmt.Println"), found(latin.substringAfterLast('.'), latin.substringBeforeLast('.')))
        // `аьею`: everything of the package
        assertEquals(listOf("fmt.Print", "fmt.Println", "fmt.Sprintf"), found("", "fmt"))
        assertEquals(listOf("log.Println"), found("printl", "LOG"))
        assertEquals(emptyList<String>(), found("Print", "os"))
    }
}
