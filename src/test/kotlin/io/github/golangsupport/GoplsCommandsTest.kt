package io.github.golangsupport

import com.google.gson.JsonParser
import io.github.golangsupport.lsp.GoplsCommandArguments
import io.github.golangsupport.lsp.GoplsLogLines
import io.github.golangsupport.lsp.GoplsServerArguments
import io.github.golangsupport.settings.GoSettings
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The arguments of the lenses as gopls 0.23 sends them (tools/gopls shows them), and the flags of the server. */
class GoplsCommandsTest {
    @Test fun testsOfALens() {
        val one = GoplsCommandArguments.testsTarget(listOf(JsonParser.parseString("""{"URI": "file:///C:/p/store/order_test.go", "Tests": ["TestTotal"], "Benchmarks": null}""")))!!
        assertEquals("file:///C:/p/store/order_test.go", one.uri)
        assertEquals(listOf("TestTotal"), one.names)
        assertFalse(one.benchmark)
        assertEquals("TestTotal", one.name)
        val benchmarks = GoplsCommandArguments.testsTarget(listOf(JsonParser.parseString("""{"URI": "file:///C:/p/x_test.go", "Tests": null, "Benchmarks": ["BenchmarkA", "BenchmarkB"]}""")))!!
        assertTrue(benchmarks.benchmark)
        assertEquals("2 benchmarks", benchmarks.name)
        // the client may hand the argument over as a map instead of a JSON tree
        val fromMap = GoplsCommandArguments.testsTarget(listOf(mapOf("URI" to "file:///x_test.go", "Tests" to listOf("TestX"))))!!
        assertEquals(listOf("TestX"), fromMap.names)
        assertNull(GoplsCommandArguments.testsTarget(listOf(JsonParser.parseString("""{"URI": "file:///x_test.go", "Tests": [], "Benchmarks": null}"""))))
        assertNull(GoplsCommandArguments.testsTarget(null))
    }

    @Test fun generateOfALens() {
        val generate = GoplsCommandArguments.generate(listOf(JsonParser.parseString("""{"Dir": "file:///C:/p/gen", "Recursive": true}""")))!!
        assertEquals("file:///C:/p/gen", generate.uri)
        assertTrue(generate.recursive)
        assertFalse(GoplsCommandArguments.generate(listOf(JsonParser.parseString("""{"Dir": "file:///C:/p/gen"}""")))!!.recursive)
    }

    @Test fun failureOfACommand() {
        val server = ResponseErrorException(ResponseError(-32603, "err: exit status 1: go: module x not found", null))
        assertEquals("err: exit status 1: go: module x not found", GoplsCommandArguments.failure(RuntimeException("wrapped", server)))
        assertEquals("timed out", GoplsCommandArguments.failure(RuntimeException("timed out")))
    }

    @Test fun numbersOfAResult() {
        assertEquals("{\n  \"Total\": 738,\n  \"Ratio\": 1.5,\n  \"Heap\": 1.53655768E8\n}", GoplsCommandArguments.pretty("{\n  \"Total\": 738.0,\n  \"Ratio\": 1.5,\n  \"Heap\": 1.53655768E8\n}"))
    }

    @Test fun serverArguments() {
        val settings = GoSettings()
        assertEquals(listOf("serve"), GoplsServerArguments.of(settings))
        settings.goplsTrace = true
        settings.goplsDebugPages = true
        assertEquals(listOf("serve", "-rpc.trace", "-debug=localhost:0"), GoplsServerArguments.of(settings))
        // the line of gopls for `-debug=localhost:0`, seen live
        assertEquals("http://localhost:61674", GoplsLogLines.debugUrl("serve.go:530: debug server listening at http://localhost:61674\n"))
        assertNull(GoplsLogLines.debugUrl("2026/09/22 09:34:22 Created View (#1)"))
        assertEquals("Created View (#1)", GoplsLogLines.withoutStamp("2026/09/22 09:34:22 Created View (#1)"))
        assertEquals("v0.23.0", GoplsLogLines.version("""{"GoVersion":"go1.26.8","Path":"golang.org/x/tools/gopls","Main":{"Path":"golang.org/x/tools/gopls","Version":"v0.23.0"}}"""))
        assertEquals("0.24.0", GoplsLogLines.version("0.24.0"))
        assertEquals("", GoplsLogLines.version(null))
    }
}
