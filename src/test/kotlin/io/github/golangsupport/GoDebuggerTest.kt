package io.github.golangsupport

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.golangsupport.debugger.DapClosedException
import io.github.golangsupport.debugger.DapConnection
import io.github.golangsupport.debugger.DapException
import io.github.golangsupport.debugger.DapFraming
import io.github.golangsupport.debugger.GoDebugProcess
import io.github.golangsupport.debugger.GoLineBreakpointHandler
import io.github.golangsupport.debugger.GoPanicBreakpointHandler
import io.github.golangsupport.debugger.GoValue
import io.github.golangsupport.debugger.int
import io.github.golangsupport.debugger.json
import io.github.golangsupport.debugger.string
import io.github.golangsupport.run.GoDebugCompletion
import io.github.golangsupport.run.GoPanicFilter
import io.github.golangsupport.debugger.GoFunctionBreakpointHandler
import io.github.golangsupport.run.HitCondition
import junit.framework.TestCase
import java.io.ByteArrayInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.StringWriter
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The plugin's own DAP client against a fake adapter on pipes, and the pure parts of the debugger. No process is started. */
class GoDebuggerTest : TestCase() {
    /** A fake adapter: reads what the client sends, writes what the test tells it to. */
    private inner class FakeAdapter {
        val toClient = PipedOutputStream()
        val fromClient = PipedInputStream(1 shl 20)
        val clientOutput = PipedOutputStream(fromClient)
        val clientInput = PipedInputStream(toClient, 1 shl 20)
        val events = LinkedBlockingQueue<Pair<String, JsonObject>>()
        val trace = StringWriter()
        @Volatile var closed = false
        @Volatile var answerToRequest: JsonObject? = json("shellProcessId" to 1)

        val connection = DapConnection(clientInput, clientOutput, object : DapConnection.Listener {
            override fun event(event: String, body: JsonObject) { events.put(event to body) }
            override fun request(command: String, arguments: JsonObject): JsonObject? = answerToRequest
            override fun closed() { closed = true }
        }, trace)

        fun received(): JsonObject = JsonParser.parseString(DapFraming.read(fromClient)!!).asJsonObject

        fun send(json: String) {
            toClient.write(DapFraming.frame(json))
            toClient.flush()
        }

        fun respond(request: JsonObject, success: Boolean = true, body: String = "{}", message: String? = null) =
            send("""{"seq":100,"type":"response","request_seq":${request.int("seq")},"command":"${request.string("command")}","success":$success,"body":$body${message?.let { ",\"message\":\"$it\"" }.orEmpty()}}""")
    }

    fun testFramingWhateverTheChunks() {
        val two = DapFraming.frame("""{"seq":1,"text":"привет"}""") + DapFraming.frame("{}")
        val input = ByteArrayInputStream(two)
        assertEquals("""{"seq":1,"text":"привет"}""", DapFraming.read(input))
        assertEquals("{}", DapFraming.read(input))
        assertNull("the end between messages", DapFraming.read(input))
        // other headers are allowed, the length counts bytes, not characters
        assertEquals("ж", DapFraming.read(ByteArrayInputStream("Content-Type: x\r\nContent-Length: 2\r\n\r\nж".toByteArray())))
        assertThrows { DapFraming.read(ByteArrayInputStream("Content-Length: 10\r\n\r\n{}".toByteArray())) }
    }

    /** Responses are matched by `request_seq`, whatever the order they come in. */
    fun testResponsesOutOfOrder() {
        val adapter = FakeAdapter()
        adapter.connection.start("test")
        val slow = adapter.connection.request("stackTrace", json("threadId" to 1))
        val fast = adapter.connection.request("threads")
        val first = adapter.received()
        val second = adapter.received()
        assertEquals(listOf("stackTrace", "threads"), listOf(first.string("command"), second.string("command")))
        adapter.respond(second, body = """{"threads":[{"id":7,"name":"* [Go 7] main.main"}]}""")
        adapter.respond(first, body = """{"stackFrames":[]}""")
        assertEquals(7, fast.get(5, TimeUnit.SECONDS).getAsJsonArray("threads")[0].asJsonObject.int("id"))
        assertTrue(slow.get(5, TimeUnit.SECONDS).has("stackFrames"))
        adapter.connection.close()
    }

