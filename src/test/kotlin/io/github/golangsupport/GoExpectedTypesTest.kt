package io.github.golangsupport

import io.github.golangsupport.lang.GoExpectedTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GoExpectedTypesTest {
    private val code = """
        package main

        func load(path string, mode int) (*os.File, int, error) {
            var limit time.Duration = <var>
            f, err := os.OpenFile(name, <argument>, 0)
            pause(describe(cfg), <nested>)
            if err != nil {
                return <first>, 0, <third>
            }
            return f, count(<inReturn>), nil
        }
        """.trimIndent().replace("    ", "\t")

    /** The text without the markers, and the offset the marker [name] was at. */
    private fun at(name: String): Pair<String, Int> {
        val markers = Regex("<\\w+>")
        val before = code.substring(0, code.indexOf("<$name>"))
        return markers.replace(code, "") to markers.replace(before, "").length
    }

    private fun byText(name: String): String? = at(name).let { (text, offset) -> GoExpectedTypes.byText(text, offset) }
    private fun call(name: String): Int? = at(name).let { (text, offset) -> GoExpectedTypes.enclosingCall(text, offset) }

    @Test fun theTypeOfAReturnedValueIsTheOneOfItsPlace() {
        assertEquals("*os.File", byText("first"))
        assertEquals("error", byText("third"))
        assertEquals("time.Duration", byText("var"))
        // an argument is the business of the server
        assertNull(byText("argument"))
        assertNull(byText("inReturn"))
    }

    @Test fun theCallAroundTheCaret() {
        val (text, _) = at("argument")
        assertEquals(text.indexOf("os.OpenFile(") + "os.OpenFile".length, call("argument"))
        assertEquals(text.indexOf("pause(") + "pause".length, call("nested"))
        assertEquals(text.indexOf("count(") + "count".length, call("inReturn"))
        assertNull(call("first"))
        assertNull(call("var"))
        // the parameters of a declaration, the condition of a statement, an index
        assertNull(GoExpectedTypes.enclosingCall("func load(path string, ", 23))
        assertNull(GoExpectedTypes.enclosingCall("func (s *Server) Load(path string, ", 35))
        assertNull(GoExpectedTypes.enclosingCall("\tif (", 5))
        assertNull(GoExpectedTypes.enclosingCall("\tx := items[", 12))
        assertNotNull(GoExpectedTypes.enclosingCall("\tgo handle(conn, ", 17))
        assertNotNull(GoExpectedTypes.enclosingCall("\tfmt.Println(\"a (b\", ", 21))
        assertNotNull(GoExpectedTypes.enclosingCall("\thandlers[0](", 13))
    }

    @Test fun theTypeOfAParameter() {
        assertEquals("int", GoExpectedTypes.parameterType("flag int"))
        assertEquals("...any", GoExpectedTypes.parameterType("a ...any"))
        assertEquals("string", GoExpectedTypes.parameterType("string"))
        assertEquals("func(int) error", GoExpectedTypes.parameterType("f func(int) error"))
        assertEquals("map[string]int", GoExpectedTypes.parameterType("map[string]int"))
        assertEquals("chan int", GoExpectedTypes.parameterType("chan int"))
    }

    @Test fun whatFits() {
        // what gopls gives for an argument of type Config, seen with tools/gopls/completion.py
        assertEquals(true, GoExpectedTypes.fits("Config", "cfg", "Config", false))
        assertEquals(true, GoExpectedTypes.fits("Config", "ptr", "*Config", false))
        assertEquals(true, GoExpectedTypes.fits("Config", "newConfig", "func() Config", true))
        assertEquals(true, GoExpectedTypes.fits("Config", "Config{}", null, false))
        assertEquals(false, GoExpectedTypes.fits("Config", "count", "int", false))
        assertEquals(false, GoExpectedTypes.fits("Config", "load", "func(path string, mode int) (*os.File, error)", true))
        assertEquals(false, GoExpectedTypes.fits("Config", "nil", null, false))

        assertEquals(true, GoExpectedTypes.fits("*Config", "cfg", "Config", false))
        assertEquals(true, GoExpectedTypes.fits("*Config", "&Config{}", null, false))
        assertEquals(true, GoExpectedTypes.fits("*Config", "nil", null, false))
        assertEquals(true, GoExpectedTypes.fits("string", "fmt.Sprint", "func(a ...any) string", true))
        assertEquals(true, GoExpectedTypes.fits("...string", "name", "string", false))
        assertEquals(true, GoExpectedTypes.fits("error", "err", "error", false))
        assertEquals(true, GoExpectedTypes.fits("func(int) error", "check", "func(int) error", true))
    }

    @Test fun whatCannotBeTold() {
        // an interface takes values of types a name does not tell
        assertNull(GoExpectedTypes.fits("any", "cfg", "Config", false))
        assertNull(GoExpectedTypes.fits("interface{}", "nil", null, false))
        assertNull(GoExpectedTypes.fits("io.Reader", "f", "*os.File", false))
        assertNull(GoExpectedTypes.fits("context.Context", "ctx", "context.Context", false))
        assertNull(GoExpectedTypes.fits("...any", "name", "string", false))
        assertNull(GoExpectedTypes.fits("T", "item", "string", false))
    }
}
