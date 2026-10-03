package io.github.golangsupport.ide.inspections.project

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.indexing.FileBasedIndex
import io.github.golangsupport.lang.index.GoFileImportsIndex
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.stubs.index.GoPackagesIndex
import io.github.golangsupport.project.api.GoPackageResolver
import io.github.golangsupport.semantic.cache.GoTrackers

/**
 * Project packages and the import graph between them, for the project-wide checks. A project package is a directory in project
 * content outside `vendor/` (what [GoTrackers] tracks per package); GOROOT, the module cache and vendored code never take part.
 */
internal object GoProjectPackages {

    private val EDGES = Key.create<CachedValue<Map<VirtualFile, String>>>("gopsi.ide.projectImportEdges")
    private val CLOSED = Key.create<CachedValue<Boolean>>("gopsi.ide.closedPackage")

    fun isProjectPackage(project: Project, dir: VirtualFile): Boolean {
        val index = ProjectFileIndex.getInstance(project)
        if (!dir.isValid || !index.isInContent(dir) || index.isInLibrary(dir)) return false
        val root = index.getContentRootForFile(dir)
        var d: VirtualFile? = dir
        while (d != null && d != root) {
            if (d.name == "vendor") return false
            d = d.parent
        }
        return true
    }

    /** The import path of [dir] for messages; the directory name when the project model does not know it. */
    fun displayPath(project: Project, dir: VirtualFile): String = GoPackageResolver.getInstance(project).importPathOf(dir) ?: dir.name

    /**
     * The project packages imported by the non-test files of the package in [dir] (build constraints applied by the project model),
     * each with the import path as written. Cached on the directory until the package itself changes ([GoTrackers.ownPackageDependencies]:
     * its own out-of-block stamp, the file-set stamp, the model and roots): an edit elsewhere keeps it, so a DFS over the graph only
     * recomputes the packages that changed.
     */
    fun edges(project: Project, dir: VirtualFile): Map<VirtualFile, String> {
        val psiDir = PsiManager.getInstance(project).findDirectory(dir) ?: return emptyMap()
        return CachedValuesManager.getCachedValue(psiDir, EDGES) {
            CachedValueProvider.Result.create(computeEdges(project, dir), *GoTrackers.getInstance(project).ownPackageDependencies(psiDir))
        }
    }

    private fun computeEdges(project: Project, dir: VirtualFile): Map<VirtualFile, String> {
        val resolver = GoPackageResolver.getInstance(project)
        val pkg = resolver.packageOf(dir) ?: return emptyMap()
        val result = LinkedHashMap<VirtualFile, String>()
        for (vf in pkg.goFiles) for (path in importsOf(project, vf)) {
            val target = resolver.resolveImport(path, vf).packageOrNull?.directory ?: continue
            if (target !in result && isProjectPackage(project, target)) result[target] = path
        }
        return result
    }

    /**
     * Import paths of [vf] from [GoFileImportsIndex] (lexer-based: no PSI, no stub). An unsaved document, or the index during
     * indexing, falls back to the stub-backed `GoFile.imports`.
     */
    private fun importsOf(project: Project, vf: VirtualFile): Collection<String> {
        if (!FileDocumentManager.getInstance().isFileModified(vf)) {
            try {
                return FileBasedIndex.getInstance().getFileData(GoFileImportsIndex.NAME, vf, project).keys
            } catch (_: IndexNotReadyException) {
            }
        }
        return (PsiManager.getInstance(project).findFile(vf) as? GoFile)?.imports?.map { it.path } ?: emptyList()
    }

    /**
     * The shortest import chain from [start] back to [goal] over [edges] (both ends included), or null when [goal] is not reachable.
     * BFS: the shortest cycle is the one worth showing.
     */
    fun chain(project: Project, start: VirtualFile, goal: VirtualFile): List<VirtualFile>? {
        if (start == goal) return listOf(start)
        val parent = HashMap<VirtualFile, VirtualFile>()
        val queue = ArrayDeque<VirtualFile>()
        queue += start
        parent[start] = start
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            for (next in edges(project, p).keys) {
                if (next in parent) continue
                parent[next] = p
                if (next == goal) {
                    val path = ArrayList<VirtualFile>()
                    var c = next
                    while (c != start) { path += c; c = parent.getValue(c) }
                    path += start
                    return path.asReversed()
                }
                queue += next
            }
        }
        return null
    }

    /**
     * Whether every importer of the package in [dir] is project code, so a project-wide reference search sees every use of its
     * exported names: (1) its import path has an `internal` element (the go command allows importers only under the parent of that
     * element, which is inside the module), or (2) its module is a main or workspace module of the project with a `package main`
     * somewhere under the module root, i.e. an application rather than a library. Anything else may be imported by modules outside
     * the project. Cached on the directory, project-wide (a new `main` package anywhere can flip it).
     */
    fun isClosed(project: Project, dir: VirtualFile): Boolean {
        val psiDir = PsiManager.getInstance(project).findDirectory(dir) ?: return false
        return CachedValuesManager.getCachedValue(psiDir, CLOSED) {
            CachedValueProvider.Result.create(computeClosed(project, dir), *GoTrackers.getInstance(project).projectWideDependencies())
        }
    }

    private fun computeClosed(project: Project, dir: VirtualFile): Boolean {
        val pkg = GoPackageResolver.getInstance(project).packageOf(dir) ?: return false
        if (pkg.isStd || !isProjectPackage(project, dir)) return false
        val path = pkg.importPath
        if (path != null && (path == "internal" || path.startsWith("internal/") || path.endsWith("/internal") || "/internal/" in path)) return true
        val module = pkg.module ?: return false
        if (!module.isMain && !module.isWorkspaceMember) return false
        val root = module.dir?.let { LocalFileSystem.getInstance().findFileByNioFile(it) } ?: return false
        if (!VfsUtilCore.isAncestor(root, dir, false)) return false
        val scope = GlobalSearchScopesCore.directoryScope(project, root, true).intersectWith(GlobalSearchScope.projectScope(project))
        var found = false
        StubIndex.getInstance().processElements(GoPackagesIndex.KEY, "main", project, scope, GoFile::class.java) { f ->
            found = !f.isTestFile
            !found
        }
        return found
    }
}
