package io.github.golangsupport.lang.index

import com.intellij.openapi.util.Key
import com.intellij.util.indexing.FileContent
import io.github.golangsupport.lang.lexer.GoFileHeaderScanner

/**
 * One [GoFileHeaderScanner] scan per indexed file content, shared by [GoFileImportsIndex] and
 * [GoBuildTagsIndex]. `FileBasedIndexImpl.doIndexFileContent` creates a single `FileContentImpl`
 * per file and passes it to every index that needs the file, so the second indexer finds the
 * header in the content's user data (the platform caches the PSI and the lighter AST the same way).
 * A `FileContent` is never reused for other text, so the cached header cannot go stale.
 */
internal object GoIndexedFileHeader {

    internal val HEADER: Key<GoFileHeaderScanner.Header> = Key.create("gopsi.indexed.file.header")

    fun of(content: FileContent): GoFileHeaderScanner.Header {
        content.getUserData(HEADER)?.let { return it }
        val header = GoFileHeaderScanner.scan(content.contentAsText)
        content.putUserData(HEADER, header)
        return header
    }
}
