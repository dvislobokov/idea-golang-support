package io.github.golangsupport.semantic.scope

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import io.github.golangsupport.semantic.psi.GoPsiUtil
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.project.api.GoImportResolution
import io.github.golangsupport.project.api.GoPackage
import io.github.golangsupport.project.api.GoPackageResolver
import io.github.golangsupport.semantic.cache.GoTrackers

/**
 * The package as a set of Go files, built on the project model. Every accessor is stub-first:
 * package-level declarations come from `GoFile`'s stub accessors, so other files' ASTs are never
 * loaded for a lookup.
 */
@Service(Service.Level.PROJECT)
class GoPackageModel(private val project: Project) {

    /**
     * A package-level view: files, declarations by name, methods by receiver type name. The maps
     * merge the per-file lists of [fileDeclarations], so a rebuild after an edit in one file re-reads
     * only that file's stub.
     */
    class PackageScope(val files: List<GoFile>, val importPath: String?, val name: String?) {
        private val perFile: List<FileDeclarations> by lazy { files.map(::fileDeclarations) }

        private val declarationsByName: Map<String, List<GoNamedElement>> by lazy {
            val single = perFile.singleOrNull()
            if (single != null) return@lazy single.byName
            val map = HashMap<String, MutableList<GoNamedElement>>()
            for (f in perFile) for ((n, list) in f.byName) map.getOrPut(n) { ArrayList(list.size) }.addAll(list)
            map
        }
        private val methodsByReceiver: Map<String, List<GoMethodDeclaration>> by lazy {
            val single = perFile.singleOrNull()
            if (single != null) return@lazy single.methodsByReceiver
            val map = HashMap<String, MutableList<GoMethodDeclaration>>()
            for (f in perFile) for ((r, list) in f.methodsByReceiver) map.getOrPut(r) { ArrayList(list.size) }.addAll(list)
            map
        }

        fun lookup(name: String): List<GoNamedElement> = declarationsByName[name] ?: emptyList()

        fun lookupType(name: String): GoTypeSpec? = lookup(name).firstOrNull { it is GoTypeSpec } as GoTypeSpec?

        fun methodsOf(typeName: String): List<GoMethodDeclaration> = methodsByReceiver[typeName] ?: emptyList()

        fun allDeclarations(): Sequence<GoNamedElement> = declarationsByName.values.asSequence().flatten()
    }

    /** The package-level names of one file (functions, types, vars, consts) and its methods by receiver type name. */
    class FileDeclarations(
        val byName: Map<String, List<GoNamedElement>>,
        val methodsByReceiver: Map<String, List<GoMethodDeclaration>>,
    )

    private val trackers get() = GoTrackers.getInstance(project)

    /**
     * The package scope of the package containing [file] (test variant aware). Names only: depends
     * on the package's own stamp ([GoTrackers.ownPackageDependencies]), not on its imports.
     */
    fun scopeOf(file: GoFile): PackageScope = CachedValuesManager.getCachedValue(file) {
        CachedValueProvider.Result.create(computeScope(file), *trackers.ownPackageDependencies(file))
    }

    /** The package scope of a resolved import ([pkg] from the project model). */
    fun scopeOf(pkg: GoPackage): PackageScope {
        val dir = PsiManager.getInstance(project).findDirectory(pkg.directory) ?: return PackageScope(emptyList(), pkg.importPath, pkg.name)
        return CachedValuesManager.getCachedValue(dir) {
            val files = pkg.goFiles.mapNotNull(::goFile)
            CachedValueProvider.Result.create(PackageScope(files, pkg.importPath, pkg.name), *trackers.ownPackageDependencies(dir))
        }
    }

    /** Resolves an import path as written in [from]; null when unresolved or the cgo pseudo package. */
    fun resolveImport(path: String, from: GoFile): GoPackage? = resolveImport(path, GoPsiUtil.originalVirtualFile(from))

