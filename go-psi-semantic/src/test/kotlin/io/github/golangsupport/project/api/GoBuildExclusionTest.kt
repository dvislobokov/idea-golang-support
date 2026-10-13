package io.github.golangsupport.project.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The reasons and texts of GoLand for files of prometheus on windows/amd64 (docs/goland-analysis/dumps/excluded-files.txt). */
class GoBuildExclusionTest {
    private val windows = GoBuildContext("windows", "amd64")

    private fun of(name: String, header: String = "") = GoBuildExclusion.of(name, "$header\n\npackage p\n", windows)

    private fun text(name: String, header: String = "") = of(name, header)?.let { it.message(name) + if (it.byName) " [rename]" else " [constraints]" }

    @Test fun customFlag() {
        assertEquals("'labels_dedupelabels.go' is ignored by the build tool because of the custom flag [constraints]", text("labels_dedupelabels.go", "//go:build dedupelabels"))
        assertEquals("'tools.go' is ignored by the build tool because of the custom flag [constraints]", text("tools.go", "//go:build tools"))
    }

    @Test fun operatingSystem() {
        assertEquals("'tsdb_posix_test.go' is ignored by the build tool because of the OS mismatch [constraints]", text("tsdb_posix_test.go", "//go:build !windows"))
        assertEquals("'direct_io_linux.go' is ignored by the build tool because of the OS mismatch [rename]", text("direct_io_linux.go"))
    }

    @Test fun architecture() {
        assertEquals("'mmap_arm64.go' is ignored by the build tool because of the architecture mismatch [rename]", text("mmap_arm64.go"))
        // both differ: GoLand names the architecture
        assertEquals("'statfs_linux_386.go' is ignored by the build tool because of the architecture mismatch [rename]", text("statfs_linux_386.go"))
    }

    @Test fun inTheBuild() {
        assertNull(of("labels_stringlabels.go", "//go:build !slicelabels && !dedupelabels"))
        assertNull(of("config_windows_test.go"))
        assertNull(of("mmap_amd64.go"))
        assertNull(of("install.go"))
    }
}
