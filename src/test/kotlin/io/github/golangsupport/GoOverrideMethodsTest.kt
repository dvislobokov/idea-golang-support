package io.github.golangsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.lang.GoOverrideMethodsHandler

/** Ctrl+O on a struct with embedded fields: wrappers of the promoted methods it does not declare, after its last method. */
class GoOverrideMethodsTest : BasePlatformTestCase() {
    private val source = """
        package store

        type Base struct{}

        func (b *Base) Close() error { return nil }

        func (b Base) Name() string { return "base" }

        type Logger interface {
        	Log(format string, args ...any)
        }

        type Server struct {
        	*Base
        	Logger
        	port int
        }

        func (s *Server) Name() string { return "server" }
    """.trimIndent() + "\n"

    override fun tearDown() {
        try {
            GoOverrideMethodsHandler.chooser = null
        } finally {
            super.tearDown()
        }
    }

    private fun configure(text: String = source) = myFixture.configureByText("server.go", text.replace("type Server struct", "type <caret>Server struct"))

    fun testOverridesEveryPromotedMethodNotDeclared() {
        configure()
        val handler = GoOverrideMethodsHandler()
        assertTrue(handler.isValidFor(myFixture.editor, myFixture.file))
        handler.invoke(project, myFixture.editor, myFixture.file)
        myFixture.checkResult(
            source + "\nfunc (s *Server) Close() error {\n\treturn s.Base.Close()\n}\n\nfunc (s *Server) Log(format string, args ...any) {\n\ts.Logger.Log(format, args...)\n}\n",
        )
    }

    fun testOnlyTheChosenMethod() {
        GoOverrideMethodsHandler.chooser = { methods -> methods.filter { it.name == "Log" } }
        configure()
        GoOverrideMethodsHandler().invoke(project, myFixture.editor, myFixture.file)
        myFixture.checkResult(source + "\nfunc (s *Server) Log(format string, args ...any) {\n\ts.Logger.Log(format, args...)\n}\n")
    }

    fun testUnavailableWithoutEmbeddedFields() {
        configure("package store\n\ntype Server struct {\n\tport int\n}\n")
        assertFalse(GoOverrideMethodsHandler().isValidFor(myFixture.editor, myFixture.file))
    }
}
