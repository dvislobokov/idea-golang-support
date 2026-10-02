package io.github.golangsupport.project.impl

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import io.github.golangsupport.lang.lexer.GoFileHeaderScanner
import io.github.golangsupport.project.api.GoBuildConstraintEvaluator
import io.github.golangsupport.project.api.GoBuildContext
import io.github.golangsupport.project.api.GoImportResolution
import io.github.golangsupport.project.api.GoModule
import io.github.golangsupport.project.api.GoModuleGraph
import io.github.golangsupport.project.api.GoModuleGraphProvider
import io.github.golangsupport.project.api.GoPackage
import io.github.golangsupport.project.api.GoPackageResolver
import io.github.golangsupport.project.api.GoToolchainInfo
import io.github.golangsupport.project.api.GoToolchainProvider
import org.jetbrains.annotations.ApiStatus
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Default [GoPackageResolver]. Packages are partitioned from the file headers (lexer only, no
 * PSI) and cached per directory with [CachedValuesManager], depending on VFS structure changes,
 * the package's files, [GoProjectModelTracker] and the toolchain tracker.
 */
@ApiStatus.Internal
class DefaultGoPackageResolver(private val project: Project) : GoPackageResolver {

    /** Per-directory partitions; VirtualFile user data is shared between projects, so the map lives here. */
    private val packages = ConcurrentHashMap<VirtualFile, CachedValue<ConcurrentHashMap<GoBuildContext, Any>>>()

    /** Per-directory import paths (null value kept in [ImportPath]); VFS structure, model and toolchain changes drop them. */
    private val importPaths = ConcurrentHashMap<VirtualFile, CachedValue<ImportPath>>()

    /** Number of uncached import path computations; test hook. */
    @Volatile
    internal var importPathComputations: Int = 0
        private set

    @Volatile
    private var sweepAt = SWEEP_THRESHOLD

    /** Number of cached directories; test hook. */
    internal val importPathCacheSize: Int get() = importPaths.size

    private class ImportPath(val value: String?)

    private class GorootSrc(val path: Path, val dir: VirtualFile?)

    @Volatile
    private var gorootSrcValue: CachedValue<GorootSrc?>? = null

    @Volatile
    private var cacheLayoutMemo: GoModuleCacheLayout? = null

    override fun resolveImport(importPath: String, fromFile: VirtualFile): GoImportResolution {
        if (importPath == "C") return GoImportResolution.CPseudoPackage
        val fromDir = (if (fromFile.isDirectory) fromFile else fromFile.parent)
            ?: return GoImportResolution.Unresolved(importPath, "importer has no directory")
        if (importPath.isEmpty()) return GoImportResolution.Unresolved(importPath, "empty import path")
        val toolchain = toolchain()

        if (isLocalImport(importPath)) {
            val dir = fromDir.findFileByRelativePath(importPath)?.takeIf { it.isDirectory }
                ?: return GoImportResolution.Unresolved(importPath, "no directory $importPath relative to ${fromDir.path}")
            val pkg = packageOf(dir) ?: return GoImportResolution.Unresolved(importPath, "no Go files in ${dir.path}")
            return GoImportResolution.Resolved(pkg)
        }

        val dir = findPackageDir(importPath, fromDir, toolchain)
            ?: return GoImportResolution.Unresolved(importPath, unresolvedReason(importPath, fromDir, toolchain))
        val pkg = packageOf(dir) ?: return GoImportResolution.Unresolved(importPath, "no Go files in ${dir.path}")
        if (!internalAllowed(pkg, importPath, fromDir)) return GoImportResolution.InternalDenied(pkg)
        return GoImportResolution.Resolved(pkg)
    }

    override fun packageOf(directory: VirtualFile, context: GoBuildContext?): GoPackage? {
        if (!directory.isDirectory || !directory.isValid) return null
        val ctx = context ?: defaultContext()
        val perContext = packages.computeIfAbsent(directory) { dir ->
            CachedValuesManager.getManager(project).createCachedValue {
                val deps = mutableListOf<Any>(
                    VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                    GoProjectModelTracker.getInstance(project),
                    toolchainTracker(),
                )
                if (dir.isValid) deps.addAll(goFilesOf(dir))
                CachedValueProvider.Result.create(ConcurrentHashMap<GoBuildContext, Any>(), *deps.toTypedArray())
            }
        }.value
        val value = perContext.computeIfAbsent(ctx) { computePackage(directory, it) ?: NO_PACKAGE }
        return value as? GoPackage
    }