    fun testErrorsEventsAndRequestsOfTheAdapter() {
        val adapter = FakeAdapter()
        adapter.connection.start("test")

        // the text of delve, with its variables filled in: this is how it words a failed launch
        val failed = adapter.connection.request("launch", json("mode" to "debug"))
        adapter.respond(adapter.received(), success = false, body = """{"error":{"id":3000,"format":"Failed to launch: {reason}","variables":{"reason":"Build error: Check the debug console for details."}}}""")
        val error = assertThrowsExecution { failed.get(5, TimeUnit.SECONDS) }
        assertEquals("Failed to launch: Build error: Check the debug console for details.", GoDebugProcess.errorText(error))
        assertTrue(error.cause is DapException)
        val plain = adapter.connection.request("next")
        adapter.respond(adapter.received(), success = false, message = "Unable to step while the previous step is interrupted")
        assertEquals("Unable to step while the previous step is interrupted", GoDebugProcess.errorText(assertThrowsExecution { plain.get(5, TimeUnit.SECONDS) }))

        adapter.send("""{"seq":5,"type":"event","event":"stopped","body":{"reason":"breakpoint","threadId":1,"allThreadsStopped":true,"hitBreakpointIds":[1]}}""")
        val (event, body) = adapter.events.poll(5, TimeUnit.SECONDS)!!
        assertEquals("stopped" to 1, event to body.int("threadId"))

        // a request of the adapter is answered: with the body the listener gives, or refused
        adapter.send("""{"seq":6,"type":"request","command":"runInTerminal","arguments":{"args":["a"]}}""")
        val answered = adapter.received()
        assertEquals(listOf("response", "6", "true"), listOf(answered.string("type"), answered.get("request_seq").asString, answered.get("success").asString))
        adapter.answerToRequest = null
        adapter.send("""{"seq":7,"type":"request","command":"startDebugging","arguments":{}}""")
        assertEquals(false, adapter.received().get("success").asBoolean)

        // every message both ways goes to the trace
        assertTrue(adapter.trace.toString().contains("-> {\"seq\":1,\"type\":\"request\",\"command\":\"launch\""))
        assertTrue(adapter.trace.toString().contains("<- {\"seq\":5,\"type\":\"event\",\"event\":\"stopped\""))
        adapter.connection.close()
    }

