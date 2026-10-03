package io.github.golangsupport.problems

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import io.github.golangsupport.ci.GoInspectFiles
import io.github.golangsupport.lang.GoFileType
import io.github.golangsupport.lang.index.GoFileImportsIndex
import io.github.golangsupport.mod.GoModFile
import io.github.golangsupport.project.api.GoPackageResolver

/**
 * What the background analysis of the project reads ([GoProjectProblems]), in one place: the files `go` builds in project content, as
 * `go-inspect` reads them ([GoInspectFiles]: no vendor, testdata, `.x`, `_x`, node_modules; generated files and the build constraints are
 * checked on the text, in [io.github.golangsupport.ci.GoInspectRun.inspectFile]).
 *
 * The files come from the platform's indices (file type and file name), not from a walk of the directories: a directory without Go files
 * (node_modules, build, a frontend, .git) is never entered, and the platform keeps the answer current on every VFS change. Library roots,
 * the module cache and excluded directories are outside [GlobalSearchScope.projectScope].
 */
object GoProblemsScope {
    /** Pure: a path relative to its content root (`/` between segments) names a `.go` / go.mod / go.work file outside the directories `go` skips. */
    fun isAnalysedPath(relativePath: String): Boolean {
        val segments = relativePath.split('/').filter { it.isNotEmpty() }
        return segments.isNotEmpty() && GoInspectFiles.isCandidate(segments.last()) && segments.dropLast(1).none(GoInspectFiles::skipDirectory)
    }

    /** [file] is project content, not library or excluded, and [isAnalysedPath] from its content root. Under a read action. */
    fun isAnalysed(project: Project, file: VirtualFile): Boolean {
        if (!file.isValid || file.isDirectory || !GoInspectFiles.isCandidate(file.name)) return false
        val index = ProjectFileIndex.getInstance(project)
        if (!index.isInContent(file) || index.isInLibrary(file) || index.isExcluded(file)) return false
        val root = index.getContentRootForFile(file) ?: return false
        return isAnalysedPath(VfsUtilCore.getRelativePath(file, root, '/') ?: return false)
    }

    /** The analysed files of the project, or of [under] only, sorted by path. Under a read action in smart mode (the indices answer). */
    fun files(project: Project, under: VirtualFile? = null): List<VirtualFile> {
        val projectScope = GlobalSearchScope.projectScope(project)
        val scope = if (under == null) projectScope else GlobalSearchScopesCore.directoryScope(project, under, true).intersectWith(projectScope)
        val found = LinkedHashSet<VirtualFile>(FileTypeIndex.getFiles(GoFileType, scope))
        for (name in listOf("go.mod", "go.work")) found += FilenameIndex.getVirtualFilesByName(name, scope)
        return found.filter { isAnalysed(project, it) }.sortedBy { it.path }
    }

    /**
     * Who may change when the package in [dir] changes: the other Go files of the package (and of its `_test` package) and every file of the
     * project that imports it ([GoFileImportsIndex], lexer-based, no PSI). [except] is the changed file itself. Under a read action in smart mode.
     */
    fun dependents(project: Project, dir: VirtualFile, except: VirtualFile? = null): List<VirtualFile> {
        if (!dir.isValid || !dir.isDirectory) return emptyList()
        val found = LinkedHashSet<VirtualFile>()
        dir.children.filterTo(found) { !it.isDirectory && it.name.endsWith(".go") }
        importPath(project, dir)?.let { found += importers(project, it) }
        return found.filter { it != except && isAnalysed(project, it) }.sortedBy { it.path }
    }

    /** The analysed files of the project that import [importPath]. Under a read action in smart mode. */
    fun importers(project: Project, importPath: String): List<VirtualFile> =
        GoFileImportsIndex.filesImporting(importPath, project, GlobalSearchScope.projectScope(project)).filter { isAnalysed(project, it) }.sortedBy { it.path }

    /** The project model's import path of [dir]; without one (no toolchain, a model not loaded yet), the `module` of the nearest go.mod and the way down from it. */
    fun importPath(project: Project, dir: VirtualFile): String? {
        GoPackageResolver.getInstance(project).importPathOf(dir)?.let { return it }
        val root = ProjectFileIndex.getInstance(project).getContentRootForFile(dir)
        var module: VirtualFile? = dir
        while (module != null && module.findChild("go.mod") == null) module = if (module == root) null else module.parent
        val goMod = module?.findChild("go.mod") ?: return null
        val text = FileDocumentManager.getInstance().getCachedDocument(goMod)?.immutableCharSequence ?: runCatching { LoadTextUtil.loadText(goMod) }.getOrNull() ?: return null
        return importPath(GoModFile.parse(text).modulePath ?: return null, VfsUtilCore.getRelativePath(dir, module, '/') ?: return null)
    }

    /** Pure: the import path of the directory [relativeDir] (`""` is the module root) of the module [modulePath]. */
    fun importPath(modulePath: String, relativeDir: String): String = if (relativeDir.isEmpty()) modulePath else "$modulePath/$relativeDir"
}
