package io.github.golangsupport.lang

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

/**
 * Names of the declarations of every Go file of the project, by the text scanner. The key carries the kind (`T:Server`, `I:Handler`,
 * `M:Start`), so the files that declare an interface are known without a scan (Implement Interface). Go to Class / Symbol are the stub
 * contributors of go-psi-ide now; step 6 of MIGRATION.md moves the rest onto stub indices.
 */
class GoDeclarationIndex : ScalarIndexExtension<String>() {
    override fun getName(): ID<String, Void> = NAME
    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE
    override fun getVersion(): Int = VERSION
    override fun dependsOnFileContent(): Boolean = true
    override fun getInputFilter(): FileBasedIndex.InputFilter = DefaultFileTypeSpecificInputFilter(GoFileType)

    override fun getIndexer(): DataIndexer<String, Void, FileContent> = DataIndexer { content ->
        GoDeclarations.scan(content.contentAsText).all().associate { key(it.kind, it.name) to null }
    }

    companion object {
        val NAME: ID<String, Void> = ID.create("golang.declarations")

        // bump when GoDeclarations starts to see declarations differently
        private const val VERSION = 2
        const val TYPE_PREFIX = "T:"
        const val INTERFACE_PREFIX = "I:"
        const val MEMBER_PREFIX = "M:"

        fun key(kind: GoDeclarationKind, name: String): String = when {
            kind == GoDeclarationKind.INTERFACE -> INTERFACE_PREFIX
            kind.isType -> TYPE_PREFIX
            else -> MEMBER_PREFIX
        } + name

        fun isTypeKey(key: String): Boolean = key.startsWith(TYPE_PREFIX) || key.startsWith(INTERFACE_PREFIX)

        /** The Go files of [scope] that declare an interface: what Implement Interface reads. Needs the index (smart mode). */
        fun filesWithInterfaces(project: Project, scope: GlobalSearchScope): Set<VirtualFile> {
            val index = FileBasedIndex.getInstance()
            val keys = ArrayList<String>()
            index.processAllKeys(NAME, { key -> if (key.startsWith(INTERFACE_PREFIX)) keys.add(key); true }, scope, null)
            return keys.flatMapTo(LinkedHashSet()) { index.getContainingFiles(NAME, it, scope) }
        }
    }
}
