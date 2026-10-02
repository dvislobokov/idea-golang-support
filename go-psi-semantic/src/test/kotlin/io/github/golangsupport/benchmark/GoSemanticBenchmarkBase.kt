package io.github.golangsupport.benchmark

import com.intellij.openapi.util.RecursionManager
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.psi.PsiManager
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.project.GoProjectModelTestBase
import io.github.golangsupport.project.ProjectTestUtil
import io.github.golangsupport.semantic.cache.GoTrackers

/**
 * Base of the semantic benchmarks. The inputs are the real `$GOROOT/src` files (opened through the
 * VFS like the resolve corpus does, never copied), resolved against the pinned linux/amd64
 * toolchain of [GoProjectModelTestBase].
 *
 * "Cold" bumps the Go trackers before every iteration, which discards the package-level and the
 * per-file caches of the files under test; the stubs of other files stay cached (as they do in an
 * IDE session, where indexing happens once).
 */
abstract class GoSemanticBenchmarkBase : GoProjectModelTestBase() {

    override fun setUp() {
        super.setUp()
        RecursionManager.disableAssertOnRecursionPrevention(testRootDisposable)
        RecursionManager.disableMissedCacheAssertions(testRootDisposable)
    }

    protected fun gorootFile(relative: String): GoFile {
        val vf = vfs(ProjectTestUtil.goroot().resolve("src").resolve(relative))
        return PsiManager.getInstance(project).findFile(vf) as? GoFile ?: error("not a Go PSI file: $relative")
    }

    protected fun invalidate(files: List<GoFile>) {
        val trackers = GoTrackers.getInstance(project)
        trackers.invalidateAll()
        trackers.anyGoChange.incModificationCount()
        for (file in files) (trackers.forFile(file) as SimpleModificationTracker).incModificationCount()
    }

    protected val inputs = listOf("net/http/server.go", "go/types/expr.go")
}
