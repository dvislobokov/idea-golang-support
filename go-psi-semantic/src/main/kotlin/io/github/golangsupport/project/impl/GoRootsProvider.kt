package io.github.golangsupport.project.impl

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsListener
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.golangsupport.project.api.GoModuleGraphProvider
import io.github.golangsupport.project.api.GoToolchainProvider
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.TestOnly

/**
 * Exposes `$GOROOT/src` and the module directories of the project's build lists as synthetic
 * libraries, so they are indexed (stubs, imports) and navigable. `testdata` directories (and
 * `$GOROOT/src/cmd` unless `gopsi.libraryRoots.includeCmd`) are excluded.
 *
 * Guarded by the registry key `gopsi.libraryRoots` (default true). Disabled in unit-test mode
 * unless [enableInTests] is set, so light test projects do not index GOROOT.
 */
@ApiStatus.Internal
class GoRootsProvider : AdditionalLibraryRootsProvider() {

    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> {
        if (!isEnabled()) return emptyList()
        val roots = computeRoots(project)
        val result = mutableListOf<SyntheticLibrary>()
        roots.goroot?.let { src ->
            result += SyntheticLibrary.newImmutableLibrary("gopsi.goroot", listOf(src), emptyList(), emptySet(), excludeCondition(src, excludeCmd = !includeCmd()))
        }
        if (roots.modules.isNotEmpty()) {
            result += SyntheticLibrary.newImmutableLibrary("gopsi.modules", roots.modules, emptyList(), emptySet(), excludeCondition(null, excludeCmd = false))
        }
        project.putUserData(LAST_ROOTS, roots.all())
        return result
    }

    override fun getRootsToWatch(project: Project): Collection<VirtualFile> {
        if (!isEnabled()) return emptyList()
        return computeRoots(project).all()
    }

    private fun excludeCondition(gorootSrc: VirtualFile?, excludeCmd: Boolean): SyntheticLibrary.ExcludeFileCondition =
        // (isDir, name, isRoot, isStrictRootChild, hasParentNotGrandparent)
        SyntheticLibrary.ExcludeFileCondition { isDir, filename, _, isStrictRootChild, _ ->
            if (!isDir) return@ExcludeFileCondition false
            if (filename == "testdata") return@ExcludeFileCondition true
            excludeCmd && gorootSrc != null && filename == "cmd" && isStrictRootChild.asBoolean
        }

    data class Roots(val goroot: VirtualFile?, val modules: List<VirtualFile>) {
        fun all(): List<VirtualFile> = listOfNotNull(goroot) + modules
    }

    companion object {
        private const val REGISTRY_KEY = "gopsi.libraryRoots"
        private const val REGISTRY_INCLUDE_CMD = "gopsi.libraryRoots.includeCmd"
        private val LAST_ROOTS = Key.create<List<VirtualFile>>("gopsi.libraryRoots.last")

        @Volatile
        private var enabledInTests = false

        @TestOnly
        @JvmStatic
        fun enableInTests(enabled: Boolean) {
            enabledInTests = enabled
        }

        @JvmStatic
        fun isEnabled(): Boolean {
            if (ApplicationManager.getApplication().isUnitTestMode && !enabledInTests) return false
            return registryFlag(REGISTRY_KEY, true)
        }

        private fun includeCmd(): Boolean = registryFlag(REGISTRY_INCLUDE_CMD, false)

        private fun registryFlag(key: String, default: Boolean): Boolean =
            runCatching { Registry.`is`(key, default) }.getOrDefault(default)

        /** GOROOT/src plus every non-main module directory of the content-root graphs that lies outside the project. */
        @JvmStatic
        fun computeRoots(project: Project): Roots {
            val toolchain = GoToolchainProvider.getInstance().toolchainFor(project)
            val lfs = LocalFileSystem.getInstance()
            val goroot = toolchain?.gorootSrc?.let { lfs.findFileByNioFile(it) }
            val provider = GoModuleGraphProvider.getInstance(project) as? DefaultGoModuleGraphProvider ?: return Roots(goroot, emptyList())
            val contentRoots = com.intellij.openapi.roots.ProjectRootManager.getInstance(project).contentRoots
            val modules = provider.projectGraphs()
                .flatMap { g -> g.modules.filter { !it.isMain && !g.vendorMode } }
                .mapNotNull { m -> m.dir?.let { lfs.findFileByNioFile(it) } }
                .filter { dir -> contentRoots.none { VfsUtilCore.isAncestor(it, dir, false) } }
                .distinct()
            return Roots(goroot, modules)
        }

        /**
         * Recomputes the roots in the background after a project model change and notifies the
         * platform when they differ. Never blocks the EDT.
         */
        @JvmStatic
        fun scheduleRootsUpdate(project: Project) {
            if (!isEnabled() || project.isDisposed) return
            val old = project.getUserData(LAST_ROOTS) ?: return
            ReadAction.nonBlocking<List<VirtualFile>> { computeRoots(project).all() }
                .expireWith(project)
                .finishOnUiThread(ModalityState.nonModal()) { new ->
                    if (new != old && !project.isDisposed) {
                        project.putUserData(LAST_ROOTS, new)
                        WriteAction.run<RuntimeException> {
                            AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(project, "Go", old, new, "gopsi")
                        }
                    }
                }
                .submit(AppExecutorUtil.getAppExecutorService())
        }
    }
}
