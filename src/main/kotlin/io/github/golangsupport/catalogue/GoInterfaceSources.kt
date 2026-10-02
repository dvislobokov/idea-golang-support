package io.github.golangsupport.catalogue

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.project.api.GoPackageResolver
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.scope.GoPackageModel
import io.github.golangsupport.semantic.scope.GoScopes
import io.github.golangsupport.semantic.scope.GoUniverse
import io.github.golangsupport.semantic.types.GoMethod
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoTypeRenderer
import io.github.golangsupport.lang.GoDeclarationKind
import io.github.golangsupport.lang.GoDeclarations
import io.github.golangsupport.lang.GoImport
import io.github.golangsupport.lang.GoImports
import io.github.golangsupport.lang.GoInterfaceMethod
import io.github.golangsupport.lang.GoInterfaceOrigin
import io.github.golangsupport.lang.GoInterfaces
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoInterfaceType
import io.github.golangsupport.mod.GoModulesService
import java.io.File

/**
 * The methods of an interface, read from the sources of its package when it is chosen: the catalogue keeps names, not method sets,
 * and a method set is wanted once, whole, with the interfaces it embeds. [methodsFor] asks the type checker of go-psi for the method set
 * of the interface (embedded interfaces of any package unfolded, `error` included) whenever its package is in the indices: the project,
 * and since library roots (MIGRATION.md step 7) the standard library and the module cache. Only a package outside the indices (the
 * setting indexes the standard library alone while the interface is of a dependency, or indexing is not done yet) is read the old way:
 * from disk with the scanner of the plugin ([read] of a [File]), or from the PSI text of a project package in dumb mode. Made for one
 * Implement Interface and thrown away: what it has read is kept only for that long. Needs read access; not for EDT.
 */
class GoInterfaceSources(private val project: Project) {
    /**
     * The methods of the interface [name] (of the project package in [directory], or of the package at [importPath]) as the file
     * [target] writes them: the types of other packages qualified by the names [target] imports them under, the paths it does not
     * import yet in [GoInterfaces.Rewritten.imports]. Null when the interface is not found.
     */
    fun methodsFor(target: GoFile, importPath: String?, directory: VirtualFile?, name: String): List<GoInterfaces.Rewritten>? {
        val packageDirectory = directory ?: importPath?.let { packageDirectory(it, target) }
        val spec = packageDirectory?.takeIf(::isIndexed)?.let { interfaceIn(it, name) }
        if (spec != null) return GoInterfaceRendering.methodsOf(spec, target)
        val methods = (if (directory != null) methodsOf(directory, importPath, name) else methodsOf(importPath ?: return null, name)) ?: return null
        val targetImports = target.imports.map { GoImport(it.path, it.alias, TextRange.EMPTY_RANGE) }
        return methods.map { GoInterfaces.rewrite(it, importPathOf(project, target), targetImports) }
    }

    /** The directory of [importPath] as the project model resolves it from [target]: GOROOT, the module cache, the project. */
    private fun packageDirectory(importPath: String, target: GoFile): VirtualFile? {
        val from = target.originalFile.virtualFile ?: return null
        return GoPackageResolver.getInstance(project).resolveImport(importPath, from).packageOrNull?.directory
    }

    /** Whether the type checker can answer for the files of [directory]: they are in the indices and the indices are ready. */
    private fun isIndexed(directory: VirtualFile): Boolean {
        if (DumbService.isDumb(project)) return false
        val index = ProjectFileIndex.getInstance(project)
        return index.isInContent(directory) || index.isInLibrary(directory) || index.isInLibrarySource(directory)
    }

    /** The interface type spec [name] of the package in [directory], its build files first, from the stubs. */
    private fun interfaceIn(directory: VirtualFile, name: String): GoTypeSpec? {
        val psi = PsiManager.getInstance(project)
        return directory.children.filter { !it.isDirectory && GoCatalogueScanner.isSource(it.name) }.sortedBy { it.name }
            .firstNotNullOfOrNull { file -> (psi.findFile(file) as? GoFile)?.types?.firstOrNull { it.name == name && it.type is GoInterfaceType } }
    }

    /** An interface as written: its own methods with their signatures, the names of what it embeds, the imports of its file. */
    private class Declared(val methods: List<Pair<String, String>>, val embedded: List<String>, val imports: List<GoImport>)

    /** The interfaces of one package, read lazily: only the one asked for is read whole. */
    private class LoadedPackage(val importPath: String, val name: String, val exportedTypes: Set<String>, val find: (String) -> Declared?)

    private val loaded = HashMap<String, LoadedPackage?>()

    /** The methods of [name] of the package at [importPath], the embedded interfaces unfolded; null when the interface is not found. */
    fun methodsOf(importPath: String, name: String): List<GoInterfaceMethod>? = methodsOf(load(importPath) ?: return null, name, HashSet())

