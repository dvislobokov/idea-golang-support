package io.github.golangsupport.catalogue

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
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
 * and a method set is wanted once, whole, with the interfaces it embeds. A package of the project is read through its PSI (the file
 * of an interface whose methods are asked for gets its AST loaded: one file, chosen by the user); one of the standard library or of
 * the module cache is read from disk with the scanner of the plugin, as they are not in the indices of the platform. Made for one
 * Implement Interface and thrown away: what it has read is kept only for that long. Needs read access; not for EDT.
 */
class GoInterfaceSources(private val project: Project) {
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

    /** A package of the project, from the PSI of its files: names from the stubs, the chosen interface from its source. */
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

    /** A package of the standard library or of the module cache, by the scanner of the plugin until they are in the indices. */
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
