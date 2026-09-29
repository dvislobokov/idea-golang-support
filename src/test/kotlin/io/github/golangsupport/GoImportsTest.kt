package io.github.golangsupport

import io.github.golangsupport.lang.GoImports
import io.github.golangsupport.lang.GoStructLiterals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoImportsTest {
    private fun imported(text: String, path: String): String? =
        GoImports.add(text, path)?.let { text.substring(0, it.offset) + it.text + text.substring(it.offset) }

    @Test fun theFirstImportOfAFile() {
        assertEquals("package main\n\nimport \"net/http\"\n\nfunc main() {}\n", imported("package main\n\nfunc main() {}\n", "net/http"))
        assertEquals("// Command x.\npackage main // the entry\n\nimport \"fmt\"\n", imported("// Command x.\npackage main // the entry\n", "fmt"))
        assertNull(GoImports.add("func main() {}\n", "fmt"))
    }

    @Test fun nextToASingleImport() {
        assertEquals("package main\n\nimport \"fmt\"\nimport \"net/http\"\n\nfunc main() {}\n", imported("package main\n\nimport \"fmt\"\n\nfunc main() {}\n", "net/http"))
    }

    @Test fun intoTheBlockNextToItsKind() {
        val block = "package main\n\nimport (\n\t\"fmt\"\n\t\"os\" // files\n\n\t\"github.com/google/uuid\"\n)\n"
        assertEquals("package main\n\nimport (\n\t\"fmt\"\n\t\"os\" // files\n\t\"net/http\"\n\n\t\"github.com/google/uuid\"\n)\n", imported(block, "net/http"))
        assertEquals("package main\n\nimport (\n\t\"fmt\"\n\t\"os\" // files\n\n\t\"github.com/google/uuid\"\n\t\"golang.org/x/sync/errgroup\"\n)\n", imported(block, "golang.org/x/sync/errgroup"))
        assertNull(GoImports.add(block, "os"))
    }

    @Test fun theFirstOfItsKindInTheBlock() {
        assertEquals("package main\n\nimport (\n\t\"fmt\"\n\n\t\"github.com/google/uuid\"\n)\n", imported("package main\n\nimport (\n\t\"fmt\"\n)\n", "github.com/google/uuid"))
        assertEquals("package main\n\nimport (\n\t\"fmt\"\n\n\t\"github.com/google/uuid\"\n)\n", imported("package main\n\nimport (\n\t\"github.com/google/uuid\"\n)\n", "fmt"))
    }

    @Test fun whereANameMayBeAPackage() {
        assertTrue(GoImports.isPackagePlace("\tc := htt", 6))
        assertTrue(GoImports.isPackagePlace("\treturn htt", 8))
        assertTrue(GoImports.isPackagePlace("\tf(a, htt", 6))
        // a member, and the names that are declared
        assertFalse(GoImports.isPackagePlace("\tc := os.Op", 9))
        assertFalse(GoImports.isPackagePlace("func htt", 5))
        assertFalse(GoImports.isPackagePlace("type  Htt", 6))
        assertFalse(GoImports.isPackagePlace("package htt", 8))
        assertTrue(GoImports.isStandard("net/http"))
        assertFalse(GoImports.isStandard("github.com/google/uuid"))
    }

    @Test fun whatAnEditHasWrittenAndWhatItNeeds() {
        val before = "package main\n\nimport \"net/http\"\n\nfunc build() {\n\tr := http.Request{}\n\t_ = r\n}\n"
        // what gopls fills http.Request with, shortened
        val filled = "{\n\t\tMethod: \"\",\n\t\tURL:    &url.URL{},\n\t\tHeader: http.Header{},\n\t\tForm:   url.Values{},\n\t\tTLS:    &tls.ConnectionState{},\n\t\tBody:   r.Body.Reader,\n\t}"
        val after = before.replace("{}\n\t_", "$filled\n\t_")
        val written = GoImports.written(before, after)!!
        // the braces were there before
        assertEquals(filled.removePrefix("{").removeSuffix("}"), after.substring(written.first, written.last + 1))
        assertEquals(mapOf("url" to setOf("URL", "Values"), "tls" to setOf("ConnectionState"), "r" to setOf("Body")), GoImports.missing(after, written))
        // nothing new, and an edit that only removes
        assertNull(GoImports.written(before, before))
        assertNull(GoImports.written(after, before))
        // an alias is the name of the package in the file
        val aliased = "package main\n\nimport neturl \"net/url\"\n\nvar u = neturl.URL{}\nvar v = url.Values{}\n"
        assertEquals(mapOf("url" to setOf("Values")), GoImports.missing(aliased, 0 until aliased.length))
    }

    @Test fun literals() {
        fun inLiteral(code: String): Boolean = GoStructLiterals.isInLiteral(code.replace("|", ""), code.indexOf('|'))
        assertTrue(inLiteral("\ta := Options{|}"))
        assertTrue(inLiteral("\tb := &http.Client{Timeout: t, |}"))
        assertTrue(inLiteral("\tb := http.Cli|ent{}"))
        assertTrue(inLiteral("\titems := []Options{|}"))
        assertTrue(inLiteral("\tc := Options{\n\t\tName: \"x\",\n\t\t|\n\t}"))
        assertTrue(inLiteral("\treturn Pair[int]{|}"))
        // a space before the brace, which gofmt would take away
        assertTrue(inLiteral("\tb := http.Client {|}"))
        assertTrue(inLiteral("\tb := http.Cli|ent {}"))
        assertFalse(inLiteral("\tif ready {|}"))
        assertFalse(inLiteral("\tfor _, x := range items {|}"))
        assertFalse(inLiteral("type Options struct {|}"))
        assertFalse(inLiteral("func build() {\n\t|\n}"))
        assertFalse(inLiteral("\t} else {|}"))
        assertFalse(inLiteral("\tgo func() {|}()"))
    }

    @Test fun aTypeWithoutBraces() {
        fun bare(code: String): String? {
            val text = code.replace("|", "")
            return GoStructLiterals.bareName(text, code.indexOf('|'))?.let { (start, end) -> text.substring(start, end) }
        }
        assertEquals("http.Client", bare("\tc := http.Client|"))
        assertEquals("http.Client", bare("\tc := http.Cl|ient\n"))
        assertEquals("Options", bare("\tf(Options|, 1)"))
        assertNull(bare("\tc := http.Client|{}"))
        assertNull(bare("\tc := newClient|()"))
        assertEquals("http.Client", bare("\tc := htt|p.Client"))
        assertNull(bare("\tc := |"))
        assertNull(bare("\tc := http.Client| {}"))
    }

    @Test fun whereAValueIsExpected() {
        fun value(code: String): Boolean = GoStructLiterals.isValuePlace(code.replace("|", ""), code.indexOf('|'))
        assertTrue(value("\tc := |http.Client"))
        assertTrue(value("\tc = |http.Client"))
        assertTrue(value("\tc := &|http.Client"))
        assertTrue(value("\treturn |Options"))
        assertTrue(value("\treturn nil, |Options"))
        assertTrue(value("\tsend(ctx, |Options"))
        assertTrue(value("\tsend(|Options"))
        assertTrue(value("\tvar c http.Client = |http.Client"))
        assertTrue(value("\tx := Outer{\n\t\tInner: |Options"))
        // where a type is expected
        assertFalse(value("\tvar c |http.Client"))
        assertFalse(value("func send(o |Options"))
        assertFalse(value("func send(ctx context.Context, o |Options"))
        assertFalse(value("type Client = |http.Client"))
        assertFalse(value("\tClient |http.Client"))
        assertFalse(value("\tif a == |b"))
        assertFalse(value("\tcase |Options"))
    }
}
