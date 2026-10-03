package io.github.golangsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.project.api.GoToolchainProvider
import io.github.golangsupport.sdk.GoIgsToolchainProvider
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.project.api.GoPackageResolver
import io.github.golangsupport.settings.GoCgoMode
import io.github.golangsupport.settings.GoPlatformChoices
import io.github.golangsupport.settings.GoSettings

/** The toolchain of the project model comes from the plugin: its go, its settings; the detection of go-psi is the fallback. */
class GoToolchainProviderTest : BasePlatformTestCase() {
    private val settings get() = GoSettings.getInstance()
    private var tags = ""
    private var goos = ""
    private var goarch = ""
    private var cgo = GoCgoMode.DEFAULT
    private var experiments = ""

    override fun setUp() {
        super.setUp()
        tags = settings.buildTags
        goos = settings.analysisGoos
        goarch = settings.analysisGoarch
        cgo = settings.cgoMode
        experiments = settings.goExperiments
    }

    override fun tearDown() {
        try {
            settings.buildTags = tags
            settings.analysisGoos = goos
            settings.analysisGoarch = goarch
            settings.cgoMode = cgo
            settings.goExperiments = experiments
        } finally {
            super.tearDown()
        }
    }

    fun testThePluginsProviderIsTheServiceOfTheModel() {
        assertTrue(GoToolchainProvider.getInstance().javaClass.name, GoToolchainProvider.getInstance() is GoIgsToolchainProvider)
    }

    /** Asked per import resolve: the answer is kept until the settings change or [GoIgsToolchainProvider.invalidate] (a 12 s freeze otherwise, seen live). */
    fun testTheAnswerIsCachedUntilTheSettingsChangeOrAReanalyze() {
        val provider = GoIgsToolchainProvider()
        val first = provider.toolchainFor(project) ?: return // no Go on this machine
        val stamp = provider.modificationTracker.modificationCount
        assertSame("the same question gives the same answer, no detection", first, provider.toolchainFor(project))
        assertEquals(stamp, provider.modificationTracker.modificationCount)
        settings.buildTags = "cached_test_tag"
        val changed = provider.toolchainFor(project)!!
        assertNotSame(first, changed)
        assertTrue(changed.buildTags.contains("cached_test_tag"))
        provider.invalidate()
        assertNotSame("a reanalyze looks at the disk again", changed, provider.toolchainFor(project))
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

    /** Cgo support of Build Tags decides the `cgo` tag of the analysis; Default keeps what `go env` says. */
    fun testCgoSupportDecidesTheCgoTag() {
        val provider = GoToolchainProvider.getInstance()
        val environment = provider.toolchainFor(project) ?: return // no Go on this machine
        settings.cgoMode = GoCgoMode.DISABLED
        assertFalse(provider.toolchainFor(project)!!.buildContext.matchTag("cgo"))
        settings.cgoMode = GoCgoMode.ENABLED
        assertTrue(provider.toolchainFor(project)!!.buildContext.matchTag("cgo"))
        settings.cgoMode = GoCgoMode.DEFAULT
        assertEquals(environment.cgoEnabled, provider.toolchainFor(project)!!.buildContext.matchTag("cgo"))
    }

    /** Experiments of Build Tags are the `goexperiment.X` tags of the analysis; `noX` switches one off. */
    fun testExperimentsAreGoexperimentTags() {
        val provider = GoToolchainProvider.getInstance()
        provider.toolchainFor(project) ?: return
        settings.goExperiments = "rangefunc, nofieldtrack"
        val context = provider.toolchainFor(project)!!.buildContext
        assertTrue(context.matchTag("goexperiment.rangefunc"))
        assertFalse(context.matchTag("goexperiment.fieldtrack"))
        settings.goExperiments = ""
        assertEquals("the environment decides again", provider.toolchainFor(project)!!.env["GOEXPERIMENT"]?.split(',')?.contains("rangefunc") == true,
            provider.toolchainFor(project)!!.buildContext.matchTag("goexperiment.rangefunc"))
    }

    /** What the analysis sees: a `//go:build cgo` file is in the package with Cgo support on and ignored with it off. */
    fun testACgoFileFollowsTheSetting() {
        GoToolchainProvider.getInstance().toolchainFor(project) ?: return
        myFixture.addFileToProject("cgoonly/plain.go", "package cgoonly\n")
        val cgoFile = myFixture.addFileToProject("cgoonly/native.go", "//go:build cgo\n\npackage cgoonly\n").virtualFile
        val experimentFile = myFixture.addFileToProject("cgoonly/iter.go", "//go:build goexperiment.rangefunc\n\npackage cgoonly\n").virtualFile
        val dir = cgoFile.parent
        fun files() = GoPackageResolver.getInstance(project).packageOf(dir, null)!!.goFiles
        settings.cgoMode = GoCgoMode.DISABLED
        settings.goExperiments = ""
        assertFalse(cgoFile in files())
        assertFalse(experimentFile in files())
        settings.cgoMode = GoCgoMode.ENABLED
        settings.goExperiments = "rangefunc"
        assertTrue(cgoFile in files())
        assertTrue(experimentFile in files())
        assertEquals(GoPackageResolver.getInstance(project).packageOf(dir, null)!!.ignoredFiles, emptyList<Any>())
    }

    /** The go commands of the plugin get CGO_ENABLED / GOEXPERIMENT of the settings; `go env` does not (its answer is what Default shows). */
    fun testTheGoCommandsGetTheEnvironmentOfTheSettings() {
        settings.cgoMode = GoCgoMode.DEFAULT
        settings.goExperiments = ""
        assertEquals(emptyMap<String, String>(), GoCli.buildEnvironment("build"))
        settings.cgoMode = GoCgoMode.DISABLED
        settings.goExperiments = "rangefunc"
        assertEquals(mapOf("CGO_ENABLED" to "0", "GOEXPERIMENT" to "rangefunc"), GoCli.buildEnvironment("test"))
        assertEquals(emptyMap<String, String>(), GoCli.buildEnvironment("env"))
        settings.cgoMode = GoCgoMode.ENABLED
        assertEquals("1", GoCli.buildEnvironment("run")["CGO_ENABLED"])
    }

    fun testTheWidgetSaysWhenCgoIsOff() {
        assertEquals("linux/amd64 · cgo off", GoPlatformChoices.text("linux", "amd64", emptyList(), cgoOff = true))
        assertEquals("linux/amd64 · a,b", GoPlatformChoices.text("linux", "amd64", listOf("a", "b")))
    }
}
