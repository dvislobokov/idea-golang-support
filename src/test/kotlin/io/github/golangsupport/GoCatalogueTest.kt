package io.github.golangsupport

import io.github.golangsupport.catalogue.GoCatalogueFiles
import io.github.golangsupport.catalogue.GoCatalogueInsertion
import io.github.golangsupport.catalogue.GoCatalogueScanner
import io.github.golangsupport.catalogue.GoModuleSymbols
import io.github.golangsupport.catalogue.GoPackageSymbols
import io.github.golangsupport.catalogue.GoSymbol
import io.github.golangsupport.catalogue.GoSymbolIndex
import io.github.golangsupport.lang.GoDeclarationKind
import io.github.golangsupport.lang.GoImports
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GoCatalogueTest {
    @get:Rule val folder = TemporaryFolder()

    private val client = """
        package http

        import "time"

        const StatusOK = 200

        var DefaultClient = &Client{}

        type Client struct {
            Timeout time.Duration
        }

        type Handler interface {
            ServeHTTP(w ResponseWriter, r *Request)
        }

        func (c *Client) Do(req *Request) (*Response, error) { return nil, nil }

        func NewRequest(method, url string, body io.Reader) (*Request, error) { return nil, nil }

        func newTransport() *Transport { return nil }
        """.trimIndent()

    private fun symbols(pack: GoPackageSymbols?): List<String> = pack?.symbols.orEmpty().map { "${it.kind.title} ${it.name}" }

    @Test fun theExportedNamesOfAPackage() {
        val pack = GoCatalogueScanner.scanPackage("net/http", listOf(client, "package http\n\nfunc Get(url string) (*Response, error) { return nil, nil }\n"))
        assertEquals("http", pack?.name)
        assertEquals(listOf("struct Client", "var DefaultClient", "func Get", "interface Handler", "func NewRequest", "const StatusOK"), symbols(pack))
        assertEquals("(method, url string, body io.Reader) (*Request, error)", pack?.symbols?.first { it.name == "NewRequest" }?.signature)
    }

    @Test fun whatIsNotAPackageToImport() {
        assertNull(GoCatalogueScanner.scanPackage("example.com/tool", listOf("package main\n\nfunc Main() {}\n")))
        assertNull(GoCatalogueScanner.scanPackage("example.com/x", listOf("package x\n\nfunc hidden() {}\n")))
        // a generator kept next to the package it generates for
        val pack = GoCatalogueScanner.scanPackage("example.com/x", listOf("package x\n\nfunc A() {}\n", "package x\n\nfunc B() {}\n", "package main\n\nfunc Gen() {}\n"))
        assertEquals(listOf("func A", "func B"), symbols(pack))
        // the same name for two systems
        assertEquals(listOf("func Open"), symbols(GoCatalogueScanner.scanPackage("os", listOf("package os\n\nfunc Open() {}\n", "package os\n\nfunc Open() {}\n"))))
        assertTrue(GoCatalogueScanner.isSource("client.go"))
        assertFalse(GoCatalogueScanner.isSource("client_test.go"))
        assertFalse(GoCatalogueScanner.isPackageDirectory("internal", false))
        assertFalse(GoCatalogueScanner.isPackageDirectory("testdata", false))
        assertFalse(GoCatalogueScanner.isPackageDirectory("cmd", true))
        assertTrue(GoCatalogueScanner.isPackageDirectory("cmd", false))
    }

    @Test fun aModuleFromItsDirectory() {
        val root = folder.newFolder("uuid@v1.6.0")
        File(root, "go.mod").writeText("module github.com/google/uuid\n")
        File(root, "uuid.go").writeText("package uuid\n\nfunc New() UUID { return UUID{} }\n\ntype UUID [16]byte\n")
        File(root, "uuid_test.go").writeText("package uuid\n\nfunc TestNew(t *testing.T) {}\n\nfunc Helper() {}\n")
        File(root, "internal/rand").mkdirs()
        File(root, "internal/rand/rand.go").writeText("package rand\n\nfunc Read() {}\n")
        File(root, "codec").mkdirs()
        File(root, "codec/codec.go").writeText("package codec\n\nfunc Encode() {}\n")
        // a module of its own inside the directory of another
        File(root, "tools").mkdirs()
        File(root, "tools/go.mod").writeText("module github.com/google/uuid/tools\n")
        File(root, "tools/tools.go").writeText("package tools\n\nfunc Run() {}\n")

        val packages = GoCatalogueScanner.scanModule(root, "github.com/google/uuid", false)
        assertEquals(listOf("github.com/google/uuid", "github.com/google/uuid/codec"), packages.map { it.importPath })
        assertEquals(listOf("func New", "type UUID"), symbols(packages[0]))
    }

    @Test fun theFileOfAModule() {
        val module = GoModuleSymbols("github.com/google/uuid@v1.6.0", false, listOf(GoCatalogueScanner.scanPackage("net/http", listOf(client))!!))
        val file = File(folder.root, GoCatalogueFiles.fileName(module.key))
        GoCatalogueFiles.write(file, module)
        val read = GoCatalogueFiles.read(file, module.key)
        assertNotNull(read)
        assertEquals(symbols(module.packages[0]), symbols(read!!.packages[0]))
        assertEquals("net/http", read.packages[0].importPath)
        assertEquals("(method, url string, body io.Reader) (*Request, error)", read.packages[0].symbols.first { it.name == "NewRequest" }.signature)
        assertNull(read.packages[0].symbols.first { it.name == "StatusOK" }.signature?.takeIf { it.isEmpty() })
        // a file of another module under the same name, a file that is not one of ours, no file
        assertNull(GoCatalogueFiles.read(file, "github.com/google/uuid@v1.7.0"))
        file.writeText("something else")
        assertNull(GoCatalogueFiles.read(file, module.key))
        assertNull(GoCatalogueFiles.read(File(folder.root, "absent.bin"), module.key))
        assertEquals("github.com_google_uuid@v1.6.0", GoCatalogueFiles.fileName(module.key).substringBeforeLast('-'))
    }

    private fun pack(path: String, name: String, vararg functions: String) = GoPackageSymbols(path, name, functions.map { GoSymbol(it, GoDeclarationKind.FUNCTION, "(a ...any)") })

    private val index = GoSymbolIndex(listOf(
        GoModuleSymbols("std", true, listOf(pack("fmt", "fmt", "Print", "Printf", "Println", "Sprintf"), pack("log", "log", "Print", "Println", "Fatal"), pack("go/printer", "printer", "Fprint"))),
        GoModuleSymbols("zap", false, listOf(pack("go.uber.org/zap", "zap", "Print", "NewProduction"))),
    ))

    private fun found(prefix: String, vararg preferred: String): List<String> = index.find(prefix, 10, preferred.toSet()).map { "${it.pack.name}.${it.symbol.name}" }

    @Test fun byTheBeginningOfAName() {
        // the standard library before the modules, a shorter name before a longer one
        assertEquals(listOf("fmt.Print", "log.Print", "fmt.Printf", "fmt.Println", "log.Println", "zap.Print"), found("Print"))
        assertEquals(listOf("fmt.Println", "log.Println"), found("Printl"))
        // the case of the letters is for the order, not for the match
        assertEquals(listOf("fmt.Println", "log.Println"), found("printl"))
        assertEquals(listOf("zap.NewProduction"), found("NewP"))
        assertEquals(emptyList<String>(), found("Missing"))
        assertEquals(emptyList<String>(), found(""))
        assertEquals(2, index.find("Print", 2).size)
    }

    @Test fun thePackagesOfTheFileGoFirst() {
        assertEquals(listOf("zap.Print", "fmt.Print", "log.Print"), found("Print", "go.uber.org/zap").take(3))
        assertEquals(listOf("log.Println", "fmt.Println"), found("Printl", "log"))
    }

    private fun insertion(name: String, code: String): String? {
        val entry = index.find(name, 1).single()
        return GoCatalogueInsertion.of(entry, GoImports.importsOf(code))?.let { "${it.text} caret-${it.caretFromEnd} import=${it.importPath}" }
    }

    @Test fun howASymbolIsWritten() {
        assertEquals("fmt.Sprintf() caret-1 import=fmt", insertion("Sprintf", "package main\n"))
        assertEquals("fmt.Sprintf() caret-1 import=null", insertion("Sprintf", "package main\n\nimport \"fmt\"\n"))
        assertEquals("f.Sprintf() caret-1 import=null", insertion("Sprintf", "package main\n\nimport f \"fmt\"\n"))
        assertEquals("Sprintf() caret-1 import=null", insertion("Sprintf", "package main\n\nimport . \"fmt\"\n"))
        // imported for its side effects only: imported again, to be used
        assertEquals("fmt.Sprintf() caret-1 import=fmt", insertion("Sprintf", "package main\n\nimport _ \"fmt\"\n"))
        // the name of the package means another package in this file
        assertNull(insertion("Sprintf", "package main\n\nimport \"example.com/fmt\"\n"))
        assertNull(insertion("Sprintf", "package main\n\nimport fmt \"example.com/format\"\n"))
    }

    @Test fun thePackageOfANameAndWhatIsTakenOfIt() {
        fun types(path: String, name: String, vararg types: String) = GoPackageSymbols(path, name, types.map { GoSymbol(it, GoDeclarationKind.STRUCT, null) })
        val catalogue = GoSymbolIndex(listOf(
            GoModuleSymbols("dep", false, listOf(types("example.com/net/url", "url", "URL", "Values"))),
            GoModuleSymbols("std", true, listOf(types("net/url", "url", "URL", "Values"), types("math/rand", "rand", "Rand", "Source"), types("crypto/rand", "rand", "Reader"))),
        ))
        // the standard library before a module with a package of the same name
        assertEquals("net/url", catalogue.packageOf("url", setOf("URL", "Values")))
        assertEquals("crypto/rand", catalogue.packageOf("rand", setOf("Reader")))
        assertEquals("math/rand", catalogue.packageOf("rand", setOf("Rand")))
        assertNull(catalogue.packageOf("rand", setOf("Reader", "Rand")))
        assertNull(catalogue.packageOf("tls", setOf("ConnectionState")))
        // a local variable is no package
        assertNull(catalogue.packageOf("r", setOf("Body")))
    }

    /** What pasted text from outside the IDE gets imported from: only the standard library, every candidate (the caller wants one). */
    @Test fun theStandardPackagesOfAName() {
        fun types(path: String, name: String, vararg types: String) = GoPackageSymbols(path, name, types.map { GoSymbol(it, GoDeclarationKind.STRUCT, null) })
        val catalogue = GoSymbolIndex(listOf(
            GoModuleSymbols("dep", false, listOf(types("example.com/json", "json", "Marshal"))),
            GoModuleSymbols("std", true, listOf(
                types("encoding/json", "json", "Marshal", "Unmarshal"), types("text/template", "template", "New"), types("html/template", "template", "New", "HTML"),
            )),
        ))
        assertEquals(listOf("encoding/json"), catalogue.standardPackagesOf("json", setOf("Marshal")))
        assertEquals(listOf("text/template", "html/template"), catalogue.standardPackagesOf("template", setOf("New")))
        assertEquals(listOf("html/template"), catalogue.standardPackagesOf("template", setOf("New", "HTML")))
        assertEquals(emptyList<String>(), catalogue.standardPackagesOf("json", setOf("Decode")))
    }

    @Test fun aFunctionWithoutParametersAndAType() {
        val entries = GoSymbolIndex(listOf(GoModuleSymbols("std", true, listOf(GoPackageSymbols("time", "time", listOf(
            GoSymbol("Now", GoDeclarationKind.FUNCTION, "() Time"), GoSymbol("Duration", GoDeclarationKind.TYPE, "int64"),
        ))))))
        val now = GoCatalogueInsertion.of(entries.find("Now", 1).single(), emptyList())!!
        assertEquals("time.Now()" to 0, now.text to now.caretFromEnd)
        val duration = GoCatalogueInsertion.of(entries.find("Dur", 1).single(), emptyList())!!
        assertEquals("time.Duration" to 0, duration.text to duration.caretFromEnd)
    }

    @Test fun aTypeWhereAValueIsExpectedIsALiteral() {
        val entries = GoSymbolIndex(listOf(GoModuleSymbols("std", true, listOf(GoPackageSymbols("net/http", "http", listOf(
            GoSymbol("Client", GoDeclarationKind.STRUCT, null), GoSymbol("Header", GoDeclarationKind.TYPE, "map[string][]string"),
            GoSymbol("Handler", GoDeclarationKind.INTERFACE, null), GoSymbol("ConnState", GoDeclarationKind.TYPE, "int"), GoSymbol("Get", GoDeclarationKind.FUNCTION, "(url string) (*Response, error)"),
        ))))))
        fun written(name: String, literal: Boolean): String = GoCatalogueInsertion.of(entries.find(name, 1).single(), emptyList(), literal)!!.let { "${it.text} caret-${it.caretFromEnd}" }
        // the name is found whatever the case of what is typed: `client` is `http.Client` (reported by the user)
        assertEquals("http.Client{} caret-1", written("client", true))
        assertEquals("http.Header{} caret-1", written("Header", true))
        assertEquals("http.Client caret-0", written("client", false))
        // no literal of an interface or of a number, and a function is called
        assertEquals("http.Handler caret-0", written("Handler", true))
        assertEquals("http.ConnState caret-0", written("ConnState", true))
        assertEquals("http.Get() caret-1", written("Get", true))
    }
}
