package io.github.golangsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.debugger.GoFunctionNames
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.run.GoRunConfigurationGenerator
import io.github.golangsupport.run.GoBreakpointLines
import io.github.golangsupport.run.GoDebugPsi
import io.github.golangsupport.run.GoHoverExpression
import io.github.golangsupport.run.GoInlineValues
import io.github.golangsupport.testing.GoSubtests

/** The pure helpers of the debugger and of the test runner on the PSI path: hover expression, inline values, breakpoint lines, subtests. */
class GoDebugPsiTest : BasePlatformTestCase() {
    private fun file(name: String, source: String): GoFile = myFixture.addFileToProject(name, source) as GoFile

    private fun hover(file: GoFile, marker: String, shift: Int = 0): String? {
        val offset = file.text.indexOf(marker) + shift
        require(offset >= shift)
        return GoHoverExpression.rangeAt(file, offset)?.substring(file.text)
    }

    fun testTheHoverExpressionIsTheSmallestEvaluableOne() {
        val file = file("h.go", """
            package a

            type C struct{ c int }
            type B struct{ b []C }

            func g(b B) int { return 0 }

            func f(a B, i int, p *int) int {
            	x := a.b[i].c + *p
            	return g(a) + x
            }
        """.trimIndent())
        assertEquals("a.b[i].c", hover(file, ".c +", 1))
        assertEquals("a.b", hover(file, "b[i]"))
        assertEquals("i", hover(file, "i].c"))
        assertEquals("a.b[i]", hover(file, "[i]"))
        assertEquals("*p", hover(file, "*p"))
        assertEquals("p", hover(file, "*p", 1))
        assertEquals("a declared variable is its own name", "x", hover(file, "x :="))
        assertNull("a call is not evaluated", hover(file, "g(a)"))
        assertEquals("an argument of it is", "a", hover(file, "g(a)", 2))
        assertNull("a type", hover(file, "B struct"))
        assertNull("a function name", hover(file, "f(a B"))
    }

    fun testAMethodCallIsNotEvaluated() {
        val file = file("m.go", "package a\n\ntype S struct{ n int }\n\nfunc (s S) Get() int { return s.n }\n\nfunc f(s S) int {\n\treturn s.Get() + s.n\n}\n")
        assertNull(hover(file, "Get() +"))
        assertEquals("s", hover(file, "s.Get"))
        assertEquals("s.n", hover(file, "n\n}", 0))
    }

    fun testTheVariablesOfALine() {
        val file = file("v.go", """
            package a

            type B struct{ c int }

            func f(x, y int) int { return x + y }

            func run(a int, b B) int {
            	x := f(a, b.c)
            	return x
            }
        """.trimIndent())
        val line = file.text.lines().indexOfFirst { "x := f(a, b.c)" in it }
        assertEquals(listOf("x", "a", "b.c"), GoInlineValues.of(file, line))
        val lines = file.text.lines()
        assertEquals("the signature and the uses up to the current line", listOf(lines.indexOfFirst { "func run" in it }, line), GoInlineValues.lines(file, "a", line + 1))
        assertEquals(listOf(line, line + 1), GoInlineValues.lines(file, "x", line + 1))
        assertEquals("a function is no variable", emptyList<Int>(), GoInlineValues.lines(file, "f", line))
    }

    fun testBreakpointLines() {
        val source = """
            package a

            var handler = func() {
            	println("x")
            }

            // Total adds.
            func Total(xs []int) int {
            	sum := 0

            	// a comment
            	for _, x := range xs {
            		sum += x
            	}
            	return sum
            }
        """.trimIndent()
        val file = file("b.go", source)
        val lines = source.lines()
        val psi = GoBreakpointLines.find(file)
        fun at(text: String) = lines.indexOfFirst { it == text }
        assertTrue(at("func Total(xs []int) int {") in psi && at("\tsum := 0") in psi && at("\t\tsum += x") in psi && at("\t}") in psi)
        assertFalse("a comment", at("\t// a comment") in psi)
        assertFalse("a blank line", at("\tsum := 0") + 1 in psi)
        assertFalse("the closing brace of a function", lines.lastIndex in psi)
        assertFalse("the doc comment", at("// Total adds.") in psi)
        assertTrue("a variable with a function literal", at("\tprintln(\"x\")") in psi)
    }

