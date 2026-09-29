package io.github.golangsupport.catalogue

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.IOUtil
import com.intellij.util.io.KeyDescriptor
import io.github.golangsupport.lang.GoDeclarationKind
import io.github.golangsupport.lang.GoFileType
import io.github.golangsupport.mod.GoModulesService
import java.io.DataInput
import java.io.DataOutput

/**
 * What every Go file of the project exports, kept by the platform: on the disk between the runs of the IDE, read again for a file
 * when the file changes, thrown away by Invalidate Caches. The packages of the project for the catalogue are put together from it
 * ([projectPackages]), without reading a source file.
 *
 * One key for every file: the index is asked for all of it at once, and only when it has changed.
 */
class GoExportsIndex : FileBasedIndexExtension<String, GoFileExports>() {
    override fun getName(): ID<String, GoFileExports> = NAME
    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE
    override fun getVersion(): Int = VERSION
    override fun dependsOnFileContent(): Boolean = true
    override fun getInputFilter(): FileBasedIndex.InputFilter = DefaultFileTypeSpecificInputFilter(GoFileType)

    override fun getIndexer(): DataIndexer<String, GoFileExports, FileContent> = DataIndexer { content ->
        val exports = if (GoCatalogueScanner.isSource(content.fileName)) GoCatalogueScanner.exportsOf(content.contentAsText) else null
        // a file that exports nothing is kept as well: it tells what the package of its directory is called
        if (exports == null) emptyMap() else mapOf(KEY to exports)
    }

    override fun getValueExternalizer(): DataExternalizer<GoFileExports> = object : DataExternalizer<GoFileExports> {
        override fun save(out: DataOutput, value: GoFileExports) {
            IOUtil.writeUTF(out, value.packageName)
            out.writeInt(value.symbols.size)
            for (symbol in value.symbols) {
                IOUtil.writeUTF(out, symbol.name)
                out.writeByte(symbol.kind.ordinal)
                IOUtil.writeUTF(out, symbol.signature.orEmpty())
            }
        }

        override fun read(input: DataInput): GoFileExports {
            val kinds = GoDeclarationKind.entries
            val name = IOUtil.readUTF(input)
            return GoFileExports(name, List(input.readInt()) { GoSymbol(IOUtil.readUTF(input), kinds[input.readByte().toInt()], IOUtil.readUTF(input).ifEmpty { null }) })
        }
    }

    companion object {
        val NAME: ID<String, GoFileExports> = ID.create("golang.exports")
        const val KEY = "exports"

        // bump when GoCatalogueScanner.exportsOf starts to see a file differently, or the form of a value changes
        private const val VERSION = 1

        private val SKIPPED = Regex("/(vendor|testdata|node_modules)/")

        /** Changes with every change of the index: whether the packages are to be put together again. Needs read access and the indexes. */
        fun stamp(project: Project): Long = FileBasedIndex.getInstance().getIndexModificationStamp(NAME, project)

        /**
         * The packages of the project with the paths they are imported by, from the index. A directory is a package; its path is the one
         * of its module with the way from the go.mod to it. Needs read access and the indexes.
         */
        fun projectPackages(project: Project): List<GoPackageSymbols> {
            val byDirectory = LinkedHashMap<VirtualFile, MutableList<GoFileExports>>()
            FileBasedIndex.getInstance().processValues(NAME, KEY, null, { file, exports ->
                val directory = file.parent
                if (directory != null && !SKIPPED.containsMatchIn(file.path)) byDirectory.getOrPut(directory) { ArrayList() } += exports
                true
            }, GlobalSearchScope.projectScope(project))
            val modules = GoModulesService.getInstance(project)
            return byDirectory.mapNotNull { (directory, files) ->
                val importPath = modules.moduleOf(directory)?.importPath(directory) ?: return@mapNotNull null
                GoCatalogueScanner.merge(importPath, files)
            }.sortedBy { it.importPath }
        }
    }
}
