package io.github.golangsupport

import io.github.golangsupport.lang.GoDocComments
import io.github.golangsupport.lang.GoGenerators
import io.github.golangsupport.lang.GoPostfixExpressions
import io.github.golangsupport.lang.GoStatements
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoGeneratorsTest {
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

    @Test fun returnsAndCalls() {
        assertEquals("return 0, nil", GoGenerators.returnStatement("(items []int, discount float64) (int, error)"))
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
}
