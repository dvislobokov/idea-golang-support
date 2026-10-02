package io.github.golangsupport.catalogue

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.golangsupport.lang.GoDeclarationInfo
import io.github.golangsupport.lang.GoDeclarationKind
import io.github.golangsupport.lang.GoDeclarations
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.GoFileStructure
import io.github.golangsupport.lang.GoImports
import io.github.golangsupport.lang.GoInterfaceMethod
import io.github.golangsupport.lang.GoInterfaceOrigin
import io.github.golangsupport.lang.GoInterfaces
import io.github.golangsupport.mod.GoModulesService
import java.io.File

/**
 * The methods of an interface, read from the sources of its package when it is chosen: the catalogue keeps names, not method sets,
 * and a method set is wanted once, whole, with the interfaces it embeds. A package of the project is read through the VFS (an open
 * document as it is now), one of the standard library or of the module cache from disk. Made for one Implement Interface and thrown
 * away: what it has read is kept only for that long. Not for EDT.
 */
class GoInterfaceSources(private val project: Project) {
    private class Source(val text: CharSequence, val structure: GoFileStructure)

    /** The files of one package with what the plugin's scanner sees in them. */
    private class LoadedPackage(val importPath: String, val name: String, val files: List<Source>) {
        val exportedTypes: Set<String> = files.flatMap { it.structure.declarations }.filter { it.kind.isType && it.isExported }.mapTo(HashSet()) { it.name }
        fun find(name: String): Pair<Source, GoDeclarationInfo>? =
            files.firstNotNullOfOrNull { source -> source.structure.declarations.firstOrNull { it.kind == GoDeclarationKind.INTERFACE && it.name == name }?.let { source to it } }
    }

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
        val (source, declaration) = pack.find(name) ?: return null
        val body = declaration.body ?: return null
        val parsed = GoInterfaces.parseBody(source.text.subSequence(body.startOffset, body.endOffset))
        val origin = GoInterfaceOrigin(pack.importPath, pack.name, pack.exportedTypes, source.structure.imports)
        val result = parsed.methods.mapTo(ArrayList()) { (method, signature) -> GoInterfaceMethod(method, signature, origin) }
        for (embedded in parsed.embedded) {
            val qualifier = embedded.substringBefore('.', "")
            val embeddedName = embedded.substringAfter('.')
            val embeddedIn = if (qualifier.isEmpty()) pack else source.structure.imports.firstOrNull { GoImports.nameOf(it) == qualifier }?.let { load(it.path) } ?: continue
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

    private fun read(importPath: String, directory: VirtualFile): LoadedPackage? {
        val sources = directory.children.filter { !it.isDirectory && GoCatalogueScanner.isSource(it.name) }
            .map { file -> FileDocumentManager.getInstance().getCachedDocument(file)?.immutableCharSequence ?: LoadTextUtil.loadText(file) }
        return assemble(importPath, sources)
    }

    private fun read(importPath: String, directory: File): LoadedPackage? {
        val sources = (directory.listFiles() ?: return null).filter { it.isFile && GoCatalogueScanner.isSource(it.name) }
            .mapNotNull { file -> runCatching { file.readText() }.getOrNull() }
        return assemble(importPath, sources)
    }

    private fun assemble(importPath: String, texts: List<CharSequence>): LoadedPackage? {
        val files = texts.map { Source(it, GoDeclarations.scan(it)) }.filter { it.structure.packageName != null }
        // a file of another package in the directory is a generator or an example kept out of the build
        val name = files.groupingBy { it.structure.packageName!! }.eachCount().maxByOrNull { it.value }?.key ?: return null
        return LoadedPackage(importPath, name, files.filter { it.structure.packageName == name })
    }

    companion object {
        /** The import path of the package of [file], as far as the go.mod files of the project tell it. */
        fun importPathOf(project: Project, file: GoFile): String? =
            file.virtualFile?.parent?.let { directory -> GoModulesService.getInstance(project).moduleOf(directory)?.importPath(directory) }
    }
}
