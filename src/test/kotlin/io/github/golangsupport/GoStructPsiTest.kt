package io.github.golangsupport

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import io.github.golangsupport.catalogue.GoInterfaceRendering
import io.github.golangsupport.project.api.GoPackageResolver
import io.github.golangsupport.project.api.GoToolchainInfo
import io.github.golangsupport.project.api.GoToolchainProvider
import io.github.golangsupport.project.impl.DefaultGoToolchainProvider
import io.github.golangsupport.semantic.types.GoTypeRenderer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import io.github.golangsupport.catalogue.GoSourceScanner
import io.github.golangsupport.lang.GenerateContext
import io.github.golangsupport.lang.GoFieldAlignment
import io.github.golangsupport.lang.GoGenerateStructTagsAction
import io.github.golangsupport.lang.GoGenerators
import io.github.golangsupport.lang.GoInterfaceChooser
import io.github.golangsupport.lang.GoStructPsi
import io.github.golangsupport.lang.GoStructTags
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.semantic.types.GoPointerType

/** Structs, struct tags, field alignment and Implement Interface from the PSI of go-psi (migration step 9). */
class GoStructPsiTest : BasePlatformTestCase() {
    private val server = """
        package store

        // Server serves.
        type Server struct {
        	port    int
        	Name    string
        	UserID  int64 `db:"user_id"`
        	handler func() error
        }
    """.trimIndent()

    private fun spec(file: GoFile, name: String): GoTypeSpec = file.types.first { it.name == name }

    fun testFieldsWithEmbeddedAndTags() {
        val file = myFixture.configureByText("model.go", """
            package model

            import "sync"

            type Base struct{ ID int }

            type User struct {
            	// Name is shown.
            	Name, Nick string `json:"name"`
            	*Base
            	sync.Mutex
            	Tags []string "json:\"tags\""
            	Inline struct {
            		X int
            	}
            }
        """.trimIndent()) as GoFile
        val fields = GoStructPsi.fields(GoStructPsi.structOf(spec(file, "User"))!!)
        assertEquals(
            listOf(
                "Name string `json:\"name\"` embedded=false exported=true",
                "Nick string `json:\"name\"` embedded=false exported=true",
                "Base *Base null embedded=true exported=true",
                "Mutex sync.Mutex null embedded=true exported=true",
                "Tags []string \"json:\\\"tags\\\"\" embedded=false exported=true",
                "Inline struct { X int } null embedded=false exported=true",
            ),
            fields.map { "${it.name} ${it.typeText} ${it.tag} embedded=${it.embedded} exported=${it.exported}" },
        )
        assertEquals("Name is shown.\n", fields[0].doc)
        assertEquals(setOf("json"), fields[0].tagKeys)
        assertTrue(fields[2].type is GoPointerType)
        assertEquals("[]string", GoTypeRenderer.render(fields[4].type))
    }

    /** The declarations [GoStructPsi.infoOf] makes give the generators the same text as those the scanner of the catalogue reads from files. */
    fun testGeneratorsGetTheSameTextFromThePsi() {
        val file = myFixture.configureByText("server.go", server) as GoFile
        val scanned = GoSourceScanner.scan(server).declarations.first { it.name == "Server" }
        val info = GoStructPsi.infoOf(spec(file, "Server"))!!
        assertEquals(scanned.range, info.range)
        assertEquals(scanned.body, info.body)
        assertEquals(scanned.nameRange, info.nameRange)
        val fields = GoGenerators.fields(info)
        assertEquals(GoGenerators.fields(scanned).map { it.name to it.signature }, fields.map { it.name to it.signature })
        assertEquals(GoGenerators.constructor(scanned), GoGenerators.constructor(info))
        assertEquals(GoGenerators.getters(scanned), GoGenerators.getters(info, fields))
        assertEquals(GoGenerators.setters(scanned), GoGenerators.setters(info, fields))
        assertEquals(GoGenerators.stringMethod(scanned), GoGenerators.stringMethod(info, fields))
    }

