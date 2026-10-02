package io.github.golangsupport.project

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.testFramework.replaceService
import io.github.golangsupport.project.api.GoPackageResolver
import io.github.golangsupport.project.api.GoToolchainProvider
import io.github.golangsupport.project.impl.DefaultGoPackageResolver
import io.github.golangsupport.project.impl.DefaultGoToolchainProvider
import io.github.golangsupport.project.impl.GoProjectModelTracker

/** `DefaultGoPackageResolver.importPathOf` is cached per directory. */
class ImportPathCacheTest : GoProjectModelTestBase() {

    private val resolver: DefaultGoPackageResolver get() = GoPackageResolver.getInstance(project) as DefaultGoPackageResolver

    fun testCachedBetweenCalls() {
        val dir = fixture("simple/internal/util")
        assertEquals("example.com/simple/internal/util", resolver.importPathOf(dir))
        val count = resolver.importPathComputations
        repeat(5) {
            assertEquals("example.com/simple/internal/util", resolver.importPathOf(dir))
            assertEquals("example.com/simple/internal/util", resolver.importPathOf(fixture("simple/internal/util/util.go")))
        }
        assertEquals(count, resolver.importPathComputations)
    }

    fun testNullResultIsCached() {
        val dir = vfs(ProjectTestUtil.testDataPath())
        val first = resolver.importPathOf(dir)
        val count = resolver.importPathComputations
        assertEquals(first, resolver.importPathOf(dir))
        assertEquals(count, resolver.importPathComputations)
    }

    fun testRecomputedAfterProjectModelChange() {
        val dir = fixture("simple/pkg/lib")
        resolver.importPathOf(dir)
        val count = resolver.importPathComputations
        GoProjectModelTracker.getInstance(project).incModificationCount()
        assertEquals("example.com/simple/pkg/lib", resolver.importPathOf(dir))
        assertEquals(count + 1, resolver.importPathComputations)
        resolver.importPathOf(dir)
        assertEquals(count + 1, resolver.importPathComputations)
    }

    fun testRecomputedAfterToolchainChange() {
        val provider = DefaultGoToolchainProvider()
        ApplicationManager.getApplication().replaceService(GoToolchainProvider::class.java, provider, testRootDisposable)
        val dir = fixture("simple/pkg/lib")
        resolver.importPathOf(dir)
        val count = resolver.importPathComputations
        resolver.importPathOf(dir)
        assertEquals(count, resolver.importPathComputations)
        provider.invalidate()
        resolver.importPathOf(dir)
        assertEquals(count + 1, resolver.importPathComputations)
    }

    fun testDeletedDirectoryIsDropped() {
        val root = myFixture.tempDirFixture.findOrCreateDir("gone")
        val before = resolver.importPathCacheSize
        resolver.importPathOf(root)
        assertEquals(before + 1, resolver.importPathCacheSize)
        runWriteAction { root.delete(this) }
        assertNull(resolver.importPathOf(root))
        assertEquals(before, resolver.importPathCacheSize)
    }
}