    /** Resolves an import path as written in the file [vf] (no PSI needed); null when unresolved or the cgo pseudo package. */
    fun resolveImport(path: String, vf: VirtualFile): GoPackage? {
        if (vf.fileSystem.protocol != "file" && vf.fileSystem.protocol != "temp") return null
        return when (val r = GoPackageResolver.getInstance(project).resolveImport(path, vf)) {
            is GoImportResolution.Resolved -> r.pkg
            is GoImportResolution.InternalDenied -> r.pkg
            else -> null
        }
    }

    /** The import path of [file]'s package (identity of unexported names); falls back to the directory path. */
    fun packagePathOf(file: GoFile): String? {
        val vf = GoPsiUtil.originalVirtualFile(file)
        return GoPackageResolver.getInstance(project).importPathOf(vf) ?: vf.parent?.path
    }

    private fun computeScope(file: GoFile): PackageScope {
        val vf = GoPsiUtil.originalVirtualFile(file)
        val original = GoPsiUtil.originalFile(file)
        val packageName = file.packageName
        val resolver = GoPackageResolver.getInstance(project)
        val dir = vf.parent
        val pkg = if (dir != null && vf.fileSystem.protocol == "file") runCatching { resolver.packageOf(dir) }.getOrNull() else null
        val files: List<GoFile> = if (pkg != null) {
            val isXTest = packageName != null && packageName.endsWith("_test") && packageName != pkg.name
            val selected = when {
                isXTest -> pkg.xTestFiles
                file.isTestFile -> pkg.goFiles + pkg.testFiles
                else -> pkg.goFiles
            }
            // A regular file of the package sees exactly what an importer sees: share the directory
            // scope (one merged map per package) instead of building one per file.
            if (!isXTest && !file.isTestFile && packageName == pkg.name && selected.any { it == vf }) return scopeOf(pkg)
            val list = selected.mapNotNull(::goFile).toMutableList()
            if (list.none { it.viewProvider.virtualFile == vf }) list += original // e.g. a file excluded by build tags: see its own package
            list
        } else {
            // In-memory/test files or directories without a project model: same PsiDirectory, same package name.
            val psiDir: PsiDirectory? = original.containingDirectory
            val siblings = psiDir?.files?.filterIsInstance<GoFile>()?.filter { it.packageName == packageName && (file.isTestFile || !it.isTestFile) } ?: emptyList()
            if (siblings.isEmpty()) listOf(original) else siblings
        }
        return PackageScope(files, pkg?.importPath ?: packagePathOf(file), packageName)
    }

    private fun goFile(vf: VirtualFile): GoFile? = PsiManager.getInstance(project).findFile(vf) as? GoFile

    companion object {
        private val FILE_DECLARATIONS = Key.create<CachedValue<FileDeclarations>>("gopsi.fileDeclarations")

        @JvmStatic
        fun getInstance(project: Project): GoPackageModel = project.service()

        /**
         * The package-level names of [file], from its stub (or the AST when loaded), cached on the file
         * until an out-of-block change of that file ([GoTrackers.fileDeclarationsDependencies]).
         */
        @JvmStatic
        fun fileDeclarations(file: GoFile): FileDeclarations = CachedValuesManager.getCachedValue(file, FILE_DECLARATIONS) {
            CachedValueProvider.Result.create(
                computeFileDeclarations(file),
                *GoTrackers.getInstance(file.project).fileDeclarationsDependencies(file),
            )
        }

        private fun computeFileDeclarations(f: GoFile): FileDeclarations {
            val byName = HashMap<String, MutableList<GoNamedElement>>()
            fun add(e: GoNamedElement) { val n = e.name ?: return; if (n != "_") byName.getOrPut(n) { ArrayList(1) }.add(e) }
            f.functions.forEach(::add)
            f.types.forEach(::add)
            f.vars.forEach(::add)
            f.consts.forEach(::add)
            val methods = HashMap<String, MutableList<GoMethodDeclaration>>()
            for (m in f.methods) {
                val r = m.receiverTypeName ?: continue
                methods.getOrPut(r) { ArrayList(2) }.add(m)
            }
            return FileDeclarations(byName, methods)
        }
    }
}
