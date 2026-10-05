package io.github.golangsupport

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.golangsupport.monitor.GoDumpGoroutines
import io.github.golangsupport.monitor.GoDumpSource
import io.github.golangsupport.monitor.GoGoroutineDump
import io.github.golangsupport.monitor.GoStackFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dump Goroutines: the runtime's dump format (runtime/traceback.go: goroutineheader, printFuncName, printcreatedby), written by hand from it. */
class GoGoroutineDumpTest {
    private val sigquit = """
        SIGQUIT: quit
        PC=0x46e0c1 m=0 sigcode=0

        goroutine 1 [chan receive, 3 minutes]:
        main.main()
        	/home/dev/app/main.go:21 +0x9a

        goroutine 18 gp=0xc000102a80 m=nil [select, locked to thread]:
        net/http.(*Server).Serve(0xc0001a2000, {0x7a5e40, 0xc0000a6020})
        	/usr/local/go/src/net/http/server.go:3360 +0x3a5
        main.serve.func1()
        	/home/dev/app/server.go:14 +0x25
        created by main.serve in goroutine 1
        	/home/dev/app/server.go:12 +0x6b

        goroutine 19 [chan receive]:
        main.worker(0xc00001c0c0)
        	C:/work/app/worker.go:8 +0x31
        ...additional frames elided...
        created by main.main
        	C:/work/app/main.go:17 +0x45

        goroutine 20 [running]:
        main.spin()
        	/home/dev/app/main.go:30 +0x9

        rax    0xca
        rbx    0x0
    """.trimIndent()

    @Test fun headersStatesAndFrames() {
        val dump = GoGoroutineDump.parse(sigquit)
        assertEquals(listOf(1, 18, 19, 20), dump.map { it.id })
        assertEquals(listOf("chan receive", "select", "chan receive", "running"), dump.map { it.state })
        assertEquals(3, dump[0].waitMinutes)
        assertNull(dump[1].waitMinutes)
        assertTrue(dump[1].lockedToThread)
        assertFalse(dump[0].lockedToThread)
        assertEquals(listOf(GoStackFrame("main.main", "/home/dev/app/main.go", 21)), dump[0].frames)
        assertEquals(GoStackFrame("net/http.(*Server).Serve", "/usr/local/go/src/net/http/server.go", 3360), dump[1].frames[0])
        assertEquals("main.serve.func1", dump[1].frames[1].function)
        assertEquals(GoStackFrame("main.serve", "/home/dev/app/server.go", 12), dump[1].createdBy)
        assertEquals(1, dump[1].createdIn)
        // Windows paths keep their drive; an elided marker is not a frame; `created by` without a goroutine number (before Go 1.21)
        assertEquals(listOf(GoStackFrame("main.worker", "C:/work/app/worker.go", 8)), dump[2].frames)
        assertEquals(GoStackFrame("main.main", "C:/work/app/main.go", 17), dump[2].createdBy)
        assertNull(dump[2].createdIn)
        // the registers after the last goroutine are not frames of it
        assertEquals(1, dump[3].frames.size)
    }

    @Test fun functionNamesDropTheArguments() {
        assertEquals("net/http.(*Server).Serve", GoGoroutineDump.functionName("net/http.(*Server).Serve(0xc0001a2000, {0x7a5e40, 0xc0000a6020})"))
        assertEquals("main.main", GoGoroutineDump.functionName("main.main()"))
        assertEquals("main.Map[...]", GoGoroutineDump.functionName("main.Map[...](...)"))
        assertEquals("panic", GoGoroutineDump.functionName("panic({0x4a0a20?, 0x52b6f0?})"))
    }

    @Test fun formattedDumpReadsBackTheSame() {
        val dump = GoGoroutineDump.parse(sigquit)
        val text = GoGoroutineDump.format(dump)
        assertTrue(text, text.startsWith("goroutine 1 [chan receive, 3 minutes]:\nmain.main(...)\n\t/home/dev/app/main.go:21\n\n"))
        assertTrue(text, text.contains("goroutine 18 [select, locked to thread]:"))
        assertTrue(text, text.contains("created by main.serve in goroutine 1\n\t/home/dev/app/server.go:12\n"))
        assertEquals(dump, GoGoroutineDump.parse(text))
    }

    @Test fun summaryCountsTheStates() {
        assertEquals("4 goroutines: 2 chan receive, 1 running, 1 select", GoGoroutineDump.summary(GoGoroutineDump.parse(sigquit)))
        assertEquals("0 goroutines", GoGoroutineDump.summary(emptyList()))
    }

    @Test fun delveDumpHasStacksWithoutStates() {
        val answers = mapOf(
            "threads" to """{"threads":[{"id":7,"name":"[Go 7] main.worker"},{"id":1,"name":"* [Go 1] main.main"}]}""",
            "stackTrace:1" to """{"stackFrames":[{"id":1000,"name":"main.main","line":21,"source":{"path":"C:\\work\\app\\main.go"}}]}""",
        )
        val dump = GoGoroutineDump.collect(50) { command, arguments: JsonObject ->
            val key = if (command == "stackTrace") "stackTrace:" + arguments.get("threadId").asInt else command
            JsonParser.parseString(answers[key] ?: throw IllegalStateException("goroutine is gone")).asJsonObject
        }
        assertEquals(listOf(1, 7), dump.map { it.id })
        assertEquals(listOf(GoStackFrame("main.main", "C:/work/app/main.go", 21)), dump[0].frames)
        // a goroutine whose stack could not be read is still listed
        assertEquals(emptyList<GoStackFrame>(), dump[1].frames)
        assertEquals("goroutine 1:\nmain.main(...)\n\tC:/work/app/main.go:21\n\ngoroutine 7:\n\n", GoGoroutineDump.format(dump))
        assertEquals("2 goroutines", GoGoroutineDump.summary(dump))
    }

    @Test fun whatTheActionActsOn() {
        assertEquals(GoDumpSource.DEBUG_SESSION, GoDumpGoroutines.availability(debugSession = true, debugSuspended = true, selectedRunIsGo = true, runningProcesses = 2).source)
        assertEquals(GoDumpSource.PROCESS, GoDumpGoroutines.availability(debugSession = false, debugSuspended = false, selectedRunIsGo = true, runningProcesses = 3).source)
        assertEquals(GoDumpSource.PROCESS, GoDumpGoroutines.availability(debugSession = false, debugSuspended = false, selectedRunIsGo = false, runningProcesses = 1).source)
        assertEquals(GoDumpSource.CHOOSE, GoDumpGoroutines.availability(debugSession = false, debugSuspended = false, selectedRunIsGo = false, runningProcesses = 2).source)
        // a running debug session with nothing else: disabled, and the description says why
        val running = GoDumpGoroutines.availability(debugSession = true, debugSuspended = false, selectedRunIsGo = false, runningProcesses = 0)
        assertFalse(running.enabled)
        assertTrue(running.description, running.description.contains("Pause"))
        assertFalse(GoDumpGoroutines.availability(debugSession = false, debugSuspended = false, selectedRunIsGo = false, runningProcesses = 0).enabled)
    }

    @Test fun windowsHasNoSigquit() {
        assertTrue(GoDumpGoroutines.viaDelve(setting = false, windows = true))
        assertFalse(GoDumpGoroutines.viaDelve(setting = false, windows = false))
        assertTrue(GoDumpGoroutines.viaDelve(setting = true, windows = false))
    }
}
