package io.github.golangsupport

import io.github.golangsupport.monitor.GoBuildInfo
import io.github.golangsupport.monitor.GoProfile
import io.github.golangsupport.monitor.GoProcesses
import io.github.golangsupport.monitor.GoProfiles
import io.github.golangsupport.monitor.GoRuntimeEvent
import io.github.golangsupport.monitor.GoRuntimeState
import io.github.golangsupport.monitor.GoRuntimeTrace
import io.github.golangsupport.monitor.GoSnapshot
import io.github.golangsupport.monitor.GoroutineInfo
import io.github.golangsupport.monitor.ProcessSampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Duration

/** The lines of the Go runtime and of the go tools as Go 1.24.7 prints them (seen live, see GoRuntimeTrace). */
class GoMonitorTest {
    @Test fun gcTrace() {
        val gc = GoRuntimeTrace.parse("gc 4 @1.761s 2%: 0.012+1.5+0.031 ms clock, 0.14+0.25/1.2/0.55+0.37 ms cpu, 36->37->18 MB, 40 MB goal, 0 MB stacks, 0 MB globals, 12 P") as GoRuntimeEvent.Gc
        assertEquals(4, gc.number)
        assertEquals(1.761, gc.atSeconds, 1e-9)
        assertEquals(2, gc.cpuPercent)
        assertEquals(0.012 + 1.5 + 0.031, gc.clockMs, 1e-9)
        assertEquals(36.0, gc.heapBeforeMb, 0.0)
        assertEquals(37.0, gc.heapAfterMb, 0.0)
        assertEquals(18.0, gc.liveMb, 0.0)
        assertEquals(40.0, gc.goalMb, 0.0)
        assertEquals(12, gc.procs)
        // a forced collection has the same shape with "(forced)" at the end
        assertTrue(GoRuntimeTrace.parse("gc 5 @2.0s 0%: 0+0+0 ms clock, 0+0/0/0+0 ms cpu, 4->4->0 MB, 4 MB goal, 0 MB stacks, 0 MB globals, 12 P (forced)") is GoRuntimeEvent.Gc)
    }

    @Test fun schedTrace() {
        val sched = GoRuntimeTrace.parse("SCHED 1000ms: gomaxprocs=12 idleprocs=10 threads=8 spinningthreads=1 needspinning=0 idlethreads=5 runqueue=2 [1 0 3 0 0 0 0 0 0 0 0 0]") as GoRuntimeEvent.Sched
        assertEquals(1000L, sched.atMs)
        assertEquals(12, sched.procs)
        assertEquals(10, sched.idleProcs)
        assertEquals(8, sched.threads)
        assertEquals(5, sched.idleThreads)
        assertEquals(1, sched.spinningThreads)
        assertEquals(2 + 1 + 3, sched.queued)
        val init = GoRuntimeTrace.parse("init internal/godebug @1.1 ms, 0.51 ms clock, 584 bytes, 15 allocs") as GoRuntimeEvent.Init
        assertEquals("internal/godebug", init.packagePath)
        assertEquals(584L, init.bytes)
        assertNull(GoRuntimeTrace.parse("total: 1600 EUR"))
        assertNull(GoRuntimeTrace.parse("gc is not a trace line"))
        assertFalse(GoRuntimeTrace.isTraceLine("hello"))
    }

    /** The pauses and the collections of the last second are counted between two reads; the rest is the latest state. */
    @Test fun runtimeState() {
        val state = GoRuntimeState()
        assertFalse(state.hasData())
        state.accept(GoRuntimeTrace.parse("gc 1 @0.1s 0%: 0.1+0.2+0.3 ms clock, 0+0/0/0+0 ms cpu, 4->4->2 MB, 4 MB goal, 0 MB stacks, 0 MB globals, 8 P")!!)
        state.accept(GoRuntimeTrace.parse("gc 2 @0.4s 1%: 1+1+1 ms clock, 0+0/0/0+0 ms cpu, 9->9->5 MB, 10 MB goal, 0 MB stacks, 0 MB globals, 8 P")!!)
        state.accept(GoRuntimeTrace.parse("SCHED 1000ms: gomaxprocs=8 idleprocs=7 threads=6 spinningthreads=0 needspinning=0 idlethreads=4 runqueue=0 [0 0 0 0 0 0 0 0]")!!)
        val first = state.read()
        assertEquals(5.0, first.liveHeapMb!!, 0.0)
        assertEquals(10.0, first.goalMb!!, 0.0)
        assertEquals(2, first.collections)
        assertEquals(2, first.collectionsPerSecond)
        assertEquals(3.6, first.pauseMs, 1e-9)
        assertEquals(6, first.threads)
        assertEquals(7, first.idleProcs)
        val second = state.read()
        assertEquals(2, second.collections)
        assertEquals(0, second.collectionsPerSecond)
        assertEquals(0.0, second.pauseMs, 0.0)
    }