    override fun importPathOf(fileOrDirectory: VirtualFile): String? {
        val dir = (if (fileOrDirectory.isDirectory) fileOrDirectory else fileOrDirectory.parent) ?: return null
        if (!dir.isValid) {
            importPaths.remove(dir)
            return null
        }
        if (importPaths.size >= sweepAt) {
            importPaths.keys.removeIf { !it.isValid }
            sweepAt = maxOf(SWEEP_THRESHOLD, importPaths.size * 2)
        }
        return importPaths.computeIfAbsent(dir) { d ->
            CachedValuesManager.getManager(project).createCachedValue {
                CachedValueProvider.Result.create(
                    ImportPath(computeImportPath(d)),
                    VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                    GoProjectModelTracker.getInstance(project),
                    toolchainTracker(),
                )
            }
        }.value.value
    }

    private fun computeImportPath(dir: VirtualFile): String? {
        importPathComputations++
        val toolchain = toolchain()
        gorootSrc(toolchain)?.let { src ->
            if (VfsUtilCore.isAncestor(src, dir, false)) {
                val rel = VfsUtilCore.getRelativePath(dir, src, '/') ?: return null
                if (rel.isEmpty()) return null
                return if (rel.startsWith("cmd/vendor/")) rel.removePrefix("cmd/vendor/") else rel
            }
        }
        val path = DefaultGoModuleGraphProvider.nioPath(dir) ?: return null
        cacheLayout(toolchain)?.locate(path)?.let { loc ->
            return if (loc.relativePath.isEmpty()) loc.modulePath else loc.modulePath + "/" + loc.relativePath
        }
        val graph = GoModuleGraphProvider.getInstance(project).graphFor(dir)
        if (graph != null) {
            val main = graph.mainModules.filter { it.dir != null && path.startsWith(it.dir!!) }.maxByOrNull { it.dir!!.nameCount }
            if (main != null) {
                val rel = main.dir!!.relativize(path).joinToString("/") { it.toString() }
                if (rel.isEmpty()) return main.path
                if (rel.startsWith("vendor/") && graph.vendorDir != null && path.startsWith(graph.vendorDir!!)) return rel.removePrefix("vendor/")
                return main.path + "/" + rel
            }
            graph.vendorDir?.takeIf { path.startsWith(it) && path != it }?.let { vendor ->
                return vendor.relativize(path).joinToString("/") { it.toString() }
            }
        }
        for (gopath in toolchain?.gopath.orEmpty()) {
            val src = gopath.resolve("src")
            if (path.startsWith(src) && path != src) return src.relativize(path).joinToString("/") { it.toString() }
        }
        return null
    }

    // ---- lookup --------------------------------------------------------------------------------

    private fun findPackageDir(importPath: String, fromDir: VirtualFile, toolchain: GoToolchainInfo?): VirtualFile? {
        val src = gorootSrc(toolchain)
        // Importers inside GOROOT: std and cmd vendoring ($GOROOT/src/vendor, $GOROOT/src/cmd/vendor).
        if (src != null && VfsUtilCore.isAncestor(src, fromDir, false)) {
            val rel = VfsUtilCore.getRelativePath(fromDir, src, '/').orEmpty()
            val vendorRoot = if (rel == "cmd" || rel.startsWith("cmd/")) "cmd/vendor" else "vendor"
            src.findFileByRelativePath("$vendorRoot/$importPath")?.takeIf(::isPackageDir)?.let { return it }
            src.findFileByRelativePath(importPath)?.takeIf(::isPackageDir)?.let { return it }
            return null
        }
        // Standard library: the first path element has no dot.
        if (!importPath.substringBefore('/').contains('.')) {
            src?.findFileByRelativePath(importPath)?.takeIf(::isPackageDir)?.let { return it }
        }
        val graph = graphForImporter(fromDir, toolchain)
        if (graph != null) {
            if (graph.vendorMode) {
                for (main in graph.mainModules.sortedByDescending { it.path.length }) {
                    if (importPath != main.path && !importPath.startsWith(main.path + "/")) continue
                    val dir = main.dir?.resolve(importPath.removePrefix(main.path).trimStart('/'))?.let(::findDir) ?: continue
                    if (isPackageDir(dir) && !inNestedModule(dir, main.dir!!)) return dir
                }
                graph.vendorDir?.resolve(importPath)?.let(::findDir)?.takeIf(::isPackageDir)?.let { return it }
                return null
            }
            for (module in graph.modulesForImportPath(importPath)) {
                val base = module.dir ?: continue
                val dir = findDir(base.resolve(importPath.removePrefix(module.path).trimStart('/'))) ?: continue
                if (isPackageDir(dir) && !(module.isMain && inNestedModule(dir, base))) return dir
            }
            return null
        }
        for (gopath in toolchain?.gopath.orEmpty()) {
            findDir(gopath.resolve("src").resolve(importPath))?.takeIf(::isPackageDir)?.let { return it }
        }
        return null
    }

