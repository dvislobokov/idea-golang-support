package io.github.golangsupport.lang.index

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.SingleEntryFileBasedIndexExtension
import com.intellij.util.indexing.SingleEntryIndexer
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.IOUtil
import io.github.golangsupport.lang.GoFileType
import io.github.golangsupport.lang.lexer.GoFileHeaderScanner
import io.github.golangsupport.lang.psi.GoBuildConstraint
import java.io.DataInput
import java.io.DataOutput

/**
 * File -> raw build constraint (`//go:build` expression and `// +build` lines). Files without a
 * constraint have no entry. Lexer-based (see [GoFileHeaderScanner]).
 */
class GoBuildTagsIndex : SingleEntryFileBasedIndexExtension<GoBuildConstraint>() {

    override fun getName(): ID<Int, GoBuildConstraint> = NAME

    override fun getIndexer(): SingleEntryIndexer<GoBuildConstraint> = object : SingleEntryIndexer<GoBuildConstraint>(false) {
        override fun computeValue(inputData: FileContent): GoBuildConstraint? =
            GoIndexedFileHeader.of(inputData).buildConstraint.takeUnless { it.isEmpty }
    }

    override fun getValueExternalizer(): DataExternalizer<GoBuildConstraint> = Externalizer

    override fun getVersion(): Int = 2

    override fun getInputFilter(): FileBasedIndex.InputFilter = DefaultFileTypeSpecificInputFilter(GoFileType)

    private object Externalizer : DataExternalizer<GoBuildConstraint> {
        override fun save(out: DataOutput, value: GoBuildConstraint) {
            out.writeBoolean(value.goBuild != null)
            value.goBuild?.let { IOUtil.writeUTF(out, it) }
            out.writeInt(value.plusBuild.size)
            value.plusBuild.forEach { IOUtil.writeUTF(out, it) }
        }

        override fun read(input: DataInput): GoBuildConstraint {
            val goBuild = if (input.readBoolean()) IOUtil.readUTF(input) else null
            val plusBuild = List(input.readInt()) { IOUtil.readUTF(input) }
            return GoBuildConstraint(goBuild, plusBuild)
        }
    }

    companion object {
        @JvmField
        val NAME: ID<Int, GoBuildConstraint> = ID.create("go.build.tags")

        /** The indexed constraint of [file], `null` when the file has none. */
        @JvmStatic
        fun constraintOf(file: VirtualFile, project: Project): GoBuildConstraint? =
            FileBasedIndex.getInstance().getFileData(NAME, file, project).values.firstOrNull()
    }
}
