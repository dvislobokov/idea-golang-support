package io.github.golangsupport

import io.github.golangsupport.catalogue.GoCatalogueFiles
import io.github.golangsupport.catalogue.GoCatalogueInsertion
import io.github.golangsupport.catalogue.GoCatalogueScanner
import io.github.golangsupport.catalogue.GoCatalogueService
import io.github.golangsupport.catalogue.GoCatalogueSmart
import io.github.golangsupport.catalogue.GoModuleSymbols
import io.github.golangsupport.catalogue.GoPackageSymbols
import io.github.golangsupport.catalogue.GoSymbol
import io.github.golangsupport.catalogue.GoSymbolIndex
import io.github.golangsupport.lang.GoDeclarationKind
import io.github.golangsupport.project.api.GoModule
import io.github.golangsupport.project.api.GoModuleVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The catalogue beyond the bare names: methods of its types, the indirect modules, smart keys, `json.` of a package not imported. */
class GoCatalogueMembersTest {
    @get:Rule val folder = TemporaryFolder()

    private val builder = """
        package strings

        type Builder struct{ buf []byte }

        type reader struct{}

        func (b *Builder) WriteString(s string) (int, error) { return 0, nil }
        func (b Builder) Len() int { return 0 }
        func (b *Builder) grow(n int) {}
        func (r *reader) Read(p []byte) (int, error) { return 0, nil }
        func (r *reader) Exported() {}

        func Count(s, substr string) int { return 0 }
        """.trimIndent()

    @Test fun theMethodsOfTheExportedTypes() {
        val pack = GoCatalogueScanner.scanPackage("strings", listOf(builder))!!
        assertEquals(listOf("Builder.Len(value) () int", "Builder.WriteString(pointer) (s string) (int, error)"),
            pack.methods.map { "${it.receiver}.${it.name}(${if (it.pointer) "pointer" else "value"}) ${it.signature}" })
        assertEquals(listOf("Len", "WriteString"), pack.methodsOf("Builder").map { it.name })
        // a method of an unexported type cannot be reached by name; an unexported method not at all
        assertTrue(pack.methodsOf("reader").isEmpty())
    }

    @Test fun theMethodsGoThroughTheFile() {
        val module = GoModuleSymbols("std@go1.27", true, listOf(GoCatalogueScanner.scanPackage("strings", listOf(builder))!!))
        val file = File(folder.root, GoCatalogueFiles.fileName(module.key))
        GoCatalogueFiles.write(file, module)
        val read = GoCatalogueFiles.read(file, module.key)!!
        val index = GoSymbolIndex(listOf(read))
        assertEquals(listOf("Len" to false, "WriteString" to true), index.methodsOf("strings", "Builder").map { it.name to it.pointer })
        assertTrue(index.methodsOf("strings", "Missing").isEmpty())
        assertTrue(index.methodsOf("bytes", "Builder").isEmpty())
        assertEquals(3, GoCatalogueFiles.VERSION)
    }

    private fun module(path: String, version: String?, dir: File?, main: Boolean = false, replacement: GoModuleVersion? = null) =
        GoModule(path, version, dir?.toPath(), null, null, main, replacement = replacement)

    @Test fun theIndirectModulesOfTheBuildList() {
        val a = folder.newFolder("a")
        val b = folder.newFolder("b")
        val c = folder.newFolder("c")
        val buildList = listOf(
            module("example.com/app", null, folder.root, main = true),
            module("github.com/direct", "v1.0.0", a),
            module("github.com/indirect", "v1.2.0", b),
            module("github.com/indirect", "v1.2.0", b),
            module("github.com/missing", "v0.1.0", null),
            module("github.com/gone", "v0.1.0", File(folder.root, "absent")),
            module("github.com/local", "v1.0.0", c, replacement = GoModuleVersion("../local", null)),
            module("github.com/second", "v2.0.0", c),
        )
        val found = GoCatalogueService.indirectModules(buildList, setOf("github.com/direct"), 10)
        assertEquals(listOf("github.com/indirect@v1.2.0", "github.com/second@v2.0.0"), found.map { "${it.path}@${it.version}" })
        assertEquals(1, GoCatalogueService.indirectModules(buildList, setOf("github.com/direct"), 1).size)
    }

    private fun pack(path: String, name: String, vararg symbols: GoSymbol) = GoPackageSymbols(path, name, symbols.toList())
    private fun fn(name: String, signature: String) = GoSymbol(name, GoDeclarationKind.FUNCTION, signature)

    @Test fun indirectModulesRankBelowDirectOnes() {
        val index = GoSymbolIndex(listOf(
            GoModuleSymbols("ind", false, listOf(pack("example.com/a", "a", fn("Parse", "()"))), indirect = true),
            GoModuleSymbols("dir", false, listOf(pack("example.com/b", "b", fn("Parse", "()")))),
        ))
        assertEquals(listOf("example.com/b", "example.com/a"), index.find("Parse", 10).map { it.pack.importPath })
        assertTrue(index.find("Parse", 10).last().indirect)
    }

