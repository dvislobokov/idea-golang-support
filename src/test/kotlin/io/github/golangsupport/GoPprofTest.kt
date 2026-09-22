package io.github.golangsupport

import io.github.golangsupport.monitor.GoGoroutineDump
import io.github.golangsupport.monitor.GoListeningPorts
import io.github.golangsupport.monitor.GoPprofKind
import io.github.golangsupport.monitor.GoPprofProbe
import io.github.golangsupport.monitor.ListeningSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The outputs as the tools print them (netstat of Windows 11, /proc of Linux, lsof of macOS, pprof of Go 1.24). */
class GoPprofTest {
    @Test fun netstat() {
        val text = """
            Active Connections

              Proto  Local Address          Foreign Address        State           PID
              TCP    0.0.0.0:135            0.0.0.0:0              LISTENING       1524
              TCP    127.0.0.1:16060        0.0.0.0:0              LISTENING       38232
              TCP    127.0.0.1:54418        127.0.0.1:16060        TIME_WAIT       0
              TCP    127.0.0.1:16060        127.0.0.1:54419        ESTABLISHED     38232
              TCP    [::]:8080              [::]:0                 ПРОСЛУШИВАНИЕ   38232
              TCP    [::1]:9090             [::]:0                 LISTENING       38232
        """.trimIndent()
        assertEquals(
            listOf(ListeningSocket("127.0.0.1", 16060), ListeningSocket("::", 8080), ListeningSocket("::1", 9090)),
            GoListeningPorts.parseNetstat(text, setOf(38232L)),
        )
        assertTrue(GoListeningPorts.parseNetstat(text, setOf(1L)).isEmpty())
    }

    @Test fun procAndLsof() {
        val tcp = """
              sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
               0: 0100007F:17AC 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 55123 1 0000000000000000 100 0 0 10 0
               1: 0100007F:17AC 0100007F:D2F2 01 00000000:00000000 00:00000000 00000000  1000        0 55124 1 0000000000000000 20 4 30 10 -1
        """.trimIndent()
        assertEquals(mapOf(55123L to ListeningSocket("127.0.0.1", 6060)), GoListeningPorts.parseProcNetTcp(tcp, ipv6 = false))
        val tcp6 = "  sl  local_address ...\n   0: 00000000000000000000000000000000:1F90 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 77 1\n"
        assertEquals(mapOf(77L to ListeningSocket("::", 8080)), GoListeningPorts.parseProcNetTcp(tcp6, ipv6 = true))
        val lsof = "COMMAND PID USER FD TYPE DEVICE SIZE/OFF NODE NAME\napp 123 me 7u IPv4 0x1 0t0 TCP 127.0.0.1:6060 (LISTEN)\napp 123 me 8u IPv6 0x2 0t0 TCP *:8080 (LISTEN)\n"
        assertEquals(listOf(ListeningSocket("127.0.0.1", 6060), ListeningSocket("0.0.0.0", 8080)), GoListeningPorts.parseLsof(lsof))
    }

    @Test fun probeHelpers() {
        assertEquals("127.0.0.1", GoPprofProbe.host("0.0.0.0"))
        assertEquals("127.0.0.1", GoPprofProbe.host("::"))
        assertEquals("[::1]", GoPprofProbe.host("::1"))
        assertEquals("192.168.1.5", GoPprofProbe.host("192.168.1.5"))
        assertTrue(GoPprofProbe.isIndex("<html><p>Types of profiles available:</p><a href='goroutine?debug=1'>"))
        assertFalse(GoPprofProbe.isIndex("<html>404 page not found</html>"))
        // the text forms of Go 1.24 (seen live): switched off, switched on without samples, with a sample
        assertTrue(GoPprofProbe.emptyReason(GoPprofKind.MUTEX, "--- mutex:\ncycles/second=4199756044\nsampling period=0\n")!!.contains("SetMutexProfileFraction"))
        assertTrue(GoPprofProbe.emptyReason(GoPprofKind.BLOCK, "--- contention:\ncycles/second=4199756044\n")!!.contains("SetBlockProfileRate"))
        assertNull(GoPprofProbe.emptyReason(GoPprofKind.BLOCK, "--- contention:\ncycles/second=1\n4829 12 @ 0x1 0x2\n#\t0x1\tsync.(*Mutex).Lock+0x1\tmutex.go:1\n"))
        assertNull(GoPprofProbe.emptyReason(GoPprofKind.HEAP, ""))
    }

    @Test fun goroutines() {
        val text = """
            goroutine profile: total 8
            5 @ 0x5e4b0e 0x5e89c7 0x7cd3fd 0x5ec6a1
            #	0x5e89c6	time.Sleep+0x166	C:/Program Files/Go/src/runtime/time.go:338
            #	0x7cd3fc	main.main.func1+0x1c	C:/p/main.go:11

            1 @ 0x5a5751 0x5e395d
            #	0x7b1810	runtime/pprof.writeRuntimeProfile+0xb0	C:/Program Files/Go/src/runtime/pprof/pprof.go:796
            #	0x7c857d	net/http/pprof.Index+0xdd		C:/Program Files/Go/src/net/http/pprof/pprof.go:389

            2 @ 0x5e4b0e 0x5a8c17
            #	0x5e3ce4	internal/poll.runtime_pollWait+0x84		C:/Program Files/Go/src/runtime/netpoll.go:351
            #	0x7cd000	example.com/app/server.(*Server).Serve+0x40	C:/p/server/server.go:42
        """.trimIndent()
        assertEquals(8, GoGoroutineDump.total(text))
        val groups = GoGoroutineDump.parse(text)
        assertEquals(listOf(5, 2, 1), groups.map { it.count })
        assertEquals("main.main.func1", groups[0].ownFrame!!.function)
        assertEquals("C:/p/main.go:11", groups[0].ownFrame!!.location)
        assertEquals("time.Sleep", groups[0].waitingIn)
        assertEquals("5 × main.main.func1  — in time.Sleep", groups[0].title)
        assertEquals("example.com/app/server.(*Server).Serve", groups[1].ownFrame!!.function)
        // a group of the library only shows its top frame
        assertEquals("runtime/pprof.writeRuntimeProfile", groups[2].ownFrame!!.function)
        assertNull(groups[2].waitingIn)
    }
}
