package io.github.golangsupport

import io.github.golangsupport.monitor.GoProfile
import io.github.golangsupport.run.GoCommand
import io.github.golangsupport.run.GoProfiler
import io.github.golangsupport.run.GoTestRecording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** Run | Run with Profiler: which flag each kind is, and what a `go test` run records with. */
class GoProfilerTest {
    private val directory = File("/tmp/go-profile-20261005-101500-1")

    @Test fun kindsAreTheFlagsOfGoTest() {
        assertEquals(listOf("-cpuprofile", "-memprofile", "-blockprofile", "-mutexprofile"), GoProfiler.KINDS.map { it.flag })
        assertEquals(listOf("CPU Profiler", "Memory Profiler", "Blocking Profiler", "Mutex Profiler"), GoProfiler.KINDS.map(GoProfiler::label))
    }

    @Test fun recordingArguments() {
        assertEquals(listOf("-cpuprofile=" + File(directory, "cpu.pprof").path), GoTestRecording.arguments(GoProfile.CPU, directory, null))
        assertEquals(listOf("-mutexprofile=" + File(directory, "mutex.pprof").path), GoTestRecording.arguments(GoProfile.MUTEX, directory, null))
        assertEquals(emptyList<String>(), GoTestRecording.arguments(GoProfile.NONE, directory, null))
        assertEquals(emptyList<String>(), GoTestRecording.arguments(GoProfile.CPU, null, null))
        // the Coverage executor: the platform's file, atomic counts; the box of the configuration: the mode go test picks
        val cover = File("/ide/system/coverage/app\$tests.out")
        assertEquals(listOf("-memprofile=" + File(directory, "mem.pprof").path, "-coverprofile=${cover.path}", "-covermode=atomic"),
            GoTestRecording.arguments(GoProfile.MEMORY, directory, cover, "atomic"))
        assertEquals(listOf("-coverprofile=${cover.path}"), GoTestRecording.arguments(GoProfile.NONE, null, cover))
    }

    @Test fun kindOfARun() {
        assertEquals(GoProfile.BLOCK, GoProfiler.kindOf(GoProfile.BLOCK, GoProfile.CPU))
        assertEquals(GoProfile.MEMORY, GoProfiler.kindOf(null, GoProfile.MEMORY))
        assertEquals(GoProfile.CPU, GoProfiler.kindOf(null, GoProfile.NONE))
        assertEquals(GoProfile.CPU, GoProfiler.kindOf(GoProfile.NONE, GoProfile.NONE))
    }

    @Test fun onlyGoTestIsProfiled() {
        assertNull(GoProfiler.unsupported(GoCommand.TEST, overSsh = false))
        assertNotNull(GoProfiler.unsupported(GoCommand.RUN, overSsh = false))
        assertNotNull(GoProfiler.unsupported(GoCommand.EXEC, overSsh = false))
        assertNotNull(GoProfiler.unsupported(GoCommand.TEST, overSsh = true))
    }
}