    /** The same for an interface in [directory] of the project, whose import path may be unknown (no go.mod above it). */
    fun methodsOf(directory: VirtualFile, importPath: String?, name: String): List<GoInterfaceMethod>? {
        val key = importPath ?: directory.path
        val pack = loaded.getOrPut(key) { read(key, directory) } ?: return null
        return methodsOf(pack, name, HashSet())
    }

    private fun methodsOf(pack: LoadedPackage, name: String, visited: MutableSet<String>): List<GoInterfaceMethod>? {
        if (!visited.add("${pack.importPath}.$name")) return emptyList()
        val declared = pack.find(name) ?: return null
        val origin = GoInterfaceOrigin(pack.importPath, pack.name, pack.exportedTypes, declared.imports)
        val result = declared.methods.mapTo(ArrayList()) { (method, signature) -> GoInterfaceMethod(method, signature, origin) }
        for (embedded in declared.embedded) {
            val qualifier = embedded.substringBefore('.', "")
            val embeddedName = embedded.substringAfter('.')
            val embeddedIn = if (qualifier.isEmpty()) pack else declared.imports.firstOrNull { GoImports.nameOf(it) == qualifier }?.let { load(it.path) } ?: continue
            methodsOf(embeddedIn, embeddedName, visited)?.let { result += it }
        }
        return result.distinctBy { it.name }
    }

    private fun load(importPath: String): LoadedPackage? = loaded.getOrPut(importPath) {
        projectDirectory(importPath)?.let { read(importPath, it) } ?: GoCatalogueService.getInstance(project).packageDirectory(importPath)?.let { read(importPath, it) }
    }

    private fun projectDirectory(importPath: String): VirtualFile? {
        val module = GoModulesService.getInstance(project).modules().filter { importPath == it.path || importPath.startsWith(it.path + "/") }.maxByOrNull { it.path.length } ?: return null
        val relative = importPath.removePrefix(module.path).trimStart('/')
        return (if (relative.isEmpty()) module.root else module.root.findFileByRelativePath(relative))?.takeIf { it.isDirectory }
    }

    /** A package of the project in dumb mode, from the PSI of its files: names from the stubs, the chosen interface from its source. */
    private fun read(importPath: String, directory: VirtualFile): LoadedPackage? {
        val psi = PsiManager.getInstance(project)
        val files = directory.children.filter { !it.isDirectory && GoCatalogueScanner.isSource(it.name) }.mapNotNull { psi.findFile(it) as? GoFile }
            .filter { it.packageName != null }
        // a file of another package in the directory is a generator or an example kept out of the build
        val name = files.groupingBy { it.packageName!! }.eachCount().maxByOrNull { it.value }?.key ?: return null
        val own = files.filter { it.packageName == name }
        val exportedTypes = own.flatMap { it.types }.mapNotNullTo(HashSet()) { spec -> spec.name?.takeIf { spec.isPublic() } }
        return LoadedPackage(importPath, name, exportedTypes) { wanted ->
            own.firstNotNullOfOrNull { file ->
                val type = file.types.firstOrNull { it.name == wanted && it.type is GoInterfaceType }?.type as? GoInterfaceType ?: return@firstNotNullOfOrNull null
                val methods = type.methodSpecList.mapNotNull { spec -> spec.name?.let { it to GoInterfaces.tidy(GoInterfaces.stripComments(spec.signature.text)) } }.toMutableList()
                val embedded = ArrayList<String>()
                for (element in type.constraintElemList) {
                    val term = element.constraintTermList.singleOrNull()?.takeIf { it.tilde == null } ?: continue
                    when (val embeddedName = term.type.typeReferenceExpression?.text?.filterNot(Char::isWhitespace) ?: continue) {
                        "any", "comparable" -> Unit
                        "error" -> methods += "Error" to "() string"
                        else -> embedded += embeddedName
                    }
                }
                Declared(methods, embedded, file.imports.map { GoImport(it.path, it.alias, TextRange.EMPTY_RANGE) })
            }
        }
    }