    private fun unresolvedReason(importPath: String, fromDir: VirtualFile, toolchain: GoToolchainInfo?): String {
        if (toolchain?.goroot == null) return "no Go toolchain (GOROOT) found"
        val graph = graphForImporter(fromDir, toolchain) ?: return "package $importPath is not in std (${toolchain.gorootSrc})"
        val module = graph.modulesForImportPath(importPath).firstOrNull()
            ?: return "no required module provides package $importPath"
        if (module.dir == null) return "module ${module.path}@${module.version} is not in the module cache (run 'go mod download')"
        return "module ${module.path} has no package $importPath"
    }

    /**
     * The module graph that governs imports from [dir]. For a module cache directory this is the
     * graph of a project module whose build list contains that module version, else the cached
     * module's own graph.
     */
    private fun graphForImporter(dir: VirtualFile, toolchain: GoToolchainInfo?): GoModuleGraph? {
        val provider = GoModuleGraphProvider.getInstance(project)
        val path = DefaultGoModuleGraphProvider.nioPath(dir)
        val located = path?.let { cacheLayout(toolchain)?.locate(it) }
        if (located != null && provider is DefaultGoModuleGraphProvider) {
            provider.projectGraphs().firstOrNull { it.module(located.modulePath)?.version == located.version }?.let { return it }
        }
        return provider.graphFor(dir)
    }

    /**
     * `internal` visibility (`cmd/go/internal/load.disallowInternal`): a package whose path has an
     * `internal` element is importable only from the tree rooted at the parent of that element.
     * Checked on directories (the importer must be inside the parent directory) and, for module
     * paths, on import paths; either one allows the import.
     */
    private fun internalAllowed(pkg: GoPackage, importPath: String, fromDir: VirtualFile): Boolean {
        val target = pkg.importPath ?: importPath
        val index = findInternal(target) ?: return true
        val parentPath = target.substring(0, index).trimEnd('/')
        val depth = target.substring(index).count { it == '/' } + 1
        var parentDir: VirtualFile? = pkg.directory
        repeat(depth) { parentDir = parentDir?.parent }
        val pd = parentDir
        if (pd != null && VfsUtilCore.isAncestor(pd, fromDir, false)) return true
        if (parentPath.isEmpty()) return false
        val importer = importPathOf(fromDir) ?: return false
        return importer == parentPath || importer.startsWith("$parentPath/")
    }

    /** Index of the last `internal` path element (`load.findInternal`), null when none. */
    private fun findInternal(path: String): Int? = when {
        path.endsWith("/internal") -> path.length - "internal".length
        path.contains("/internal/") -> path.lastIndexOf("/internal/") + 1
        path == "internal" || path.startsWith("internal/") -> 0
        else -> null
    }

    // ---- packages ------------------------------------------------------------------------------

    private fun computePackage(directory: VirtualFile, context: GoBuildContext): GoPackage? {
        val files = goFilesOf(directory)
        if (files.isEmpty()) return null
        val goFiles = mutableListOf<VirtualFile>()
        val testFiles = mutableListOf<VirtualFile>()
        val xTestFiles = mutableListOf<VirtualFile>()
        val ignored = mutableListOf<VirtualFile>()
        val candidates = mutableListOf<Triple<VirtualFile, String?, Boolean>>() // file, package name, is test
        for (file in files.sortedBy { it.name }) {
            val text = textOf(file)
            if (text == null || !GoBuildConstraintEvaluator.matchFile(file.name, text, context)) {
                ignored += file
                continue
            }
            val name = GoFileHeaderScanner.scan(text, withImports = false).packageName
            if (name == null || name == "documentation") {
                ignored += file
                continue
            }
            candidates += Triple(file, name, file.name.endsWith("_test.go"))
        }
        val packageName = candidates.firstOrNull { !it.third }?.second
            ?: candidates.firstOrNull()?.second?.removeSuffix("_test")
        for ((file, name, isTest) in candidates) {
            when {
                !isTest && name == packageName -> goFiles += file
                isTest && name == packageName -> testFiles += file
                isTest && name == "${packageName}_test" -> xTestFiles += file
                else -> ignored += file
            }
        }
        val toolchain = toolchain()
        val src = gorootSrc(toolchain)
        val isStd = src != null && VfsUtilCore.isAncestor(src, directory, false)
        return GoPackage(
            importPath = importPathOf(directory),
            name = packageName,
            directory = directory,
            module = if (isStd) null else moduleOf(directory, toolchain),
            goFiles = goFiles,
            testFiles = testFiles,
            xTestFiles = xTestFiles,
            ignoredFiles = ignored,
            isStd = isStd,
        )
    }