    @Test fun buildInfo() {
        val info = GoBuildInfo.parse("""
            C:\p\shop.exe: go1.24.7
            	path	example.com/playground/cmd/shop
            	mod	example.com/playground	(devel)
            	dep	github.com/google/uuid	v1.6.0	h1:oHVW
            	build	-race=true
            	build	vcs.revision=3f2a1b4c9d8e7f6a
            	build	vcs.modified=true
        """.trimIndent())!!
        assertEquals("go1.24.7", info.goVersion)
        assertEquals("example.com/playground/cmd/shop", info.path)
        assertEquals("example.com/playground", info.module)
        assertTrue(info.isRace)
        assertEquals("3f2a1b4c", info.revision)
        assertEquals("example.com/playground/cmd/shop (go1.24.7, 3f2a1b4c, race)", info.describe())
        assertNull(GoBuildInfo.parse("C:\\Windows\\notepad.exe: not a Go executable"))
        assertEquals("C:\\Program Files\\Go\\bin\\go.exe", GoProcesses.executableOf("\"C:\\Program Files\\Go\\bin\\go.exe\" build ."))
        assertEquals("C:\\Users\\me\\go\\bin\\gopls.exe", GoProcesses.executableOf("C:\\Users\\me\\go\\bin\\gopls.exe serve"))
        assertEquals("/usr/local/bin/app", GoProcesses.executableOf("/usr/local/bin/app --port 8080"))
        assertNull(GoBuildInfo.parse(""))
    }

    @Test fun profiles() {
        val directory = File("C:/tmp/go-profile-1")
        assertEquals(listOf("-cpuprofile=" + File(directory, "cpu.pprof").path), GoProfiles.arguments(GoProfile.CPU, directory))
        assertEquals(listOf("-trace=" + File(directory, "trace.out").path), GoProfiles.arguments(GoProfile.TRACE, directory))
        assertTrue(GoProfiles.arguments(GoProfile.NONE, directory).isEmpty())
        assertEquals("http://localhost:53421", GoProfiles.servedUrl("Serving web UI on http://localhost:53421\n"))
        assertEquals("http://127.0.0.1:8080", GoProfiles.servedUrl("2026/09/22 12:00:00 Trace viewer is listening on http://127.0.0.1:8080"))
        // the tab is named by the time of the run for a file of the plugin, by the file for any other
        assertEquals("11:45:23", GoProfiles.stamp(java.io.File("/tmp/go-profile-20260930-114523-1234/cpu.pprof")))
        assertEquals("server.pb.gz", GoProfiles.stamp(java.io.File("/home/me/server.pb.gz")))
        assertNull(GoProfiles.servedUrl("Parsing trace..."))
    }

    @Test fun cpuPercentAndGoroutines() {
        assertEquals(50.0, ProcessSampler.cpuPercent(Duration.ZERO, Duration.ofSeconds(2), 1_000_000_000L, processors = 4), 1e-9)
        assertEquals(0.0, ProcessSampler.cpuPercent(Duration.ofSeconds(2), Duration.ofSeconds(1), 1_000_000_000L, processors = 4), 0.0)
        val goroutines = listOf(GoroutineInfo(1, "* [Go 1] main.main (Thread 1234)"), GoroutineInfo(18, "[Go 18] runtime.gopark"), GoroutineInfo(19, "[Go 19] runtime.gopark"))
        assertEquals("main.main", goroutines[0].function)
        assertEquals(listOf("runtime.gopark" to 2, "main.main" to 1), GoSnapshot.summary(goroutines))
    }
}
