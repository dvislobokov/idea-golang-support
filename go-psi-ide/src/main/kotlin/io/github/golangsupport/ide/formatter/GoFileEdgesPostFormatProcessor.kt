package io.github.golangsupport.ide.formatter

import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.impl.source.codeStyle.PostFormatProcessor
import com.intellij.psi.impl.source.tree.TreeUtil
import io.github.golangsupport.lang.lexer.GoLexer
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoTypes

/**
 * The whitespace at the edges of a file, which the formatting engine does not touch (it only edits
 * whitespace between and before blocks): gofmt output starts with the first token or comment and
 * ends with exactly one line feed. Applied when the formatted range reaches the start or the end of
 * the file's tokens.
 *
 * Only whitespace changes. An inserted semicolon (SEMICOLON_SYNTHETIC, the line feed after a token
 * such as `}`) is kept as the final line feed; when the file has no line feed after its last token,
 * the added one gets the token type the lexer gives it, so the tree stays what a reparse produces.
 */
class GoFileEdgesPostFormatProcessor : PostFormatProcessor {

    override fun processElement(source: PsiElement, settings: CodeStyleSettings): PsiElement {
        val file = source.containingFile as? GoFile ?: return source
        if (!source.isValid) return source
        val range = source.textRange
        normalize(file, range)
        return source
    }

    override fun processText(source: PsiFile, rangeToReformat: TextRange, settings: CodeStyleSettings): TextRange {
        val file = source as? GoFile ?: return rangeToReformat
        val delta = normalize(file, rangeToReformat)
        if (delta == 0) return rangeToReformat
        val end = maxOf(rangeToReformat.startOffset, minOf(rangeToReformat.endOffset + delta, file.textLength))
        return TextRange(maxOf(0, minOf(rangeToReformat.startOffset, end)), end)
    }

    /** Normalizes the file edges that [range] reaches; returns the change of the text length. */
    private fun normalize(file: GoFile, range: TextRange): Int {
        val root = file.node ?: return 0
        val first = firstSignificantLeaf(root) ?: return 0
        val last = lastSignificantLeaf(root) ?: return 0
        var delta = 0
        // the end first: removing leading whitespace shifts the offsets compared here
        if (range.endOffset >= last.startOffset + last.textLength) delta += normalizeEnd(root, last)
        if (range.startOffset <= first.startOffset) delta += normalizeStart(root, first)
        return delta
    }

    /** Removes line breaks, blanks and tabs before the first token or comment. */
    private fun normalizeStart(root: ASTNode, first: ASTNode): Int {
        var delta = 0
        var leaf = TreeUtil.prevLeaf(first)
        while (leaf != null) {
            val prev = TreeUtil.prevLeaf(leaf)
            // a byte order mark is WHITE_SPACE too; it stays
            if (leaf.elementType == TokenType.WHITE_SPACE && isPlainWhitespace(leaf.chars)) {
                delta -= leaf.textLength
                leaf.treeParent.removeChild(leaf)
            }
            leaf = prev
        }
        return delta
    }

    /** Makes the text after the last token or comment exactly one line feed. */
    private fun normalizeEnd(root: ASTNode, last: ASTNode): Int {
        val trailing = ArrayList<ASTNode>()
        var leaf = TreeUtil.nextLeaf(last)
        while (leaf != null) {
            if (leaf.textLength > 0) trailing += leaf
            leaf = TreeUtil.nextLeaf(leaf)
        }
        if (trailing.size == 1 && trailing[0].textLength == 1 && trailing[0].chars[0] == '\n') return 0
        if (trailing.any { it.elementType != TokenType.WHITE_SPACE && it.elementType != GoTypes.SEMICOLON_SYNTHETIC }) return 0
        var delta = 0
        val keepsLineFeed = trailing.any { it.elementType == GoTypes.SEMICOLON_SYNTHETIC }
        for (t in trailing) {
            if (t.elementType == TokenType.WHITE_SPACE) {
                delta -= t.textLength
                t.treeParent.removeChild(t)
            }
        }
        if (!keepsLineFeed) {
            root.addLeaf(lineFeedType(root, last), "\n", null)
            delta += 1
        }
        return delta
    }

    /** The token type of a line feed right after [last]: an inserted semicolon after `}`, `x`, `return`... */
    private fun lineFeedType(root: ASTNode, last: ASTNode): com.intellij.psi.tree.IElementType {
        // lex from the last token (comments keep the lexer state) to the end, plus the line feed
        var token: ASTNode? = last
        while (token != null && (token.textLength == 0 || isTrivia(token))) token = TreeUtil.prevLeaf(token)
        val start = (token ?: last).startOffset
        val end = last.startOffset + last.textLength
        val text = root.chars.subSequence(start, end).toString() + "\n"
        val lexer = GoLexer()
        lexer.start(text)
        var type: com.intellij.psi.tree.IElementType? = null
        while (lexer.tokenType != null) {
            type = lexer.tokenType
            lexer.advance()
        }
        return if (type == GoTypes.SEMICOLON_SYNTHETIC) GoTypes.SEMICOLON_SYNTHETIC else TokenType.WHITE_SPACE
    }

    private fun firstSignificantLeaf(root: ASTNode): ASTNode? {
        var leaf = TreeUtil.findFirstLeaf(root)
        while (leaf != null && (leaf.textLength == 0 || leaf.elementType == TokenType.WHITE_SPACE)) leaf = TreeUtil.nextLeaf(leaf)
        return leaf
    }

    private fun lastSignificantLeaf(root: ASTNode): ASTNode? {
        var leaf = TreeUtil.findLastLeaf(root)
        while (leaf != null && (leaf.textLength == 0 || leaf.elementType == TokenType.WHITE_SPACE || leaf.elementType == GoTypes.SEMICOLON_SYNTHETIC)) {
            leaf = TreeUtil.prevLeaf(leaf)
        }
        return leaf
    }

    private fun isTrivia(node: ASTNode): Boolean {
        val type = node.elementType
        return type == TokenType.WHITE_SPACE || type == GoTypes.SEMICOLON_SYNTHETIC || type == GoTypes.LINE_COMMENT || type == GoTypes.BLOCK_COMMENT
    }

    private fun isPlainWhitespace(chars: CharSequence): Boolean {
        for (c in chars) if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return false
        return true
    }
}
