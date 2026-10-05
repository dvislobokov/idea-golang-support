package io.github.golangsupport

import io.github.golangsupport.ide.intentions.GoEnumConstants
import io.github.golangsupport.lang.GoDocComments
import io.github.golangsupport.semantic.types.GoConstant
import java.math.BigInteger
import io.github.golangsupport.lang.GoGenerators
import io.github.golangsupport.lang.GoDeclarationInfo
import io.github.golangsupport.lang.GoDeclarationKind
import com.intellij.openapi.util.TextRange
import io.github.golangsupport.lang.GoPostfixExpressions
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

    @Test fun enumString() {
        assertEquals(
            "func (c Color) String() string {\n\tswitch c {\n\tcase Red:\n\t\treturn \"Red\"\n\tcase Green:\n\t\treturn \"Green\"\n\t}\n" +
                "\treturn fmt.Sprintf(\"Color(%d)\", int(c))\n}\n",
            GoGenerators.enumStringMethod("Color", "c", listOf("Red", "Green"), "int"),
        )
        // the receiver the type's methods use, and the conversion to the underlying type (`%d` on the value would call String() again)
        val custom = GoGenerators.enumStringMethod("Level", "lvl", listOf("Debug"), "uint8")
        assertTrue(custom, custom.startsWith("func (lvl Level) String() string {\n\tswitch lvl {"))
        assertTrue(custom, custom.contains("fmt.Sprintf(\"Level(%d)\", uint8(lvl))"))
    }

    @Test fun enumFlagsAndEqualValues() {
        fun ints(vararg values: Long) = values.map { GoConstant.Int(BigInteger.valueOf(it)) }
        assertTrue(GoEnumConstants.isFlags(ints(1, 2, 4, 8)))
        assertTrue(GoEnumConstants.isFlags(ints(0, 1, 2, 4)))
        assertFalse(GoEnumConstants.isFlags(ints(0, 1, 2, 3)))
        assertFalse(GoEnumConstants.isFlags(ints(1, 2)))
        assertFalse(GoEnumConstants.isFlags(listOf(GoConstant.Int(BigInteger.ONE), null, GoConstant.Int(BigInteger.TWO))))
    }

    @Test fun equalMethod() {
        val kinds = GoGenerators.EqualKind.entries.map { it.name.lowercase() to it }
        val code = GoGenerators.equalMethod("T", "t", false, kinds)
        assertEquals(
            "func (t T) Equal(other T) bool {\n\treturn t.operator == other.operator &&\n\t\tbytes.Equal(t.bytes, other.bytes) &&\n" +
                "\t\tslices.Equal(t.slices, other.slices) &&\n\t\tmaps.Equal(t.maps, other.maps) &&\n\t\tt.time.Equal(other.time) &&\n" +
                "\t\treflect.DeepEqual(t.deep, other.deep)\n}\n",
            code,
        )
        assertEquals(listOf("bytes", "maps", "reflect", "slices"), GoGenerators.equalImports(kinds.map { it.second }))
        assertEquals(
            "func (p *Point) Equal(other *Point) bool {\n\treturn p.X == other.X && p.Y == other.Y\n}\n",
            GoGenerators.equalMethod("Point", "p", true, listOf("X" to GoGenerators.EqualKind.OPERATOR, "Y" to GoGenerators.EqualKind.OPERATOR)),
        )
        assertEquals("func (e Empty) Equal(other Empty) bool {\n\treturn true\n}\n", GoGenerators.equalMethod("Empty", "e", false, emptyList()))
        // a receiver named `other` does not clash with the parameter
        assertTrue(GoGenerators.equalMethod("T", "other", false, emptyList()).startsWith("func (other T) Equal(o T) bool"))
        assertEquals(emptyList<String>(), GoGenerators.equalImports(listOf(GoGenerators.EqualKind.OPERATOR, GoGenerators.EqualKind.TIME)))
        assertFalse(GoGenerators.EqualKind.DEEP.byDefault)
        assertTrue(GoGenerators.EqualKind.SLICES.byDefault)
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

    @Test fun delegatingMethods() {
        assertEquals(
            "func (s *Server) Close() error {\n\treturn s.Conn.Close()\n}\n",
            GoGenerators.delegatingMethod("Server", "s", true, "Conn", "Close", "() error"),
        )
        // grouped names are kept as written; a variadic parameter is passed on with `...`; no results: no return
        assertEquals(
            "func (s Server) Log(level, format string, args ...any) {\n\ts.Logger.Log(level, format, args...)\n}\n",
            GoGenerators.delegatingMethod("Server", "s", false, "Logger", "Log", "(level, format string, args ...any)"),
        )
        // unnamed and blank parameters get names; a parameter named like the receiver renames the receiver
        assertEquals(
            "func (recv *T) Write(arg0 []byte, s string) (n int, err error) {\n\treturn recv.W.Write(arg0, s)\n}\n",
            GoGenerators.delegatingMethod("T", "s", true, "W", "Write", "(_ []byte, s string) (n int, err error)"),
        )
    }

    @Test fun methods() {
        val panic = " {\n\tpanic(\"not implemented\")\n}\n"
        assertEquals("func (s *Server) Stop()$panic", GoGenerators.method("Server", "s", true, "Stop", "", ""))
        assertEquals(
            "func (s Server) Get(ctx context.Context, id string) (Item, error)$panic",
            GoGenerators.method("Server", "s", false, "Get", "ctx context.Context, id string", "Item, error"),
        )
        assertEquals("func (s *S) N() (n int)$panic", GoGenerators.method("S", "s", true, "N", "", "n int"))
        assertEquals("func (s *S) F() func() error$panic", GoGenerators.method("S", "s", true, "F", "", "func() error"))
        assertEquals("func (s *S) P() (int, error)$panic", GoGenerators.method("S", "s", true, "P", "", "(int, error)"))
    }

    @Test fun untestedFunctions() {
        fun f(name: String, receiver: String? = null) = GoDeclarationInfo(
            if (receiver == null) GoDeclarationKind.FUNCTION else GoDeclarationKind.METHOD, name, TextRange(0, 0), TextRange(0, 0), receiver, "()",
        )
        val functions = listOf(f("Sum"), f("helper"), f("init"), f("Start", "Server"), f("Stop", "Server"), f("TestLike"))
        val tests = listOf("package p\n\nfunc TestSum(t *testing.T) {}\n", "func TestServer_Stop(t *testing.T) {}")
        assertEquals(listOf("Start"), GoGenerators.untested(functions, tests).map { it.name })
        assertEquals("TestServer_Start", GoGenerators.testName(f("Start", "Server")))
    }
}