    fun testTheHelperAnswersNothingForAnUncommittedDocument() {
        val file = myFixture.configureByText("u.go", "package a\n\nfunc f(n int) int {\n\treturn n\n}\n") as GoFile
        val document = myFixture.editor.document
        assertEquals("psi", GoDebugPsi.compute(project, document) { "psi" })
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "// x\n") }
        assertNull(GoDebugPsi.compute(project, document) { "psi" })
        assertNotNull(file)
    }

    fun testSubtestsByTheTypeOfT() {
        val source = """
            package a

            import "testing"

            type Server struct{}

            func (s *Server) Run(name string, f func()) {}

            func TestX(t *testing.T) {
            	s := &Server{}
            	s.Run("server", func() {})
            	t.Run("outer", func(t *testing.T) {
            		t.Run("inner one", func(t *testing.T) {})
            	})
            	tests := []struct{ name string }{{name: "case a"}}
            	for _, tc := range tests {
            		t.Run(tc.name, func(t *testing.T) {
            			t.Run("hidden", func(t *testing.T) {})
            		})
            	}
            }
        """.trimIndent()
        val file = file("x_test.go", source)
        val function = file.functions.first { it.name == "TestX" }
        val subtests = GoSubtests.find(function)
        assertEquals(listOf("outer", "outer/inner_one", "case_a"), subtests.map { it.name })
        assertEquals("TestX/outer/inner_one", subtests[1].fullName)
        assertEquals("\"inner one\"", subtests[1].nameRange.substring(source))
        assertFalse("a Run of a server is no subtest", subtests.any { it.name == "server" })
        assertEquals(setOf("outer", "outer/inner_one", "case_a"), GoSubtests.ofFile(file).values.map { it.name }.toSet())
    }

    fun testTheNameDelveGivesTheFunctionAtACaret() {
        val text = """
            package store

            type Order struct{}

            func NewOrder() *Order { return nil }

            func (o *Order) Total() int {
            	return 0
            }

            func (o Order) Empty() bool { return true }

            var x = 1
        """.trimIndent()
        val file = file("store.go", text)
        assertEquals("store.NewOrder", GoFunctionNames.at(file, text.indexOf("return nil")))
        assertEquals("store.(*Order).Total", GoFunctionNames.at(file, text.indexOf("return 0")))
        assertEquals("store.Order.Empty", GoFunctionNames.at(file, text.indexOf("Empty")))
        assertNull("not in a function", GoFunctionNames.at(file, text.indexOf("var x")))
        assertEquals("main.main", GoFunctionNames.at(file("main.go", "package main\n\nfunc main() {\n}\n"), 22))
    }

    fun testInlineValueLinesStayInTheFunctionAndAboveTheLine() {
        val text = """
            package main

            type Item struct{ Price, Quantity int }
            type Order struct{ items []Item }

            func (o *Order) Total() int {
            	total := 0
            	for _, item := range o.items {
            		total += item.Price * item.Quantity
            	}
            	return total
            }

            func other() {
            	total := 1
            	_ = total
            }
        """.trimIndent()
        val file = file("order.go", text)
        val lines = text.lines()
        fun at(code: String) = lines.indexOfFirst { it.trim() == code }
        val returned = at("return total")
        // the lines of Total() that mention `total`, not other(), not `item.Price` for `Price`
        assertEquals(listOf(at("total := 0"), at("total += item.Price * item.Quantity"), returned), GoInlineValues.lines(file, "total", returned))
        assertEquals(listOf(at("for _, item := range o.items {"), at("total += item.Price * item.Quantity")), GoInlineValues.lines(file, "item", returned))
        assertEquals(emptyList<Int>(), GoInlineValues.lines(file, "Price", returned))
        // stopped in the loop: nothing below the line
        assertEquals(listOf(at("total := 0"), at("total += item.Price * item.Quantity")), GoInlineValues.lines(file, "total", at("total += item.Price * item.Quantity")))
        assertEquals(emptyList<Int>(), GoInlineValues.lines(file, "total", 100))
    }

    fun testProgramsAreMainPackagesWithAMain() {
        assertTrue(GoRunConfigurationGenerator.isProgram(file("p1/main.go", "package main\n\nfunc main() {}\n")))
        assertFalse(GoRunConfigurationGenerator.isProgram(file("p2/store.go", "package store\n\nfunc main() {}\n")))
        assertFalse(GoRunConfigurationGenerator.isProgram(file("p3/helper.go", "package main\n\nfunc helper() {}\n")))
    }
}
