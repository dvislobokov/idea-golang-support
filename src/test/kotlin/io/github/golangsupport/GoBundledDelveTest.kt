package io.github.golangsupport

import io.github.golangsupport.debugger.GoBundledDelve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Paths

/** The delve shipped as sources: where its build goes, how it is built, and that the repository carries what the build needs. */
class GoBundledDelveTest {
    @Test fun oneDirectoryPerSourceHash() {
        val path = GoBundledDelve.binaryPath(Paths.get("data", "delve"), "0123abcd")
        assertEquals(Paths.get("data", "delve", "0123abcd").toString(), path.parent.toString())
        assertTrue(path.fileName.toString().startsWith("dlv"))
    }

    @Test fun buildsOfflineFromVendor() {
        assertEquals(listOf("build", "-mod=vendor", "-trimpath", "-o", "out", "./cmd/dlv"), GoBundledDelve.buildArguments("out"))
        // the build at project open leaves the cores to indexing; Debug builds at full speed
        assertEquals(listOf("build", "-p", "2", "-mod=vendor", "-trimpath", "-o", "out", "./cmd/dlv"), GoBundledDelve.buildArguments("out", quiet = true))
        // no toolchain download for the go version go.mod names, no go.work of the user's workspace
        assertEquals("local", GoBundledDelve.BUILD_ENVIRONMENT["GOTOOLCHAIN"])
        assertEquals("off", GoBundledDelve.BUILD_ENVIRONMENT["GOWORK"])
    }

    @Test fun theRepositoryHasVendoredSources() {
        // plain files, not a submodule: a ZIP of the repository from GitHub has them too (third_party/README.md)
        val delve = File("third_party/delve")
        assertTrue("third_party/delve/go.mod is part of the repository", File(delve, "go.mod").isFile)
        assertTrue("not a submodule", !File(delve, ".git").exists() && !File(".gitmodules").exists())
        assertTrue(File(delve, "vendor/modules.txt").isFile)
        assertTrue(File(delve, "cmd/dlv/main.go").isFile)
    }
}
