package io.github.golangsupport.ide.completion

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import io.github.golangsupport.project.api.GoModule
import io.github.golangsupport.project.api.GoModuleGraphProvider
import io.github.golangsupport.project.api.GoToolchainProvider
import io.github.golangsupport.project.impl.GoProjectModelTracker
import io.github.golangsupport.semantic.scope.GoScopes
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Importable package paths for import-path completion and unimported-package completion:
 *
 * - the standard library: a directory walk of `$GOROOT/src` (no `internal`, `vendor`, `cmd`,
 *   `testdata`), cached per GOROOT for the session (GOROOT content does not change);
 * - the modules of the build list: each module directory is walked once and cached by
 *   directory (module cache directories are immutable); `internal` packages only of main modules;
 * - main (workspace) modules: walked per query and cached on the project-model tracker and VFS
 *   structure changes.
 *
 * The walks read directory listings only (no Go files are parsed).
 */
object GoImportPaths {

    /** An importable package: [path] and the name it is most likely declared with. */
    data class Entry(val path: String, val name: String, val isStd: Boolean, val module: String?)

    private val stdCache = ConcurrentHashMap<Path, List<Entry>>()
    private val moduleCache = ConcurrentHashMap<Path, List<String>>()
    private const val MAX_DEPTH = 10

    /** Every importable package seen from [file]: standard library first, then modules in build-list order. */
    fun all(project: Project, file: VirtualFile?): List<Entry> = std(project) + modules(project, file)

    fun std(project: Project): List<Entry> {
        val src = GoToolchainProvider.getInstance().toolchainFor(project)?.gorootSrc ?: return emptyList()
        if (!src.isDirectory()) return emptyList()
        return stdCache.computeIfAbsent(src) { root ->
            walk(root, skipInternal = true, skipTopLevel = setOf("cmd", "vendor", "builtin"))
                .map { Entry(it, GoScopes.defaultImportName(it), true, null) }
        }
    }

    /**
     * Main (workspace) modules first, then dependencies. The two halves are cached separately:
     * dependency packages live in the immutable module cache, so they depend on the project-model
     * tracker only (a new dependency changes go.mod/go.sum and bumps it); workspace packages are
     * re-walked on VFS structure changes (a new directory may be a new package), which is cheap
     * compared to a dependency walk and does not invalidate the dependency list.
     */
    fun modules(project: Project, file: VirtualFile?): List<Entry> {
        val dir = file?.let { if (it.isDirectory) it else it.parent } ?: return emptyList()
        if (dir.fileSystem.protocol != "file") return emptyList()
        val manager = CachedValuesManager.getManager(project)
        val tracker = GoProjectModelTracker.getInstance(project)
        val main = manager.getCachedValue(project, keyFor("main:" + dir.path), {
            CachedValueProvider.Result.create(computeMainModules(project, dir), tracker, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
        }, false)
        val dependencies = manager.getCachedValue(project, keyFor("deps:" + dir.path), {
            CachedValueProvider.Result.create(computeDependencyModules(project, dir), tracker)
        }, false)
        if (dependencies.isEmpty()) return main
        if (main.isEmpty()) return dependencies
        val seen = main.mapTo(HashSet()) { it.path }
        return main + dependencies.filter { seen.add(it.path) }
    }

    private val keys = ConcurrentHashMap<String, Key<CachedValue<List<Entry>>>>()

    private fun keyFor(path: String): Key<CachedValue<List<Entry>>> = keys.computeIfAbsent(path) { Key.create("gopsi.completion.modulePackages:$it") }

    private fun entries(module: GoModule, relatives: List<String>, seen: MutableSet<String>, out: MutableList<Entry>) {
        for (relative in relatives) {
            val path = if (relative.isEmpty()) module.path else module.path + "/" + relative
            if (seen.add(path)) out += Entry(path, GoScopes.defaultImportName(path), false, module.path)
        }
    }

    private fun computeMainModules(project: Project, dir: VirtualFile): List<Entry> {
        val graph = GoModuleGraphProvider.getInstance(project).graphFor(dir) ?: return emptyList()
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        for (module in graph.mainModules) {
            val root = module.dir ?: continue
            // Main modules change while editing: walked per cached value, not cached by directory.
            entries(module, walk(root, skipInternal = false, skipTopLevel = setOf("vendor")), seen, out)
        }
        return out
    }

    private fun computeDependencyModules(project: Project, dir: VirtualFile): List<Entry> {
        val graph = GoModuleGraphProvider.getInstance(project).graphFor(dir) ?: return emptyList()
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        for (module in graph.modules) {
            if (module.isMain || module.isWorkspaceMember) continue
            val root = module.dir ?: continue
            entries(module, moduleCache.computeIfAbsent(root) { walk(it, skipInternal = true, skipTopLevel = emptySet()) }, seen, out)
        }
        return out
    }

    /** Relative paths (`/`-separated, "" for the root) of directories under [root] holding non-test `.go` files. */
    private fun walk(root: Path, skipInternal: Boolean, skipTopLevel: Set<String>): List<String> {
        val result = ArrayList<String>()
        fun visit(dir: Path, relative: String, depth: Int) {
            if (depth > MAX_DEPTH) return
            val children = try {
                Files.newDirectoryStream(dir).use { it.toList() }
            } catch (_: Exception) {
                return
            }
            var hasGo = false
            val subdirs = ArrayList<Path>()
            for (child in children) {
                val name = child.name
                if (child.isDirectory()) {
                    subdirs.add(child)
                } else if (name.endsWith(".go") && !name.endsWith("_test.go") && !name.startsWith("_") && !name.startsWith(".")) {
                    hasGo = true
                }
            }
            if (hasGo) result += relative
            for (sub in subdirs.sortedBy { it.name }) {
                val name = sub.name
                if (name.startsWith(".") || name.startsWith("_") || name == "testdata") continue
                if (skipInternal && name == "internal") continue
                if (depth == 0 && name in skipTopLevel) continue
                // A nested module (own go.mod) is not part of this module.
                if (Files.exists(sub.resolve("go.mod"))) continue
                visit(sub, if (relative.isEmpty()) name else "$relative/$name", depth + 1)
            }
        }
        visit(root, "", 0)
        return result
    }
}