    private fun moduleOf(directory: VirtualFile, toolchain: GoToolchainInfo?): GoModule? {
        val path = DefaultGoModuleGraphProvider.nioPath(directory) ?: return null
        val graph = graphForImporter(directory, toolchain) ?: return null
        val cache = cacheLayout(toolchain)
        cache?.locate(path)?.let { loc ->
            graph.module(loc.modulePath)?.takeIf { it.version == loc.version }?.let { return it }
            // Not in a project build list: describe the cached module version itself.
            val goMod = cache.readGoMod(loc.modulePath, loc.version)
            val mod = goMod?.second?.let { GoModFileParser.parseGoMod(it) }
            return GoModule(
                path = loc.modulePath, version = loc.version, dir = cache.extractedDir(loc.modulePath, loc.version),
                goModFile = goMod?.first, goVersion = mod?.go, isMain = false, requires = mod?.requires.orEmpty(),
                replaces = mod?.replaces.orEmpty(), excludes = mod?.excludes.orEmpty(), retracts = mod?.retracts.orEmpty(),
                tools = mod?.tools.orEmpty(), deprecated = mod?.deprecated,
            )
        }
        if (graph.vendorMode && graph.vendorDir != null && path.startsWith(graph.vendorDir!!)) {
            val rel = graph.vendorDir!!.relativize(path).joinToString("/") { it.toString() }
            return graph.modulesForImportPath(rel).firstOrNull { !it.isMain }
        }
        return graph.modules.filter { it.dir != null && path.startsWith(it.dir!!) }.maxByOrNull { it.dir!!.nameCount }
    }

    private fun goFilesOf(directory: VirtualFile): List<VirtualFile> =
        directory.children.filter { !it.isDirectory && it.name.endsWith(".go") }

    private fun textOf(file: VirtualFile): CharSequence? {
        FileDocumentManager.getInstance().getCachedDocument(file)?.let { return it.immutableCharSequence }
        return try {
            VfsUtilCore.loadText(file)
        } catch (_: IOException) {
            null
        }
    }

    private fun isPackageDir(dir: VirtualFile): Boolean = dir.isDirectory && dir.children.any { !it.isDirectory && it.name.endsWith(".go") }

    /** Whether a go.mod exists between [dir] (inclusive) and [moduleRoot] (exclusive). */
    private fun inNestedModule(dir: VirtualFile, moduleRoot: Path): Boolean {
        var d: VirtualFile? = dir
        while (d != null) {
            val p = DefaultGoModuleGraphProvider.nioPath(d) ?: return false
            if (p == moduleRoot) return false
            if (d.findChild("go.mod") != null) return true
            d = d.parent
        }
        return false
    }

    private fun findDir(path: Path): VirtualFile? = LocalFileSystem.getInstance().findFileByNioFile(path)?.takeIf { it.isDirectory }

    private fun toolchain(): GoToolchainInfo? = GoToolchainProvider.getInstance().toolchainFor(project)

    private fun defaultContext(): GoBuildContext =
        toolchain()?.buildContext ?: GoBuildContext(DefaultGoToolchainProvider.hostOs(), DefaultGoToolchainProvider.hostArch())

    /** GOROOT `src` directory, cached until the toolchain or the VFS structure changes. */
    private fun gorootSrc(toolchain: GoToolchainInfo?): VirtualFile? {
        val path = toolchain?.gorootSrc ?: return null
        var cached = gorootSrcValue
        if (cached == null || cached.value?.path != path) {
            cached = CachedValuesManager.getManager(project).createCachedValue {
                CachedValueProvider.Result.create<GorootSrc?>(
                    GorootSrc(path, findDir(path)),
                    VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                    toolchainTracker(),
                )
            }
            gorootSrcValue = cached
        }
        return cached!!.value?.dir?.takeIf { it.isValid }
    }

    private fun cacheLayout(toolchain: GoToolchainInfo?): GoModuleCacheLayout? {
        val root = toolchain?.gomodcache ?: return null
        cacheLayoutMemo?.takeIf { it.root == root }?.let { return it }
        return GoModuleCacheLayout(root).also { cacheLayoutMemo = it }
    }

    private fun toolchainTracker(): ModificationTracker =
        (GoToolchainProvider.getInstance() as? DefaultGoToolchainProvider)?.modificationTracker ?: ModificationTracker.NEVER_CHANGED

    companion object {
        private val NO_PACKAGE = Any()
        private const val SWEEP_THRESHOLD = 4096

        /** `go/build.IsLocalImport`. */
        @JvmStatic
        fun isLocalImport(path: String): Boolean =
            path == "." || path == ".." || path.startsWith("./") || path.startsWith("../")
    }
}