    /**
     * A package of the standard library or of the module cache that is not in the indices (library roots of the standard library only,
     * or indexing not done), by the scanner of the plugin from disk.
     */
    private fun read(importPath: String, directory: File): LoadedPackage? {
        val texts = (directory.listFiles() ?: return null).filter { it.isFile && GoCatalogueScanner.isSource(it.name) }
            .mapNotNull { file -> runCatching { file.readText() }.getOrNull() }
        val files = texts.map { it to GoDeclarations.scan(it) }.filter { it.second.packageName != null }
        val name = files.groupingBy { it.second.packageName!! }.eachCount().maxByOrNull { it.value }?.key ?: return null
        val own = files.filter { it.second.packageName == name }
        val exportedTypes = own.flatMap { it.second.declarations }.filter { it.kind.isType && it.isExported }.mapTo(HashSet()) { it.name }
        return LoadedPackage(importPath, name, exportedTypes) { wanted ->
            own.firstNotNullOfOrNull { (text, structure) ->
                val declaration = structure.declarations.firstOrNull { it.kind == GoDeclarationKind.INTERFACE && it.name == wanted } ?: return@firstNotNullOfOrNull null
                val body = declaration.body ?: return@firstNotNullOfOrNull null
                val parsed = GoInterfaces.parseBody(text.subSequence(body.startOffset, body.endOffset))
                Declared(parsed.methods, parsed.embedded, structure.imports)
            }
        }
    }

    companion object {
        /** The import path of the package of [file], as far as the go.mod files of the project tell it. */
        fun importPathOf(project: Project, file: GoFile): String? =
            file.virtualFile?.parent?.let { directory -> GoModulesService.getInstance(project).moduleOf(directory)?.importPath(directory) }
    }
}

/**
 * The method set of an interface from the type checker of go-psi, written for the file that implements it: the signature rendered by
 * [GoTypeRenderer] with the qualifier of the target file (an import alias is kept, a dot import needs none, a package the file does not
 * import is called by its package name and its path is returned to be imported). Needs read access.
 */
internal object GoInterfaceRendering {
    /** Every method of the interface [spec] (embedded interfaces unfolded) for [target]. */
    fun methodsOf(spec: GoTypeSpec, target: GoFile): List<GoInterfaces.Rewritten> {
        val semantic = GoSemanticService.getInstance(spec.project)
        return semantic.methodsOf(semantic.declarationType(spec)).map { render(it, target) }
    }

    fun render(method: GoMethod, target: GoFile): GoInterfaces.Rewritten {
        val needed = LinkedHashSet<String>()
        val targetPath = GoPackageModel.getInstance(target.project).packagePathOf(target)
        val targetDirectory = target.originalFile.virtualFile?.parent
        // `any` as gopls and go/types print an empty interface in a signature since Go 1.18
        val signature = GoTypeRenderer.render(method.signature) { named -> qualifier(named, target, targetPath, targetDirectory, needed) }
            .removePrefix("func").replace("interface{}", "any")
        // a type the checker does not know is printed `?`: the method is written as its interface writes it instead
        if ('?' in signature) asWritten(method, target)?.let { return it }
        return GoInterfaces.Rewritten(method.name, signature, needed.toList())
    }

    private fun qualifier(named: GoNamedType, target: GoFile, targetPath: String?, targetDirectory: VirtualFile?, needed: MutableSet<String>): String? {
        val declaration = named.declaration
        if (GoUniverse.isBuiltinDeclaration(declaration)) return null
        val file = declaration.containingFile as? GoFile ?: return null
        val path = named.pkgPath
        if (path != null && path == targetPath || targetDirectory != null && file.originalFile.virtualFile?.parent == targetDirectory) return null
        if (path == null) return null
        val import = target.imports.firstOrNull { it.path == path && !it.isBlank }
        if (import != null) return if (import.isDot) null else GoScopes.importName(import)
        needed += path
        return file.packageName ?: path.substringAfterLast('/')
    }

    /** The signature of the method spec as written, made to compile in [target] by [GoInterfaces.rewrite]. */
    private fun asWritten(method: GoMethod, target: GoFile): GoInterfaces.Rewritten? {
        val spec = method.declaration as? GoMethodSpec ?: return null
        val file = spec.containingFile as? GoFile ?: return null
        val packageFiles = file.originalFile.virtualFile?.parent?.children.orEmpty().mapNotNull { PsiManager.getInstance(file.project).findFile(it) as? GoFile }
            .filter { it.packageName == file.packageName }
        val exportedTypes = packageFiles.flatMap { it.types }.mapNotNullTo(HashSet()) { type -> type.name?.takeIf { type.isPublic() } }
        val origin = GoInterfaceOrigin(GoPackageModel.getInstance(file.project).packagePathOf(file).orEmpty(), file.packageName.orEmpty(), exportedTypes,
            file.imports.map { GoImport(it.path, it.alias, TextRange.EMPTY_RANGE) })
        val written = GoInterfaceMethod(method.name, GoInterfaces.tidy(GoInterfaces.stripComments(spec.signature.text)), origin)
        val targetPath = GoPackageModel.getInstance(target.project).packagePathOf(target)
        return GoInterfaces.rewrite(written, targetPath, target.imports.map { GoImport(it.path, it.alias, TextRange.EMPTY_RANGE) })
    }
}
