package io.github.golangsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.project.impl.GoLibraryRootsMode
import io.github.golangsupport.project.impl.GoLibraryRootsPolicy
import io.github.golangsupport.sdk.GoIgsLibraryRootsPolicy
import io.github.golangsupport.settings.GoLibraryRoots
import io.github.golangsupport.settings.GoSettings

/** The library roots of the native PSI follow the setting of the plugin, not the registry key of go-psi. */
class GoLibraryRootsTest : BasePlatformTestCase() {
    private val settings get() = GoSettings.getInstance()
    private var libraryRoots = GoLibraryRoots.STANDARD_LIBRARY_AND_DEPENDENCIES

    override fun setUp() {
        super.setUp()
        libraryRoots = settings.libraryRoots
    }

    override fun tearDown() {
        try {
            settings.libraryRoots = libraryRoots
        } finally {
            super.tearDown()
        }
    }

    fun testThePluginsPolicyIsTheServiceOfTheModel() {
        assertTrue(GoLibraryRootsPolicy.getInstance().javaClass.name, GoLibraryRootsPolicy.getInstance() is GoIgsLibraryRootsPolicy)
    }

    fun testThePolicyFollowsTheSetting() {
        val policy = GoLibraryRootsPolicy.getInstance()
        settings.libraryRoots = GoLibraryRoots.STANDARD_LIBRARY
        assertEquals(GoLibraryRootsMode.STANDARD_LIBRARY, policy.modeFor(project))
        settings.libraryRoots = GoLibraryRoots.STANDARD_LIBRARY_AND_DEPENDENCIES
        assertEquals(GoLibraryRootsMode.STANDARD_LIBRARY_AND_DEPENDENCIES, policy.modeFor(project))
        // the enum keeps its English in the settings file whatever the language of the page
        assertEquals("Standard library", GoLibraryRoots.STANDARD_LIBRARY.toString())
    }
}
