package io.github.golangsupport.ide.formatter

import com.intellij.formatting.Block
import com.intellij.formatting.FormattingDocumentModel
import com.intellij.formatting.FormattingModelWithShiftIndentInsideDocumentRange
import com.intellij.lang.ASTNode
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.formatter.FormattingDocumentModelImpl
import com.intellij.psi.formatter.PsiBasedFormattingModel
import com.intellij.psi.impl.source.tree.TreeUtil
import io.github.golangsupport.lang.psi.GoTypes

/**
 * Go's inserted semicolons are SEMICOLON_SYNTHETIC tokens whose text is the line break they replace.
 * They are not blocks, so the engine sees them as part of the whitespace between two blocks (the
 * text is whitespace-only). This model makes sure such a token is never deleted or retyped: the
 * first line break of the new whitespace is mapped onto the token and only the whitespace before and
 * after it is replaced.
 *
 * The text inside a block is never re-indented: when the engine moves a multi-line leaf block (a
 * raw string, a `/* */` comment, or a multi-token segment of [GoSegmentRootBlock]) it would shift
 * the block's inner lines along (the document-based model of the editor path does), but gofmt never
 * changes the inside of a literal or comment and the lines of a segment are already laid out.
 */
class GoFormattingModel(file: PsiFile, rootBlock: Block, documentModel: FormattingDocumentModelImpl) :
    PsiBasedFormattingModel(file, rootBlock, documentModel), FormattingModelWithShiftIndentInsideDocumentRange {

    private val fastDocumentModel = GoFormattingDocumentModel(documentModel)

    override fun getDocumentModel(): FormattingDocumentModel = fastDocumentModel

    override fun shiftIndentInsideRange(node: ASTNode?, range: TextRange, indent: Int): TextRange = range

    override fun shiftIndentInsideDocumentRange(document: Document, node: ASTNode?, range: TextRange, indent: Int): TextRange = range

    override fun adjustWhiteSpaceInsideDocument(node: ASTNode?, whiteSpace: String): String = whiteSpace

    override fun replaceWhiteSpace(textRange: TextRange, whiteSpace: String): TextRange = replace(textRange, null, whiteSpace)

    override fun replaceWhiteSpace(textRange: TextRange, nodeAfter: ASTNode?, whiteSpace: String): TextRange = replace(textRange, nodeAfter, whiteSpace)

    private fun replace(range: TextRange, nodeAfter: ASTNode?, whiteSpace: String): TextRange {
        val synthetic = syntheticSemicolonIn(range) ?: return super.replaceWhiteSpace(range, nodeAfter, whiteSpace)
        val nl = whiteSpace.indexOf('\n')
        // the engine is always asked for at least one line break where an inserted semicolon is;
        // should it ever drop it, keep the token and put the requested whitespace after it
        val before = if (nl < 0) "" else whiteSpace.substring(0, nl)
        val after = if (nl < 0) whiteSpace else whiteSpace.substring(nl + 1)
        val afterRange = TextRange(synthetic + 1, range.endOffset)
        if (currentText(afterRange) != after) {
            super.replaceWhiteSpace(afterRange, nodeAfter, after)
        }
        val beforeRange = TextRange(range.startOffset, synthetic)
        if (currentText(beforeRange) != before) {
            super.replaceWhiteSpace(beforeRange, null, before)
        }
        return TextRange(range.startOffset, range.startOffset + before.length + 1 + after.length)
    }

    // The current text is read from the leaves around the range: the text of the whole file node is
    // rebuilt after every change, which made reformatting a file with many changes quadratic.

    /** Offset of a SEMICOLON_SYNTHETIC token inside [range], or null. */
    private fun syntheticSemicolonIn(range: TextRange): Int? {
        var leaf = findElementAt(range.startOffset) ?: return null
        var start = leaf.startOffset
        while (start < range.endOffset) {
            if (leaf.elementType == GoTypes.SEMICOLON_SYNTHETIC && start >= range.startOffset) return start
            start += leaf.textLength
            leaf = TreeUtil.nextLeaf(leaf) ?: return null
        }
        return null
    }

    /** The current text of [range]. */
    private fun currentText(range: TextRange): String {
        if (range.isEmpty) return ""
        var leaf = findElementAt(range.startOffset) ?: return ""
        var start = leaf.startOffset
        val result = StringBuilder(range.length)
        while (start < range.endOffset) {
            val chars = leaf.chars
            result.append(chars, maxOf(range.startOffset - start, 0), minOf(range.endOffset - start, chars.length))
            start += chars.length
            leaf = TreeUtil.nextLeaf(leaf) ?: break
        }
        return result.toString()
    }
}

/**
 * The platform's document model answers [containsWhiteSpaceSymbolsOnly] by looking up the PSI
 * element (and its language) at the offset, once per block; on a large file that lookup was a
 * tenth of the whole reformat. Whitespace between Go blocks is plain ` \t\r\n` (inserted
 * semicolons are line feeds), so that case is answered from the text and anything else is
 * delegated.
 */
internal class GoFormattingDocumentModel(private val delegate: FormattingDocumentModelImpl) : FormattingDocumentModel by delegate {
    override fun containsWhiteSpaceSymbolsOnly(startOffset: Int, endOffset: Int): Boolean {
        val chars = delegate.document.charsSequence
        val end = minOf(endOffset, chars.length)
        var i = startOffset
        while (i < end) {
            val c = chars[i]
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return delegate.containsWhiteSpaceSymbolsOnly(startOffset, endOffset)
            i++
        }
        return true
    }
}
