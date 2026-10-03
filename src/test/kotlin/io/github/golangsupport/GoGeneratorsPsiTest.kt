package io.github.golangsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.lang.GoDeclarationInfo
import io.github.golangsupport.lang.GoGenerateEnumStringAction
import io.github.golangsupport.lang.GoGenerateEqualAction
import io.github.golangsupport.lang.GoGenerators
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoTypeSpec

/** The generators over declarations of the PSI ([GoDeclarationInfo.of]): what Alt+Insert writes for a struct, an interface, a function. */
class GoGeneratorsPsiTest : BasePlatformTestCase() {
    private val source = """
        package store

        type Server struct {
        	port    int
        	Name    string
        	UserID  int64 `db:"user_id"`
        	handler func() error
        }

        type Store interface {
        	Get(key string) (string, error)
        	Put(key, value string) error
        }

        func Total(items []int, discount float64) (int, error) {
        	return 0, nil
        }

        func (s *Server) Start() {
        }
    """.trimIndent()


    private lateinit var file: GoFile

    override fun setUp() {
        super.setUp()
        file = myFixture.addFileToProject("server.go", source) as GoFile
    }

    private fun declaration(name: String): GoDeclarationInfo =
        GoDeclarationInfo.topLevel(file).first { it.name == name }

    private val server get() = declaration("Server")
    private val store get() = declaration("Store")
    private val total get() = declaration("Total")

    fun testConstructorAndAccessors() {
        val fields = GoGenerators.fields(server).take(2)
        assertEquals("func NewServer(port int, name string) *Server {\n\treturn &Server{port: port, Name: name}\n}\n", GoGenerators.constructor(server, fields))
        assertEquals("func (s *Server) Port() int {\n\treturn s.port\n}\n", GoGenerators.getters(server, fields.take(1)))
        assertEquals("func (s *Server) SetPort(port int) {\n\ts.port = port\n}\n", GoGenerators.setters(server, fields.take(1)))
        assertEquals("func (s Server) String() string {\n\treturn fmt.Sprintf(\"Server{port: %v, Name: %v}\", s.port, s.Name)\n}\n", GoGenerators.stringMethod(server, fields))
        assertEquals(4, GoGenerators.fields(server).size)
    }

    fun testStructTags() {
        val body = server.body!!
        val text = source.substring(body.startOffset, body.endOffset)
        val tagged = GoGenerators.withTags(text, GoGenerators.fields(server), body.startOffset, listOf("json", "db"), GoGenerators.TagCase.SNAKE, omitEmpty = true)
        // an unexported field gets no json tag, but a db one; an existing tag keeps its key and gets the missing one
        assertTrue(tagged, tagged.contains("port    int `db:\"port\"`"))
        assertTrue(tagged, tagged.contains("Name    string `json:\"name,omitempty\" db:\"name\"`"))
        assertTrue(tagged, tagged.contains("UserID  int64 `db:\"user_id\" json:\"user_id,omitempty\"`"))
    }

    fun testInterfaceStubs() {
        val stubs = GoGenerators.interfaceStubs("Server", store)
        assertEquals(
            "func (s *Server) Get(key string) (string, error) {\n\tpanic(\"not implemented\")\n}\n\nfunc (s *Server) Put(key, value string) error {\n\tpanic(\"not implemented\")\n}\n",
            stubs,
        )
        assertEquals(listOf("Put"), GoGenerators.missingMethods(store, setOf("Get")).children.map { it.name })
    }

    fun testTestsOfAFunctionAndOfAMethod() {
        val test = GoGenerators.testFunction(total, "store")
        assertTrue(test, test.startsWith("func TestTotal(t *testing.T) {"))
        assertTrue(test, test.contains("\t\titems []int\n\t\tdiscount float64\n\t\twant0 int\n\t\twant1 error\n"))
        assertTrue(test, test.contains("got0, got1 := Total(tt.items, tt.discount)"))
        assertTrue(GoGenerators.testFunction(declaration("Start"), "store").startsWith("func TestServer_Start(t *testing.T) {"))
        assertEquals("return 0, nil", GoGenerators.returnStatement(total.signature))
    }

    private fun spec(file: GoFile, name: String): GoTypeSpec = file.types.first { it.name == name }

    fun testEnumStringFromTheConstantsOfThePackage() {
        val colors = myFixture.addFileToProject("color.go", """
            package store

            type Color int

            const (
            	Red Color = iota
            	Green
            	Blue
            	Crimson = Red
            	count = 3
            )

            type Mode uint8

            func (m *Mode) Set() {}

            const Fast, Slow Mode = 0, 1

            type Flags int

            const (
            	Read Flags = 1 << iota
            	Write
            	Exec
            )

            type Named int

            const One Named = 1

            func (Named) String() string { return "" }

            type NoConstants int
        """.trimIndent()) as GoFile
        // equal values are one member, the first name wins; an untyped constant is no member
        assertEquals(
            "func (c Color) String() string {\n\tswitch c {\n\tcase Red:\n\t\treturn \"Red\"\n\tcase Green:\n\t\treturn \"Green\"\n\tcase Blue:\n\t\treturn \"Blue\"\n" +
                "\t}\n\treturn fmt.Sprintf(\"Color(%d)\", int(c))\n}\n",
            GoGenerateEnumStringAction.code(spec(colors, "Color")),
        )
        // another file of the package counts too
        myFixture.addFileToProject("more.go", "package store\n\nconst Yellow Color = 7\n")
        assertEquals(setOf("Red", "Green", "Blue", "Yellow"), GoGenerateEnumStringAction.members(spec(colors, "Color"))?.map { it.name }?.toSet())
        // the receiver name of the methods there are, the underlying type in the conversion
        val mode = GoGenerateEnumStringAction.code(spec(colors, "Mode"))!!
        assertTrue(mode, mode.startsWith("func (m Mode) String() string {") && mode.contains("uint8(m)"))
        assertNull("bit flags", GoGenerateEnumStringAction.code(spec(colors, "Flags")))
        assertNull("String() is there", GoGenerateEnumStringAction.code(spec(colors, "Named")))
        assertNull("no constants", GoGenerateEnumStringAction.code(spec(colors, "NoConstants")))
        assertNull("a struct", GoGenerateEnumStringAction.code(spec(file, "Server")))
    }

    fun testEqualKindsOfTheFields() {
        val types = myFixture.addFileToProject("record.go", """
            package store

            type ID int

            type Record struct {
            	id      ID
            	Name    string
            	Data    []byte
            	Tags    []string
            	Matrix  [][]int
            	Counts  map[string]int
            	Groups  map[string][]int
            	Owner   *Server
            	Handler func()
            	Inner   struct{ A int }
            }
        """.trimIndent()) as GoFile
        val kinds = GoGenerateEqualAction.kinds(spec(types, "Record"))
        val k = GoGenerators.EqualKind.entries.associateBy { it.name }
        assertEquals(
            mapOf(
                "id" to k["OPERATOR"], "Name" to k["OPERATOR"], "Data" to k["BYTES"], "Tags" to k["SLICES"], "Matrix" to k["DEEP"], "Counts" to k["MAPS"],
                "Groups" to k["DEEP"], "Owner" to k["OPERATOR"], "Handler" to k["DEEP"], "Inner" to k["OPERATOR"],
            ),
            kinds,
        )
    }
}
