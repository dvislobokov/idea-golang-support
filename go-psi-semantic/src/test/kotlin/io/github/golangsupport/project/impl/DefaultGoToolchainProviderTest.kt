package io.github.golangsupport.project.impl

import io.github.golangsupport.project.ProjectTestUtil
import io.github.golangsupport.project.api.GoVersion
import junit.framework.TestCase
import java.nio.file.Files

/** Detects the local toolchain (Go 1.27.1 at C:\Program Files\Go on the reference machine). */
class DefaultGoToolchainProviderTest : TestCase() {

    fun testPureDetectionFindsGorootAndVersion() {
        val goroot = ProjectTestUtil.goroot()
        if (!Files.isDirectory(goroot.resolve("src"))) {
            println("skipped: no GOROOT at $goroot")
            return
        }
        // GOROOT from the environment wins; nothing is executed.
        val info = DefaultGoToolchainProvider.detectPure(mapOf("GOROOT" to goroot.toString(), "GOENV" to "off"))!!
        assertEquals(goroot, info.goroot)
        assertTrue("version ${info.version}", info.version!! >= GoVersion("1.27"))
        assertEquals(info.version, DefaultGoToolchainProvider.readVersion(goroot))
        assertEquals(DefaultGoToolchainProvider.hostOs(), info.goos)
        assertEquals(DefaultGoToolchainProvider.hostArch(), info.goarch)
        assertEquals(info.gopath.first().resolve("pkg").resolve("mod"), info.gomodcache)
        assertNotNull(info.goBinary)
        assertEquals(goroot.resolve("src"), info.gorootSrc)
        assertEquals(info.version, info.buildContext.goVersion)
    }

    fun testPathDetection() {
        val binary = ProjectTestUtil.goBinary() ?: return println("skipped: no go binary")
        val info = DefaultGoToolchainProvider.detectPure(mapOf("PATH" to binary.parent.toString(), "GOENV" to "off", "CGO_ENABLED" to "0", "GOFLAGS" to "-tags=a,b -mod=mod"))!!
        assertEquals(ProjectTestUtil.goroot().toRealPath(), info.goroot!!.toRealPath())
        assertFalse(info.cgoEnabled)
        assertEquals(setOf("a", "b"), info.buildTags)
    }

    fun testGoEnvRefinement() {
        val binary = ProjectTestUtil.goBinary() ?: return println("skipped: no go binary")
        val env = GoListModuleGraph.goEnv(binary, binary.parent)!!
        val info = DefaultGoToolchainProvider.fromGoEnv(env, binary, null)
        assertEquals(ProjectTestUtil.goroot().toRealPath(), info.goroot!!.toRealPath())
        assertTrue("version ${info.version}", info.version!! >= GoVersion("1.27"))
        assertEquals(env["GOOS"], info.goos)
        assertEquals(env["GOMODCACHE"], info.gomodcache.toString())
        assertTrue(info.buildContext.matchTag("go1.27"))
        assertTrue(info.buildContext.toolTags.toString(), info.buildContext.matchTag("${info.goarch}.v1") || info.goarch != "amd64")
    }

    fun testDetectBlocking() {
        val info = DefaultGoToolchainProvider().detectBlocking() ?: return println("skipped: no toolchain")
        assertTrue(info.version!! >= GoVersion("1.27"))
        assertNotNull(info.goroot)
    }
}
