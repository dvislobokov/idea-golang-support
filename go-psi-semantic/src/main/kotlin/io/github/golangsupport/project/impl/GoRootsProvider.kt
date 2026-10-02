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
 * What is exposed is decided by [GoLibraryRootsPolicy] (the registry key `gopsi.libraryRoots` by default, a setting of the host
 * plugin otherwise): nothing, the standard library, or both. Disabled in unit-test mode unless [enableInTests] is set, so light
 * test projects do not index GOROOT. The roots of the last answer are remembered per project, so that [scheduleRootsUpdate] can
 * tell the platform what changed after the project model or the policy changes.
 */
@ApiStatus.Internal
class GoRootsProvider : AdditionalLibraryRootsProvider() {

    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> {
        // computed even when disabled (empty roots): a later change of the policy is then a change the platform is told about
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

    override fun getRootsToWatch(project: Project): Collection<VirtualFile> = computeRoots(project).all()

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
        private const val REGISTRY_INCLUDE_CMD = "gopsi.libraryRoots.includeCmd"
        private val LAST_ROOTS = Key.create<List<VirtualFile>>("gopsi.libraryRoots.last")

        @Volatile
        private var enabledInTests = false

        @TestOnly
        @JvmStatic
        fun enableInTests(enabled: Boolean) {
            enabledInTests = enabled
        }

        /** The mode of the policy for [project]; [GoLibraryRootsMode.NONE] in unit tests unless [enableInTests]. */
        @JvmStatic
        fun modeFor(project: Project): GoLibraryRootsMode {
            if (ApplicationManager.getApplication().isUnitTestMode && !enabledInTests) return GoLibraryRootsMode.NONE
            return GoLibraryRootsPolicy.getInstance().modeFor(project)
        }

        @JvmStatic
        fun isEnabled(project: Project): Boolean = modeFor(project) != GoLibraryRootsMode.NONE

        private fun includeCmd(): Boolean = registryFlag(REGISTRY_INCLUDE_CMD, false)

        private fun registryFlag(key: String, default: Boolean): Boolean =
            runCatching { Registry.`is`(key, default) }.getOrDefault(default)

        /** What the policy allows of: GOROOT/src, plus every non-main module directory of the content-root graphs that lies outside the project. */
        @JvmStatic
        fun computeRoots(project: Project): Roots {
            val mode = modeFor(project)
            if (mode == GoLibraryRootsMode.NONE) return Roots(null, emptyList())
            val toolchain = GoToolchainProvider.getInstance().toolchainFor(project)
            val lfs = LocalFileSystem.getInstance()
            val goroot = toolchain?.gorootSrc?.let { lfs.findFileByNioFile(it) }
            if (mode == GoLibraryRootsMode.STANDARD_LIBRARY) return Roots(goroot, emptyList())
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
         * Recomputes the roots in the background after a project model or policy change and notifies the platform when they
         * differ. Never blocks the EDT. Nothing happens while the platform has not asked yet (no last answer): the first query
         * computes the current roots anyway.
         */
        @JvmStatic
        fun scheduleRootsUpdate(project: Project) {
            if (project.isDisposed) return
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
