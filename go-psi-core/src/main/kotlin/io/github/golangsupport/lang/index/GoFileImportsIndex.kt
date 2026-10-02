package io.github.golangsupport.lang.index

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.ScalarIndexExtension
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import io.github.golangsupport.lang.GoFileType
import io.github.golangsupport.lang.lexer.GoFileHeaderScanner

/** Import path -> files importing it. Lexer-based (see [GoFileHeaderScanner]); no parse tree. */
class GoFileImportsIndex : ScalarIndexExtension<String>() {

    override fun getName(): ID<String, Void> = NAME

    override fun getIndexer(): DataIndexer<String, Void, FileContent> = DataIndexer { content ->
        GoIndexedFileHeader.of(content).imports.associate { it.path to null }
    }

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getVersion(): Int = 1

    override fun getInputFilter(): FileBasedIndex.InputFilter = DefaultFileTypeSpecificInputFilter(GoFileType)

    override fun dependsOnFileContent(): Boolean = true

    companion object {
        @JvmField
        val NAME: ID<String, Void> = ID.create("go.file.imports")

        @JvmStatic
        fun filesImporting(path: String, project: Project, scope: GlobalSearchScope = GlobalSearchScope.allScope(project)):
            Collection<VirtualFile> = FileBasedIndex.getInstance().getContainingFiles(NAME, path, scope)
    }
}
