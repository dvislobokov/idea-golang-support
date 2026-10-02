package io.github.golangsupport.project

import com.intellij.openapi.util.registry.Registry
import io.github.golangsupport.project.impl.GoProjectModelTracker
import io.github.golangsupport.project.impl.GoRootsProvider

class GoRootsProviderTest : GoProjectModelTestBase() {

    fun testGorootLibraryExcludesTestdataAndCmd() {
        GoRootsProvider.enableInTests(true)
        try {
            val libraries = GoRootsProvider().getAdditionalProjectLibraries(project)
            val goroot = libraries.single { it.comparisonId == "gopsi.goroot" }
            val src = vfs(ProjectTestUtil.goroot().resolve("src"))
            assertEquals(listOf(src), goroot.sourceRoots.toList())
            assertTrue(goroot.contains(vfs(ProjectTestUtil.goroot().resolve("src/fmt/print.go"))))
            // The exclude condition is applied by indexing (SyntheticLibrary.contains ignores it).
            val excluded = goroot.unitedExcludeCondition!!
            assertFalse(excluded.value(vfs(ProjectTestUtil.goroot().resolve("src/fmt"))))
            assertTrue(excluded.value(vfs(ProjectTestUtil.goroot().resolve("src/go/build/testdata"))))
            assertTrue(excluded.value(vfs(ProjectTestUtil.goroot().resolve("src/cmd"))))
            assertFalse(excluded.value(vfs(ProjectTestUtil.goroot().resolve("src/go/build"))))
            // The light project has no go.mod content roots, hence no module library.
            assertNull(libraries.firstOrNull { it.comparisonId == "gopsi.modules" })
        } finally {
            GoRootsProvider.enableInTests(false)
        }
    }

    fun testDisabledByRegistryAndInTests() {
        assertTrue(GoRootsProvider().getAdditionalProjectLibraries(project).isEmpty())
        GoRootsProvider.enableInTests(true)
        try {
            Registry.get("gopsi.libraryRoots").setValue(false, testRootDisposable)
            assertTrue(GoRootsProvider().getAdditionalProjectLibraries(project).isEmpty())
        } finally {
            GoRootsProvider.enableInTests(false)
        }
    }

    fun testModelFileNames() {
        for (name in listOf("go.mod", "go.work", "go.sum", "go.work.sum")) assertTrue(name, GoProjectModelTracker.isModelFile(name, null))
        assertTrue(GoProjectModelTracker.isModelFile("modules.txt", "vendor"))
        assertFalse(GoProjectModelTracker.isModelFile("modules.txt", "docs"))
        assertFalse(GoProjectModelTracker.isModelFile("main.go", null))
        val tracker = GoProjectModelTracker.getInstance(project)
        val before = tracker.modificationCount
        tracker.incModificationCount()
        assertTrue(tracker.modificationCount > before)
    }
}
