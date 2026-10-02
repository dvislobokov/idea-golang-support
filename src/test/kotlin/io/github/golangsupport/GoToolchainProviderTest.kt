package io.github.golangsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.project.api.GoToolchainProvider
import io.github.golangsupport.sdk.GoIgsToolchainProvider
import io.github.golangsupport.settings.GoSettings

/** The toolchain of the project model comes from the plugin: its go, its settings; the detection of go-psi is the fallback. */
class GoToolchainProviderTest : BasePlatformTestCase() {
    private val settings get() = GoSettings.getInstance()
    private var tags = ""
    private var goos = ""
    private var goarch = ""

    override fun setUp() {
        super.setUp()
        tags = settings.buildTags
        goos = settings.analysisGoos
        goarch = settings.analysisGoarch
    }

    override fun tearDown() {
        try {
            settings.buildTags = tags
            settings.analysisGoos = goos
            settings.analysisGoarch = goarch
        } finally {
            super.tearDown()
        }
    }

    fun testThePluginsProviderIsTheServiceOfTheModel() {
        assertTrue(GoToolchainProvider.getInstance().javaClass.name, GoToolchainProvider.getInstance() is GoIgsToolchainProvider)
    }

    fun testTheSettingsReachTheBuildContext() {
        val provider = GoIgsToolchainProvider()
        val before = provider.toolchainFor(project) ?: return // no Go on this machine: nothing to adjust, and nothing to assert
        val stamp = provider.modificationTracker.modificationCount
        settings.buildTags = "integration, fast"
        settings.analysisGoos = "linux"
        settings.analysisGoarch = "arm64"
        val info = provider.toolchainFor(project)!!
        assertTrue(info.buildTags.toString(), info.buildTags.containsAll(setOf("integration", "fast")))
        assertEquals("linux", info.goos)
        assertEquals("arm64", info.goarch)
        assertEquals(before.goroot, info.goroot)
        assertTrue("a change of the settings is a change of the toolchain", provider.modificationTracker.modificationCount > stamp)
        assertTrue(info.buildContext.matchTag("integration") && info.buildContext.matchTag("linux") && !info.buildContext.matchTag("windows"))
    }
}