    @Test fun theKeyOfTheTypeOfAValue() {
        fun key(symbol: GoSymbol) = GoCatalogueSmart.resultKey(symbol, "net/http")
        assertEquals("int", key(fn("Count", "(s, substr string) int")))
        assertEquals("uint8", key(fn("At", "(i int) byte")))
        assertEquals("*net/http.Request", key(fn("NewRequest", "(method, url string, body io.Reader) (*Request, error)")))
        assertEquals("@time.Duration", key(fn("Timeout", "() time.Duration")))
        assertEquals("error", key(GoSymbol("ErrAbort", GoDeclarationKind.VAR, "error")))
        assertEquals("net/http.Header", key(GoSymbol("Empty", GoDeclarationKind.VAR, "Header")))
        // nothing to match: no result, a slice, a generic function, an untyped constant, a type, a cut signature
        assertNull(key(fn("Run", "()")))
        assertNull(key(fn("Fields", "(s string) []string")))
        assertNull(key(fn("Max", "[T cmp.Ordered](a, b T) T")))
        assertNull(key(GoSymbol("StatusOK", GoDeclarationKind.CONST, null)))
        assertNull(key(GoSymbol("Client", GoDeclarationKind.STRUCT, null)))
        assertNull(key(fn("Long", "(a int) int…")))
    }

    @Test fun whatFitsTheExpectedType() {
        val index = GoSymbolIndex(listOf(GoModuleSymbols("std", true, listOf(
            pack("strings", "strings", fn("Count", "(s, substr string) int"), fn("Index", "(s, substr string) int"), fn("ToUpper", "(s string) string")),
            pack("unicode/utf8", "utf8", fn("RuneLen", "(r rune) int"), GoSymbol("UTFMax", GoDeclarationKind.CONST, "int")),
            pack("time", "time", fn("Since", "(t Time) Duration")),
            pack("net/http", "http", fn("Timeout", "() time.Duration")),
        ))))
        fun fitting(keys: List<String>, perPackage: Int = 10, preferred: Set<String> = emptySet()) =
            index.fitting(keys, 30, perPackage, preferred).map { it.pack.name + "." + it.symbol.name }
        assertEquals(listOf("strings.Count", "strings.Index", "utf8.RuneLen", "utf8.UTFMax"), fitting(listOf("int")))
        assertEquals(listOf("utf8.RuneLen", "utf8.UTFMax", "strings.Count", "strings.Index"), fitting(listOf("int"), preferred = setOf("unicode/utf8")))
        assertEquals(listOf("strings.Count", "utf8.RuneLen"), fitting(listOf("int"), perPackage = 1))
        assertEquals(listOf("http.Timeout", "time.Since"), fitting(listOf("time.Duration", "@time.Duration")))
        assertEquals(emptyList<String>(), fitting(listOf("float64")))
    }

    @Test fun theQualifierOfAPackageNotImported() {
        assertEquals("json", GoCatalogueInsertion.packageQualifier("\tjson.Mar", 6))
        assertEquals("json", GoCatalogueInsertion.packageQualifier("\tjson.", 6))
        assertNull(GoCatalogueInsertion.packageQualifier("\tx.json.Mar", 8))
        assertNull(GoCatalogueInsertion.packageQualifier("\tMar", 1))
        assertNull(GoCatalogueInsertion.packageQualifier("\t1.5", 3))
        assertEquals(1, GoCatalogueInsertion.replacedFrom("\tjson.Mar", 6, "json"))
        assertEquals(6, GoCatalogueInsertion.replacedFrom("\tjson.Mar", 6, "jsonv2"))
        assertEquals(8, GoCatalogueInsertion.replacedFrom("\tx.json.Mar", 8, "json"))
    }

    /** A one-letter prefix over a catalogue of the size of the standard library: the list must stay instant. */
    @Test fun aShortPrefixOverABigCatalogueIsFast() {
        val letters = "abcdefghijklmnopqrstuvwxyz"
        val packages = (0 until 100).map { p -> pack("pkg$p", "pkg$p", *Array(50) { s -> fn(letters[(p + s) % 26].uppercase() + "name$p$s", "() int") }) }
        val index = GoSymbolIndex(listOf(GoModuleSymbols("std", true, packages)))
        assertEquals(5000, index.size)
        index.find("c", 40)
        val started = System.nanoTime()
        repeat(10) { assertEquals(40, index.find("c", 40).size) }
        val millis = (System.nanoTime() - started) / 1_000_000 / 10
        assertTrue("$millis ms", millis < 100)
    }
}