    /** The adapter is gone: what was waiting ends at once, and so does whatever is asked afterwards. */
    fun testClosedConnection() {
        val adapter = FakeAdapter()
        adapter.connection.start("test")
        val waiting = adapter.connection.request("evaluate", json("expression" to "slow"))
        adapter.received()
        adapter.toClient.close()
        assertTrue(assertThrowsExecution { waiting.get(5, TimeUnit.SECONDS) }.cause is DapClosedException)
        val deadline = System.currentTimeMillis() + 5000
        while (!adapter.closed && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(adapter.closed)
        assertTrue(assertThrowsExecution { adapter.connection.request("threads").get(1, TimeUnit.SECONDS) }.cause is DapClosedException)
        // a busy adapter answers nothing at all: a request with a timeout gives up
        val busy = FakeAdapter()
        busy.connection.start("busy")
        assertEquals("The debugger has not answered in time", GoDebugProcess.errorText(assertThrowsExecution { busy.connection.request("disconnect", null, 100).get(5, TimeUnit.SECONDS) }))
        busy.connection.close()
    }

    /** The breakpoints of `setBreakpoints`: 1-based lines, the condition, the hit count and the log message as delve takes them. */
    fun testBreakpointRequests() {
        assertEquals(mapOf("line" to 61), GoLineBreakpointHandler.breakpointJson(60, null, null, null))
        assertEquals(mapOf("line" to 1, "condition" to "i == 1", "hitCondition" to ">= 3", "logMessage" to "total = {total}"),
            GoLineBreakpointHandler.breakpointJson(0, " i == 1 ", ">=3", " total = {total} "))
        // a bare number is `== N` for delve; what it would refuse the whole breakpoint for is not sent
        assertEquals(mapOf("line" to 5, "hitCondition" to "5"), GoLineBreakpointHandler.breakpointJson(4, " ", "5", ""))
        assertEquals(mapOf("line" to 5), GoLineBreakpointHandler.breakpointJson(4, null, "abc", null))
        assertEquals(mapOf("name" to "store.(*Order).Total", "condition" to "o.Currency == \"EUR\"", "hitCondition" to ">= 3"), GoFunctionBreakpointHandler.breakpointJson(" store.(*Order).Total ", " o.Currency == \"EUR\" ", ">=3"))
        assertEquals(mapOf("name" to "/Total$/"), GoFunctionBreakpointHandler.breakpointJson("/Total$/", "", "abc"))
        for (valid in listOf("", "  ", "5", "==5", ">= 3", ">3", "<=10", "< 2", "% 10", "!= 3")) assertTrue(valid, HitCondition.isValid(valid))
        for (invalid in listOf("0", "-1", "abc", "5 times", ">=", "3 >", "1.5")) assertFalse(invalid, HitCondition.isValid(invalid))
    }

    /** The filters of delve by their ids; an empty list is still sent (see the handler). */
    fun testPanicBreakpointRequests() {
        assertEquals("""{"filters":[]}""", GoPanicBreakpointHandler.arguments(emptySet()).toString())
        assertEquals("""{"filters":["unrecovered-panic","runtime-fatal-throw"]}""", GoPanicBreakpointHandler.arguments(GoPanicFilter.entries.toSet()).toString())
        assertEquals("""{"filters":["runtime-fatal-throw"]}""", GoPanicBreakpointHandler.arguments(setOf(GoPanicFilter.FATAL_THROW)).toString())
    }

    fun testSetValueRequest() {
        assertEquals("""{"variablesReference":1001,"name":"total","value":"777"}""", GoValue.setVariableArguments(1001, "total", " 777 ").toString())
        assertEquals("boom", GoDebugProcess.errorText(IllegalStateException("boom")))
    }

    fun testCompletionContext() {
        fun at(text: String): Pair<String?, String>? {
            val offset = text.indexOf('|')
            return GoDebugCompletion.contextAt(text.replace("|", ""), offset)?.let { it.qualifier to it.prefix }
        }
        assertEquals(null to "", at("|"))
        assertEquals(null to "ord", at("ord|"))
        assertEquals("order" to "", at("order.|"))
        assertEquals("order" to "Cur", at("order.Cur|"))
        assertEquals("order.items" to "", at("order.items.|"))
        assertEquals(null to "", at("len(order) + |"))
        assertNull("a call is not evaluated while typing", at("NewOrder().|"))
        assertNull("nor an element", at("items[0].|"))
        assertNull(at("\"text|\""))
        assertNull(at("// comment|"))
        assertNull(at("12|"))
        assertTrue(GoDebugCompletion.isName("Currency"))
        assertTrue(GoDebugCompletion.isName("_x1"))
        assertFalse(GoDebugCompletion.isName("[0]"))
        assertFalse(GoDebugCompletion.isName("[\"key\"]"))
    }

    private fun assertThrowsExecution(block: () -> Unit): ExecutionException {
        try {
            block()
        } catch (e: ExecutionException) {
            return e
        }
        fail("no exception")
        throw IllegalStateException()
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            return
        }
        fail("no exception")
    }
}
