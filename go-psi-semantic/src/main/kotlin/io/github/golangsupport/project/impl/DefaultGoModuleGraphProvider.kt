package io.github.golangsupport.project.impl

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import io.github.golangsupport.project.api.GoModuleGraph
import io.github.golangsupport.project.api.GoModuleGraphProvider
import io.github.golangsupport.project.api.GoToolchainInfo
import io.github.golangsupport.project.api.GoToolchainProvider
import org.jetbrains.annotations.ApiStatus
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Default [GoModuleGraphProvider]: pure resolution through [GoModuleGraphBuilder], cached per
 * go.mod/go.work location on the VFS stamps of go.mod, go.work, go.sum and vendor/modules.txt and
 * on [GoProjectModelTracker].
 *
 * When the pure graph misses modules (go.mod files absent from the module cache) and a `go`
 * binary is known, `go list -m -json -e all` runs once per input state on a pooled thread; its
 * result replaces the pure graph after the tracker is bumped. The choice is logged at INFO level.
 */
@ApiStatus.Internal
class DefaultGoModuleGraphProvider(private val project: Project) : GoModuleGraphProvider {

    private val graphs = ConcurrentHashMap<GoModuleGraphBuilder.Location, CachedValue<GoModuleGraph?>>()
    private val goListResults = ConcurrentHashMap<GoModuleGraphBuilder.Location, Pair<String, GoModuleGraph>>()
    private val goListRunning = ConcurrentHashMap.newKeySet<String>()

    override fun graphFor(fileOrDirectory: VirtualFile): GoModuleGraph? {
        val path = fileOrDirectory.fileSystem.getNioPath(fileOrDirectory) ?: return null
        val dir = if (fileOrDirectory.isDirectory) path else path.parent ?: return null
        val toolchain = GoToolchainProvider.getInstance().toolchainFor(project)
        val location = builder(toolchain).locate(dir)
        if (location.goMod == null && location.goWork == null) return null
        val cached = graphs.computeIfAbsent(location) { loc ->
            CachedValuesManager.getManager(project).createCachedValue {
                val tc = GoToolchainProvider.getInstance().toolchainFor(project)
                val graph = compute(loc, tc)
                val inputs = loc.inputs(graph?.mainModules?.mapNotNull { it.dir }.orEmpty())
                val deps = mutableListOf<Any>(GoProjectModelTracker.getInstance(project), toolchainTracker())
                inputs.mapNotNullTo(deps) { LocalFileSystem.getInstance().findFileByNioFile(it) }
                CachedValueProvider.Result.create(graph, *deps.toTypedArray())
            }
        }
        return cached.value
    }

    /** Graphs of the modules/workspaces at the project's content roots. */
    fun projectGraphs(): List<GoModuleGraph> =
        ProjectRootManager.getInstance(project).contentRoots
            .filter { it.findChild("go.mod") != null || it.findChild("go.work") != null }
            .mapNotNull { graphFor(it) }
            .distinctBy { g -> g.mainModules.map { it.dir } }

    private fun toolchainTracker(): ModificationTracker = GoToolchainProvider.getInstance().modificationTracker

    private fun builder(toolchain: GoToolchainInfo?): GoModuleGraphBuilder =
        GoModuleGraphBuilder(toolchain?.gomodcache?.let(::GoModuleCacheLayout), toolchain?.env.orEmpty())

    private fun compute(location: GoModuleGraphBuilder.Location, toolchain: GoToolchainInfo?): GoModuleGraph? {
        val pure = builder(toolchain).build(location) ?: return null
        if (pure.missing.isEmpty() || pure.vendorMode) {
            if (LOG.isDebugEnabled) LOG.debug("go-psi: module graph for ${location.goMod ?: location.goWork} by pure MVS (${pure.modules.size} modules)")
            return pure
        }
        val binary = toolchain?.goBinary
        if (binary == null) {
            LOG.info("go-psi: module graph for ${location.goMod ?: location.goWork}: ${pure.missing.size} module(s) missing from the cache and no go binary; using the incomplete pure graph (run 'go mod download')")
            return pure
        }
        val key = stampKey(location, pure)
        goListResults[location]?.takeIf { it.first == key }?.let {
            LOG.info("go-psi: module graph for ${location.goMod ?: location.goWork} from 'go list -m -json all' (${it.second.modules.size} modules; pure MVS missed ${pure.missing.size})")
            return it.second
        }
        val runDir = location.goWork?.parent ?: location.goMod?.parent ?: return pure
        if (goListRunning.add(key)) {
            LOG.info("go-psi: pure MVS missed ${pure.missing.size} module(s) (first: ${pure.missing.first()}); running 'go list -m -json all' in $runDir in the background")
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val graph = GoListModuleGraph.load(binary, runDir)
                    if (graph != null) {
                        goListResults[location] = key to graph
                        if (!project.isDisposed) GoProjectModelTracker.getInstance(project).incModificationCount()
                    }
                } finally {
                    goListRunning.remove(key)
                }
            }
        }
        return pure
    }

    private fun stampKey(location: GoModuleGraphBuilder.Location, graph: GoModuleGraph): String =
        location.inputs(graph.mainModules.mapNotNull { it.dir }).joinToString("|") { p ->
            val stamp = runCatching { Files.getLastModifiedTime(p).toMillis() }.getOrDefault(-1L)
            "$p=$stamp"
        }

    companion object {
        private val LOG = logger<DefaultGoModuleGraphProvider>()

        /** Directory of [file] as a NIO path (local file system only). */
        @JvmStatic
        fun nioPath(file: VirtualFile): Path? = file.fileSystem.getNioPath(file)
    }
}