    fun testTheStructAtTheCaret() {
        myFixture.configureByText("server.go", server.replace("port    int", "po<caret>rt    int") + "\n\nfunc f() {\n\ttype Local struct{ A int }\n}\n")
        val context = GenerateContext(project, myFixture.editor, myFixture.file as GoFile)
        assertEquals("Server", context.structAtCaret?.name)
        // on the keyword, right after the closing brace, inside a type of a function: as the scanner had it
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("type Server"))
        assertEquals("Server", context.typeAtCaret?.name)
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("}\n\nfunc") + 1)
        assertEquals("Server", context.typeAtCaret?.name)
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("Local") + 2)
        assertNull(context.typeAtCaret)
    }

    fun testTagContextFromThePsi() {
        val text = "package p\n\ntype User struct {\n\tName, Alias string `json:\"na\n\tAge int\n}\n\nvar s = `json`\n"
        val file = myFixture.configureByText("user.go", text)
        // a tag still being typed runs to the end of the file and is a tag all the same
        val (open, field) = GoStructTags.tagAt(file, text.indexOf("na\n") + 2)!!
        assertEquals(text.indexOf('`'), open)
        assertEquals("Name", field)
        val context = GoStructTags.contextOf(text.substring(open + 1, text.indexOf("na\n") + 2)) as GoStructTags.Context.AtValue
        assertEquals("json", context.key)
        assertEquals("na", context.prefix)

        val closed = "package p\n\ntype User struct {\n\t*Base `js`\n\tAge int `json:\"age\"`\n}\n\nvar s = `json`\n"
        val closedFile = myFixture.configureByText("closed.go", closed)
        assertEquals("Base", GoStructTags.tagAt(closedFile, closed.indexOf("`js") + 3)!!.second)
        assertEquals("Age", GoStructTags.tagAt(closedFile, closed.indexOf("\"age") + 2)!!.second)
        // after the closing backquote, before the opening one, and a raw string that is no tag
        assertNull(GoStructTags.tagAt(closedFile, closed.indexOf("\"age\"`") + 6))
        assertNull(GoStructTags.tagAt(closedFile, closed.indexOf("`js")))
        assertNull(GoStructTags.tagAt(closedFile, closed.lastIndexOf("json") + 2))

        val anonymous = "package p\n\nfunc f() {\n\tvar body struct {\n\t\tID int `js`\n\t}\n\t_ = body\n}\n"
        val anonymousFile = myFixture.configureByText("anonymous.go", anonymous)
        assertEquals("ID", GoStructTags.tagAt(anonymousFile, anonymous.indexOf("`js") + 3)!!.second)
    }

    fun testTagEditsFromThePsi() {
        val text = server.replace("handler func() error", "handler func() error // the handler\n\tLegacy  string \"xml:\\\"legacy\\\"\"\n\tA, B    int")
        val file = myFixture.configureByText("server.go", text) as GoFile
        val fields = GoStructPsi.fields(GoStructPsi.structOf(spec(file, "Server"))!!).filter { !it.embedded }
        val edits = GoGenerateStructTagsAction.tagEdits(fields, listOf("json", "db"), GoGenerators.TagCase.SNAKE, omitEmpty = true)
        val document = StringBuilder(text)
        for ((range, replacement) in edits.sortedByDescending { it.first.startOffset }) document.replace(range.startOffset, range.endOffset, replacement)
        val tagged = document.toString()
        // as the generator of the scanner writes them
        assertTrue(tagged, tagged.contains("port    int `db:\"port\"`\n"))
        assertTrue(tagged, tagged.contains("Name    string `json:\"name,omitempty\" db:\"name\"`\n"))
        assertTrue(tagged, tagged.contains("UserID  int64 `db:\"user_id\" json:\"user_id,omitempty\"`\n"))
        // the PSI knows better: the tag goes before a trailing comment, an interpreted tag becomes a raw one, `A, B` is one declaration
        assertTrue(tagged, tagged.contains("handler func() error `db:\"handler\"` // the handler\n"))
        assertTrue(tagged, tagged.contains("Legacy  string `xml:\"legacy\" json:\"legacy,omitempty\" db:\"legacy\"`\n"))
        assertTrue(tagged, tagged.contains("A, B    int `json:\"a,omitempty\" db:\"a\"`\n"))
        assertTrue(GoGenerateStructTagsAction.tagEdits(fields.take(1), listOf("db"), GoGenerators.TagCase.SNAKE, false).isNotEmpty())
        assertTrue(GoGenerateStructTagsAction.tagEdits(fields.filter { it.name == "UserID" }, listOf("db"), GoGenerators.TagCase.SNAKE, false).isEmpty())
    }

    fun testFieldAlignmentSizesFromTheTypes() {
        // types of another file of the package: the text path knew only those of the same file and a table of the standard library
        myFixture.addFileToProject("store/other.go", "package store\n\ntype Weight int32\n\ntype Pair struct {\n\tA, B int16\n}\n\ntype Inner struct {\n\tA bool\n\tB int64\n}\n")
        val file = myFixture.addFileToProject("store/order.go", """
            package store

            type Order struct {
            	Paid   bool
            	Total  int64
            	Weight Weight
            	Inner
            	Open   bool
            	Next   *Order
            	Sizes  [3]Pair
            	Keys   map[string]int
            	Ready, Done bool
            }

            type Unknown struct {
            	A bool
            	B nowhere.Thing
            }
        """.trimIndent()) as GoFile
        val result = GoFieldAlignment.analyze(GoStructPsi.structOf(spec(file, "Order"))!!)!!
        // Paid 1 [7] Total 8 Weight 4 [4] Inner 16 Open 1 [7] Next 8 Sizes 12 [4] Keys 8 Ready, Done 2 [6] = 88
        assertEquals(88, result.currentSize)
        // Inner, Total, Next, Keys, Weight, Sizes, Ready, Done, Paid, Open: 16 + 8 + 8 + 8 + 4 + 12 + 2 + 1 + 1 = 60 -> 64
        assertEquals(64, result.optimalSize)
        assertEquals(listOf("Inner", "Total", "Next", "Keys", "Weight", "Sizes", "Ready,", "Paid", "Open"), result.fields.map { it.lines.last().substringBefore(' ') })
        val sizes = result.fields.first { it.lines.last().startsWith("Sizes") }.layout
        assertEquals(12 to 2, sizes.size to sizes.align)
        assertNull(GoFieldAlignment.analyze(GoStructPsi.structOf(spec(file, "Unknown"))!!))
    }

    private fun lines(plan: GoInterfaceChooser.Plan): List<String> = plan.stubs.lines().filter { it.startsWith("func") }

    /**
     * Implement Interface on a project interface, through [GoInterfaceChooser.plan] as the popup runs it: the method set of the type
     * checker with the embedded interfaces unfolded (two levels, `error` among them), the methods the type has (declared in another file,
     * promoted from an embedded field) left out, the receiver style of the type kept. The packages of the light fixture live in a
     * `temp://` file system, which the module graph does not read: an interface of another package of the project is not resolved
     * here (seen with a probe), so the qualification by the target's imports is checked on the standard library below.
     */
    fun testImplementInterfaceFromTheMethodSet() {
        myFixture.addFileToProject("store/api.go", """
            package store

            type Item struct{}

            type Getter interface {
            	Get(id int) (*Item, error)
            }

            type Sorter interface {
            	Len() int
            	Less(i, j int) bool
            	Swap(i, j int)
            }

            type Counter interface {
            	Len() int
            }

            type Lister interface {
            	Getter
            	Sorter
            }

            type Store interface {
            	Lister
            	error
            	Put(items ...Item) map[string]Getter
            	Each(f func(Item) bool, done chan<- struct{}) any
            }
        """.trimIndent())
        myFixture.addFileToProject("store/methods.go", "package store\n\nfunc (m *Memory) Len() int { return len(m.items) }\n")
        val target = myFixture.addFileToProject("store/memory.go", """
            package store

            type Memory struct{ items map[int]string }

            func (m *Memory) Error() string { return "" }

            type Wrapped struct {
            	*Memory
            }

            type Slice []int

            func (s Slice) Len() int { return len(s) }
        """.trimIndent()) as GoFile
        val directory = target.virtualFile.parent
        val plan = GoInterfaceChooser.plan(spec(target, "Memory"), target, null, directory, "Store")!!
        assertEquals(
            listOf(
                "func (m *Memory) Put(items ...Item) map[string]Getter {",
                "func (m *Memory) Each(f func(Item) bool, done chan<- struct{}) any {",
                "func (m *Memory) Get(id int) (*Item, error) {",
                "func (m *Memory) Less(i int, j int) bool {",
                "func (m *Memory) Swap(i int, j int) {",
            ),
            lines(plan),
        )
        assertEquals(emptyList<String>(), plan.imports)
        // Error and Len are promoted from *Memory
        assertEquals(listOf("func (w *Wrapped) Less(i int, j int) bool {", "func (w *Wrapped) Swap(i int, j int) {"), lines(GoInterfaceChooser.plan(spec(target, "Wrapped"), target, null, directory, "Sorter")!!))
        // a type with value receivers keeps them
        assertEquals(listOf("func (s Slice) Less(i int, j int) bool {", "func (s Slice) Swap(i int, j int) {"), lines(GoInterfaceChooser.plan(spec(target, "Slice"), target, null, directory, "Sorter")!!))
        // nothing is missing: nothing to write
        assertEquals("", GoInterfaceChooser.plan(spec(target, "Memory"), target, null, directory, "Counter")!!.stubs)
    }

    /**
     * The method sets of interfaces of the standard library, read from the PSI of GOROOT (the toolchain pinned without running `go`, as
     * go-psi tests do), rendered for a file that imports `io` under an alias and does not import `fmt`. Skipped without a GOROOT.
     * [GoInterfaceSources.methodsFor] itself reads GOROOT this way only when it is in the indices (library roots, off in unit tests);
     * here the rendering is called on the spec directly.
     */
    fun testStandardLibraryInterfaces() {
        val goroot = pinGoroot() ?: return println("GOROOT not found: skipped")
        val target = myFixture.addFileToProject("app/app.go", "package app\n\nimport stdio \"io\"\n\nvar _ stdio.Reader\n\ntype Buffer struct{}\n") as GoFile
        fun methods(path: String, name: String): List<String> {
            val directory = GoPackageResolver.getInstance(project).resolveImport(path, target.virtualFile).packageOrNull?.directory ?: error("$path is not in $goroot")
            val spec = directory.children.filter { it.name.endsWith(".go") && !it.name.endsWith("_test.go") }.sortedBy { it.name }
                .firstNotNullOf { (PsiManager.getInstance(project).findFile(it) as? GoFile)?.types?.firstOrNull { type -> type.name == name } }
            return GoInterfaceRendering.methodsOf(spec, target).map { it.name + it.signature + it.imports.joinToString("") { path -> " +$path" } }
        }
        assertEquals(listOf("Read(p []byte) (n int, err error)"), methods("io", "Reader"))
        assertEquals(listOf("String() string"), methods("fmt", "Stringer"))
        assertEquals(listOf("Len() int", "Less(i int, j int) bool", "Swap(i int, j int)"), methods("sort", "Interface"))
        // heap.Interface embeds sort.Interface, of another package
        assertEquals(listOf("Push(x any)", "Pop() any", "Len() int", "Less(i int, j int) bool", "Swap(i int, j int)"), methods("container/heap", "Interface"))
        assertEquals(listOf("Read(p []byte) (n int, err error)", "Write(p []byte) (n int, err error)", "Close() error"), methods("io", "ReadWriteCloser"))
        // the alias of the target file; a package it does not import is named and returned to be imported
        assertEquals(listOf("WriteTo(w stdio.Writer) (n int64, err error)"), methods("io", "WriterTo"))
        assertEquals(listOf("Format(f fmt.State, verb rune) +fmt"), methods("fmt", "Formatter"))
    }

    /** The toolchain of GOROOT (`-Dgopsi.goroot`, `GOROOT`, the default install), without a `go` binary to run; null when there is none. */
    private fun pinGoroot(): Path? {
        val goroot = listOfNotNull(System.getProperty("gopsi.goroot"), System.getenv("GOROOT"), "C:\\Program Files\\Go", "/usr/local/go")
            .map { Paths.get(it) }.firstOrNull { Files.isDirectory(it.resolve("src")) } ?: return null
        val toolchain = GoToolchainInfo(goroot, DefaultGoToolchainProvider.readVersion(goroot), emptyList(), null, "linux", "amd64", true)
        ApplicationManager.getApplication().replaceService(GoToolchainProvider::class.java, object : GoToolchainProvider {
            override fun toolchainFor(project: Project?): GoToolchainInfo = toolchain
        }, testRootDisposable)
        VfsRootAccess.allowRootAccess(testRootDisposable, goroot.toString())
        return goroot
    }
}
