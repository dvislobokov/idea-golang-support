package io.github.golangsupport.ide.formatter

import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.impl.source.codeStyle.PostFormatProcessor
import com.intellij.psi.tree.IElementType
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoTokenSets
import io.github.golangsupport.lang.psi.GoTypes

/**
 * Code Style | Go | Wrapping and Braces, the options gofmt can live with: gofmt keeps line breaks where they are, so a one-line call,
 * composite literal or parameter list past the right margin may be chopped down, one item per line with a trailing comma, the layout gofmt
 * itself keeps. Off by default ([GoCodeStyleSettings]); only Reformat Code with the Built-in formatter runs it (an external gofmt claims
 * the file before the platform's formatter, so this processor is never reached then).
 */
class GoChopDownPostFormatProcessor : PostFormatProcessor {
    /** `CodeStyleManager.reformat(element)` comes here rather than to [processText]: a whole file is chopped the same way. */
    override fun processElement(source: PsiElement, settings: CodeStyleSettings): PsiElement {
        if (source is GoFile && source.isValid) processText(source, source.textRange, settings)
        return source
    }

    override fun processText(source: PsiFile, rangeToReformat: TextRange, settings: CodeStyleSettings): TextRange {
        if (source !is GoFile) return rangeToReformat
        val style = settings.getCustomSettings(GoCodeStyleSettings::class.java)
        val kinds = HashSet<IElementType>()
        if (style.CHOP_DOWN_CALL_ARGUMENTS) kinds += GoTypes.ARGUMENT_LIST
        if (style.CHOP_DOWN_COMPOSITE_LITERALS) kinds += GoTypes.LITERAL_VALUE
        if (style.CHOP_DOWN_PARAMETERS) kinds += GoTypes.PARAMETERS
        if (kinds.isEmpty()) return rangeToReformat
        val documents = PsiDocumentManager.getInstance(source.project)
        val document = documents.getDocument(source) ?: return rangeToReformat
        documents.doPostponedOperationsAndUnblockDocument(document)
        val text = document.immutableCharSequence
        val tabSize = settings.getIndentOptions(source.fileType).TAB_SIZE
        val edits = chopDown(source.node, text, rangeToReformat, kinds, settings.getRightMargin(GoLanguage), tabSize)
        if (edits.isEmpty()) return rangeToReformat
        var delta = 0
        for ((range, replacement) in edits.sortedByDescending { it.first.startOffset }) {
            document.replaceString(range.startOffset, range.endOffset, replacement)
            delta += replacement.length - range.length
        }
        documents.commitDocument(document)
        return TextRange(rangeToReformat.startOffset, (rangeToReformat.endOffset + delta).coerceIn(rangeToReformat.startOffset, document.textLength))
    }

    companion object {
        /**
         * The replacements that chop down every list of [kinds] inside [range] whose line is wider than [margin] columns: the outermost list
         * of a line first (the ones inside it are on lines of their own afterwards). A list with comments or line breaks is left alone.
         */
        fun chopDown(root: ASTNode, text: CharSequence, range: TextRange, kinds: Set<IElementType>, margin: Int, tabSize: Int): List<Pair<TextRange, String>> {
            val edits = ArrayList<Pair<TextRange, String>>()
            fun visit(node: ASTNode) {
                if (node.elementType in kinds && range.contains(node.textRange) && isChoppable(node)) {
                    val lineStart = lineStart(text, node.startOffset)
                    val lineEnd = lineEnd(text, node.startOffset)
                    if (width(text, lineStart, lineEnd, tabSize) > margin) {
                        chopped(node, text, lineStart)?.let { edits += node.textRange to it; return }
                    }
                }
                var child = node.firstChildNode
                while (child != null) {
                    visit(child)
                    child = child.treeNext
                }
            }
            visit(root)
            return edits
        }

        /** Function parameters only: the results of a signature are left as they are (GoLand has a separate option for them). */
        private fun isChoppable(node: ASTNode): Boolean =
            node.elementType != GoTypes.PARAMETERS || node.treeParent?.elementType == GoTypes.SIGNATURE && node.treeParent.firstChildNode == node

        /** `(a, b)` -> `(\n<indent>\ta,\n<indent>\tb,\n<indent>)`; null when the list is empty, spans lines or has comments. */
        fun chopped(node: ASTNode, text: CharSequence, lineStart: Int): String? {
            val open = node.firstChildNode ?: return null
            val close = node.lastChildNode ?: return null
            if (open == close || node.text.contains('\n')) return null
            val items = ArrayList<String>()
            var itemStart = -1
            var itemEnd = -1
            var child = open.treeNext
            while (child != null && child != close) {
                when {
                    GoTokenSets.COMMENTS.contains(child.elementType) -> return null
                    child.elementType == GoTypes.COMMA -> {
                        if (itemStart < 0) return null
                        items += text.substring(itemStart, itemEnd)
                        itemStart = -1
                    }
                    child.elementType == TokenType.WHITE_SPACE -> {}
                    else -> {
                        if (itemStart < 0) itemStart = child.startOffset
                        itemEnd = child.startOffset + child.textLength
                    }
                }
                child = child.treeNext
            }
            if (itemStart >= 0) items += text.substring(itemStart, itemEnd)
            if (items.size < 2) return null
            var indentEnd = lineStart
            while (indentEnd < text.length && (text[indentEnd] == '\t' || text[indentEnd] == ' ')) indentEnd++
            val indent = text.substring(lineStart, indentEnd)
            return open.text + "\n" + items.joinToString("") { "$indent\t$it,\n" } + indent + close.text
        }

        private fun lineStart(text: CharSequence, offset: Int): Int {
            var i = offset
            while (i > 0 && text[i - 1] != '\n') i--
            return i
        }

        private fun lineEnd(text: CharSequence, offset: Int): Int {
            var i = offset
            while (i < text.length && text[i] != '\n') i++
            return i
        }

        private fun width(text: CharSequence, start: Int, end: Int, tabSize: Int): Int {
            var column = 0
            for (i in start until end) column = if (text[i] == '\t') (column / tabSize + 1) * tabSize else column + 1
            return column
        }
    }
}
