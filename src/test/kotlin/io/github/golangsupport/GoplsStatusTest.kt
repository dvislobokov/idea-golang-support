package io.github.golangsupport

import io.github.golangsupport.lsp.GoplsLogLines
import io.github.golangsupport.lsp.GoplsStatusText
import io.github.golangsupport.lsp.GoplsUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration

class GoplsStatusTest {
    /** What gopls gives for its version, seen live (shortened). */
    private val buildInfo = """{"GoVersion":"go1.26.8","Path":"golang.org/x/tools/gopls","Main":{"Path":"golang.org/x/tools/gopls","Version":"v0.23.0"},"Deps":[]}"""

    private fun sample(megabytes: Long, cpu: Double?, committed: Long = megabytes) =
        GoplsUsage.Sample(4242, 1, megabytes * 1024 * 1024, committed * 1024 * 1024, cpu, Duration.ofMinutes(5))

    @Test fun theServerInWordsInsteadOfItsBuildInfo() {
        assertEquals("gopls v0.23.0", GoplsStatusText.title(buildInfo))
        assertEquals("go1.26.8", GoplsStatusText.builtWith(buildInfo))
        assertEquals("gopls 0.24.0", GoplsStatusText.title("0.24.0"))
        assertNull(GoplsStatusText.builtWith("0.24.0"))
        // before the server has answered `initialize`
        assertEquals("gopls", GoplsStatusText.title(null))
        assertNull(GoplsLogLines.goVersion(null))
    }

    @Test fun theStatusBar() {
        assertEquals("gopls", GoplsStatusText.widget(null))
        // the first sample has no share of the CPU yet
        assertEquals("312 MB", GoplsStatusText.widget(sample(312, null)))
        assertEquals("312 MB · 2%", GoplsStatusText.widget(sample(312, 1.6)))
        assertEquals("1.50 GB · 0%", GoplsStatusText.widget(sample(1536, 0.2)))
        assertEquals("gopls v0.23.0, memory 312 MB, CPU 1.6 %, running 5 min", GoplsStatusText.tooltip(buildInfo, sample(312, 1.6)))
        assertEquals("gopls", GoplsStatusText.tooltip(null, null))
    }

    @Test fun thePopup() {
        assertEquals("312 MB", GoplsStatusText.memoryWithCommitted(312L shl 20, 312L shl 20))
        assertEquals("312 MB (committed 540 MB)", GoplsStatusText.memoryWithCommitted(312L shl 20, 540L shl 20))
        assertEquals("312 MB", GoplsStatusText.memoryWithCommitted(312L shl 20, 0))
        assertEquals("PID 4242, 3 processes, running 1 h 5 min", GoplsStatusText.process(4242, 3, Duration.ofMinutes(65)))
        assertEquals("PID 4242", GoplsStatusText.process(4242, 1, null))
    }
}
