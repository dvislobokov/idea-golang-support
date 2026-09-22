package io.github.golangsupport

import io.github.golangsupport.lang.GoDeclarationKind
import io.github.golangsupport.lang.GoDeclarations
import io.github.golangsupport.lang.GoDocComments
import io.github.golangsupport.lang.GoGenerators
import io.github.golangsupport.lang.GoPostfixExpressions
import io.github.golangsupport.lang.GoStatements
import io.github.golangsupport.lang.GoStatementsOfError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoGeneratorsTest {
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

    private val structure = GoDeclarations.scan(source)
    private val server = structure.declarations.first { it.name == "Server" }
    private val store = structure.declarations.first { it.name == "Store" }
    private val total = structure.declarations.first { it.name == "Total" }

    @Test fun names() {
        assertEquals("s", GoGenerators.receiverName("Server"))
        assertEquals("hc", GoGenerators.receiverName("HTTPClient"))
        assertEquals("port", GoGenerators.parameterName("Port"))
        assertEquals("id", GoGenerators.parameterName("ID"))
        assertEquals("httpClient", GoGenerators.parameterName("HTTPClient"))
        assertEquals("type_", GoGenerators.parameterName("Type"))
        assertEquals("user_id", GoGenerators.snakeCase("UserID"))
        assertEquals("http_client", GoGenerators.snakeCase("HTTPClient"))
        assertEquals("name", GoGenerators.snakeCase("Name"))
        assertEquals("ID", GoGenerators.accessorName("id"))
        assertEquals("Port", GoGenerators.accessorName("port"))
    }

    @Test fun constructorAndAccessors() {
        val fields = GoGenerators.fields(server).take(2)
        assertEquals("func NewServer(port int, name string) *Server {\n\treturn &Server{port: port, Name: name}\n}\n", GoGenerators.constructor(server, fields))
        assertEquals("func (s *Server) Port() int {\n\treturn s.port\n}\n", GoGenerators.getters(server, fields.take(1)))
        assertEquals("func (s *Server) SetPort(port int) {\n\ts.port = port\n}\n", GoGenerators.setters(server, fields.take(1)))
        assertEquals("func (s Server) String() string {\n\treturn fmt.Sprintf(\"Server{port: %v, Name: %v}\", s.port, s.Name)\n}\n", GoGenerators.stringMethod(server, fields))
        assertEquals(4, GoGenerators.fields(server).size)
    }

    @Test fun structTags() {
        val body = server.body!!
        val text = source.substring(body.startOffset, body.endOffset)
        val tagged = GoGenerators.withTags(text, GoGenerators.fields(server), body.startOffset, listOf("json", "db"), GoGenerators.TagCase.SNAKE, omitEmpty = true)
        // an unexported field gets no json tag, but a db one; an existing tag keeps its key and gets the missing one
        assertTrue(tagged, tagged.contains("port    int `db:\"port\"`"))
        assertTrue(tagged, tagged.contains("Name    string `json:\"name,omitempty\" db:\"name\"`"))
        assertTrue(tagged, tagged.contains("UserID  int64 `db:\"user_id\" json:\"user_id,omitempty\"`"))
    }

    @Test fun interfaceStubs() {
        val stubs = GoGenerators.interfaceStubs("Server", store)
        assertEquals(
            "func (s *Server) Get(key string) (string, error) {\n\tpanic(\"not implemented\")\n}\n\nfunc (s *Server) Put(key, value string) error {\n\tpanic(\"not implemented\")\n}\n",
            stubs,
        )
        assertEquals(listOf("Put"), GoGenerators.missingMethods(store, setOf("Get")).children.map { it.name })
    }

    @Test fun testsReturnsAndCalls() {
        val test = GoGenerators.testFunction(total, "store")
        assertTrue(test, test.startsWith("func TestTotal(t *testing.T) {"))
        assertTrue(test, test.contains("\t\titems []int\n\t\tdiscount float64\n\t\twant0 int\n\t\twant1 error\n"))
        assertTrue(test, test.contains("got0, got1 := Total(tt.items, tt.discount)"))
        val method = structure.declarations.first { it.kind == GoDeclarationKind.METHOD }
        assertTrue(GoGenerators.testFunction(method, "store").startsWith("func TestServer_Start(t *testing.T) {"))
        assertEquals("return 0, nil", GoGenerators.returnStatement(total.signature))
        assertNull(GoGenerators.returnStatement("()"))
        assertEquals(listOf("a", "g(b, c)", "\"x,y\"", "[]int{1, 2}"), GoGenerators.callArguments("f(a, g(b, c), \"x,y\", []int{1, 2})"))
        assertEquals("func add(a any, arg2 int, arg3 string) {\n\tpanic(\"not implemented\")\n}\n", GoGenerators.functionFromCall("add", listOf("a", "2", "\"x\"")))
        assertEquals("func (s *Server) sum() {\n\tpanic(\"not implemented\")\n}\n", GoGenerators.functionFromCall("sum", emptyList(), "Server"))
        assertEquals("func load(path any) (any, error) {\n\tpanic(\"not implemented\")\n}\n", GoGenerators.functionFromCall("load", listOf("path"), results = listOf("data", "err")))
        assertEquals(listOf("data", "err"), GoGenerators.assignedNames("\tdata, err := "))
        assertEquals(listOf("sum"), GoGenerators.assignedNames("\tsum = "))
        assertEquals(emptyList<String>(), GoGenerators.assignedNames("\treturn "))
        assertEquals("add" to null, GoGenerators.calledName("x := add(a, 2)", 7))
        assertEquals("Total" to "s", GoGenerators.calledName("v := s.Total()", 8))
        assertNull(GoGenerators.calledName("x := add", 7))
    }

    @Test fun postfixExpressions() {
        fun before(text: String): String? = GoPostfixExpressions.rangeBefore(text, text.length)?.let { text.substring(it.startOffset, it.endOffset) }
        assertEquals("err", before("\terr."))
        assertEquals("os.Open(name)", before("\tos.Open(name)."))
        assertEquals("items[0].Name", before("x := items[0].Name."))
        assertEquals("f(a, \")\")", before("\tf(a, \")\")."))
        assertEquals("!ok", before("\t!ok."))
        assertEquals("\"text\"", before("\t\"text\"."))
        assertNull(before("\t."))
        assertNull(before("\treturn."))
    }

    @Test fun completeStatement() {
        assertEquals("\tif x > 0 {" to true, GoStatements.complete("\tif x > 0"))
        assertEquals("\tfor {" to true, GoStatements.complete("\tfor"))
        assertEquals("func run() {" to true, GoStatements.complete("func run"))
        assertEquals("\tfmt.Println(x)" to false, GoStatements.complete("\tfmt.Println(x"))
        assertEquals("\tgo func() {" to true, GoStatements.complete("\tgo func"))
        assertNull(GoStatements.complete("\tx := 1"))
        assertNull(GoStatements.complete("\tif x {"))
    }

    @Test fun docComments() {
        val text = "package p\n\n//\nfunc (s *Server) Start() {}\n"
        assertEquals("Start", GoDocComments.nameToComment(text, text.indexOf("//") + 2))
        val type = "package p\n\n\t//\ntype Server struct{}\n"
        assertEquals("Server", GoDocComments.nameToComment(type, type.indexOf("//") + 2))
        val second = "package p\n\n// Server serves.\n//\ntype Server struct{}\n"
        assertNull(GoDocComments.nameToComment(second, second.lastIndexOf("//") + 2))
        val code = "package p\n\nfunc f() {\n\tx := 1 //\n\ty := 2\n}\n"
        assertNull(GoDocComments.nameToComment(code, code.indexOf("//") + 2))
        val body = "package p\n\nfunc f() {\n\t//\n\tx := 1\n}\n"
        assertNull(GoDocComments.nameToComment(body, body.indexOf("//") + 2))
    }

    @Test fun assignedErrors() {
        assertEquals("err", GoStatementsOfError.assignedError("\tdata, err := os.ReadFile(name)"))
        assertEquals("err", GoStatementsOfError.assignedError("\terr = f.Close()"))
        assertEquals("errW", GoStatementsOfError.assignedError("\t_, errW := w.Write(b)"))
        assertNull(GoStatementsOfError.assignedError("\tif err := f(); err != nil {"))
        assertNull(GoStatementsOfError.assignedError("\tx := 1"))
        assertFalse(GoStatementsOfError.assignedError("\tdata := read()") != null)
    }
}
