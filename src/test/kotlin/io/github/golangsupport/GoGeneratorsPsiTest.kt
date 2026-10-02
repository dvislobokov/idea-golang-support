package io.github.golangsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.lang.GoDeclarationInfo
import io.github.golangsupport.lang.GoGenerators
import io.github.golangsupport.lang.psi.GoFile

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
}
