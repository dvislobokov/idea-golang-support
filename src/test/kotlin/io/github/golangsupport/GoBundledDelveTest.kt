package io.github.golangsupport

import io.github.golangsupport.debugger.GoBundledDelve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Paths

/** The delve shipped as sources: where its build goes, how it is built, and that the submodule carries what the build needs. */
class GoBundledDelveTest {
    @Test fun oneDirectoryPerSourceHash() {
        val path = GoBundledDelve.binaryPath(Paths.get("system"), "0123abcd")
        assertEquals(Paths.get("system", "go-plugin", "delve", "0123abcd").toString(), path.parent.toString())
        assertTrue(path.fileName.toString().startsWith("dlv"))
    }

    @Test fun buildsOfflineFromVendor() {
        assertEquals(listOf("build", "-mod=vendor", "-trimpath", "-o", "out", "./cmd/dlv"), GoBundledDelve.buildArguments("out"))
        // no toolchain download for the go version go.mod names, no go.work of the user's workspace
        assertEquals("local", GoBundledDelve.BUILD_ENVIRONMENT["GOTOOLCHAIN"])
        assertEquals("off", GoBundledDelve.BUILD_ENVIRONMENT["GOWORK"])
    }

    @Test fun theSubmoduleHasVendoredSources() {
        val delve = File("third_party/delve")
        assertTrue("git submodule update --init", File(delve, "go.mod").isFile)
        assertTrue(File(delve, "vendor/modules.txt").isFile)
        assertTrue(File(delve, "cmd/dlv/main.go").isFile)
    }
}
