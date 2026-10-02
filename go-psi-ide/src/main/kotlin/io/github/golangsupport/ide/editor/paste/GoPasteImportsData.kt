package io.github.golangsupport.ide.editor.paste

import com.intellij.codeInsight.editorActions.TextBlockTransferableData
import java.awt.datatransfer.DataFlavor
import java.io.Serializable

/** One import the copied code used: `name` is the qualifier as written (`json`), [alias] the explicit name of the spec, if any. */
data class GoCopiedImport(val name: String, val path: String, val alias: String?) : Serializable

/**
 * What [GoPasteImportsProcessor] keeps with copied Go text: the imports its qualifiers resolved to in the source file. [external] marks
 * text that came without it (outside the IDE, another language): then the pasted qualifiers are resolved by [GoPasteImportResolver]s.
 */
class GoPasteImportsData(val sourceFileUrl: String?, val imports: List<GoCopiedImport>, val external: Boolean = false) : TextBlockTransferableData, Serializable {
    override fun getFlavor(): DataFlavor = FLAVOR

    companion object {
        @JvmField
        val FLAVOR: DataFlavor = DataFlavor(GoPasteImportsData::class.java, "Go imports of copied code")

        @JvmField
        val EXTERNAL: GoPasteImportsData = GoPasteImportsData(null, emptyList(), external = true)

        private const val serialVersionUID = 1L
    }
}
