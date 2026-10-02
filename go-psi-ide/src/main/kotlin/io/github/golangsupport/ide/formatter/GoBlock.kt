package io.github.golangsupport.ide.formatter

import com.intellij.formatting.ASTBlock
import com.intellij.formatting.Alignment
import com.intellij.formatting.Block
import com.intellij.formatting.ChildAttributes
import com.intellij.formatting.Indent
import com.intellij.formatting.Spacing
import com.intellij.formatting.Wrap
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.TokenType
import com.intellij.psi.formatter.FormatterUtil
import com.intellij.psi.tree.TokenSet
import io.github.golangsupport.lang.psi.GoTokenSets
import io.github.golangsupport.lang.psi.GoTypes.*

/**
 * A formatting block per non-empty PSI node (whitespace and inserted semicolons are not blocks).
 *
 * With a gofmt layout ([GoBlockContext.layout]) spacing and indentation reproduce it exactly:
 * - [getSpacing] returns the number of line breaks of the layout gap, or its number of spaces;
 * - a block that starts a line and is the outermost block starting at that token gets
 *   `Indent.getSpaceIndent(delta)`, where `delta` is its line indentation minus the indentation of
 *   the line where the nearest line-starting ancestor begins (the engine adds those up).
 *
 * Without a layout the indents follow the Go structure ([structuralIndent]) and spacing comes from
 * [GoSpacingBuilder]. [getChildAttributes] is always structural; it drives Enter.
 *
 * Column alignment is part of the layout (see `GoAlignmentStrategy`), so no [Alignment] objects
 * are used; no wrapping is done, like gofmt.
 */
class GoBlock(
    private val node: ASTNode,
    private val indent: Indent,
    private val context: GoBlockContext,
    /** Indentation (columns) of the line where the nearest line-starting block containing this one begins. */
    private val anchorColumns: Int,
) : ASTBlock {

    private var subBlocks: List<Block>? = null

    override fun getNode(): ASTNode = node

    override fun getTextRange(): TextRange = node.textRange

    override fun getWrap(): Wrap? = null

    override fun getIndent(): Indent = indent

    override fun getAlignment(): Alignment? = null

    override fun isLeaf(): Boolean = node.firstChildNode == null

    override fun getSubBlocks(): List<Block> {
        subBlocks?.let { return it }
        val result = ArrayList<Block>()
        // a partial layout (syntax errors) decides only the blocks inside its laid-out runs
        val layout = context.layout?.takeIf { it.covers(node.startOffset, node.startOffset + node.textLength) }
        // a block whose first token starts a line anchors the indents of its descendants
        val myColumns = layout?.let { lineColumnsAt(node.startOffset) }?.takeIf { it >= 0 } ?: anchorColumns
        var child = node.firstChildNode
        var first = true
        while (child != null) {
            if (isBlockNode(child)) {
                val childIndent = if (layout != null) layoutIndent(child, first, myColumns) else structuralIndent(child)
                result += GoBlock(child, childIndent, context, myColumns)
                first = false
            }
            child = child.treeNext
        }
        subBlocks = result
        return result
    }

    /** Line indentation (columns) if the token at [offset] starts a line in the layout, else -1. */
    private fun lineColumnsAt(offset: Int): Int {
        val layout = context.layout ?: return -1
        val index = layout.leafIndexAt(offset)
        if (index < 0) return -1
        if (index == 0) return 0
        return context.lineIndentColumns(layout.gapAt(index) ?: return -1)
    }

    private fun layoutIndent(child: ASTNode, isFirstChild: Boolean, anchor: Int): Indent {
        // the outermost block at a line start carries the indent; the blocks nested at the same offset do not
        if (isFirstChild && child.startOffset == node.startOffset) return Indent.getNoneIndent()
        val columns = lineColumnsAt(child.startOffset)
        if (columns < 0) return Indent.getNoneIndent()
        return Indent.getSpaceIndent(columns - anchor)
    }

    override fun getSpacing(child1: Block?, child2: Block): Spacing? {
        if (child1 == null) return null
        val leftEnd = child1.textRange.endOffset
        val rightStart = child2.textRange.startOffset
        val layout = context.layout
        if (layout != null) {
            val gap = layout.gapBefore(rightStart)
            if (gap != null) return layoutSpacing(gap)
        }
        if (hasSyntheticSemicolon(leftEnd, rightStart)) {
            // an inserted semicolon is a line break that must stay
            return Spacing.createSpacing(0, Int.MAX_VALUE, 1, true, context.common.KEEP_BLANK_LINES_IN_CODE)
        }
        return context.spacingBuilder.getSpacing(this, child1, child2)
    }

    private fun hasSyntheticSemicolon(from: Int, to: Int): Boolean {
        val text = context.text
        for (i in from until minOf(to, text.length)) {
            if (text[i] == '\n' && context.fileNode.findLeafElementAt(i)?.elementType == SEMICOLON_SYNTHETIC) return true
        }
        return false
    }

    override fun getChildAttributes(newChildIndex: Int): ChildAttributes {
        val indent = when (node.elementType) {
            BLOCK, STRUCT_TYPE, INTERFACE_TYPE, LITERAL_VALUE, ARGUMENT_LIST, PARAMETERS, TYPE_PARAMETERS,
            IMPORT_DECLARATION, CONST_DECLARATION, VAR_DECLARATION, TYPE_DECLARATION,
            EXPR_CASE_CLAUSE, TYPE_CASE_CLAUSE, COMM_CLAUSE,
            -> if (isAfterOpening(newChildIndex)) Indent.getNormalIndent() else Indent.getNoneIndent()
            // a new line right after a case clause continues that clause's body
            EXPR_SWITCH_STATEMENT, TYPE_SWITCH_STATEMENT, SELECT_STATEMENT -> {
                val previous = subBlocks?.getOrNull(newChildIndex - 1) as? GoBlock
                val type = previous?.node?.elementType
                if (type == EXPR_CASE_CLAUSE || type == TYPE_CASE_CLAUSE || type == COMM_CLAUSE) Indent.getNormalIndent() else Indent.getNoneIndent()
            }
            else -> Indent.getNoneIndent()
        }
        return ChildAttributes(indent, null)
    }

    /** True if the new child goes after the opening token of the construct (`{`, `(`, `[` or a case `:`). */
    private fun isAfterOpening(newChildIndex: Int): Boolean {
        val blocks = subBlocks
        if (blocks == null || newChildIndex == 0) return newChildIndex > 0
        for (i in 0 until minOf(newChildIndex, blocks.size)) {
            val type = (blocks[i] as GoBlock).node.elementType
            if (type in OPENERS) return true
        }
        return false
    }

    override fun isIncomplete(): Boolean {
        if (FormatterUtil.isIncomplete(node)) return true
        val closer = when (node.elementType) {
            BLOCK, STRUCT_TYPE, INTERFACE_TYPE, LITERAL_VALUE -> RBRACE
            ARGUMENT_LIST, PARAMETERS -> RPAREN
            else -> return false
        }
        var last = node.lastChildNode
        while (last != null && (last.elementType == TokenType.WHITE_SPACE || GoTokenSets.COMMENTS.contains(last.elementType))) last = last.treePrev
        return last?.elementType != closer
    }

    override fun toString(): String = "GoBlock(${node.elementType} $textRange)"

    // --- structural indents (no layout) -------------------------------------------------------

    private fun structuralIndent(child: ASTNode): Indent {
        val type = child.elementType
        return when (node.elementType) {
            BLOCK, STRUCT_TYPE, INTERFACE_TYPE, LITERAL_VALUE,
            IMPORT_DECLARATION, CONST_DECLARATION, VAR_DECLARATION, TYPE_DECLARATION,
            -> if (type in CLOSERS || type in OPENERS || isKeyword(type)) Indent.getNoneIndent() else Indent.getNormalIndent()
            ARGUMENT_LIST, PARAMETERS, TYPE_PARAMETERS, TYPE_ARGUMENTS ->
                if (type in CLOSERS || type in OPENERS) Indent.getNoneIndent() else Indent.getNormalIndent()
            EXPR_SWITCH_STATEMENT, TYPE_SWITCH_STATEMENT, SELECT_STATEMENT -> Indent.getNoneIndent()
            EXPR_CASE_CLAUSE, TYPE_CASE_CLAUSE, COMM_CLAUSE -> if (isAfterColon(child)) Indent.getNormalIndent() else Indent.getNoneIndent()
            OR_EXPR, AND_EXPR, CONDITIONAL_EXPR, ADD_EXPR, MUL_EXPR -> Indent.getContinuationWithoutFirstIndent()
            else -> Indent.getNoneIndent()
        }
    }

    private fun isAfterColon(child: ASTNode): Boolean {
        var c = child.treePrev
        while (c != null) {
            if (c.elementType == COLON) return true
            c = c.treePrev
        }
        return false
    }

    private fun isKeyword(type: com.intellij.psi.tree.IElementType): Boolean = GoTokenSets.KEYWORDS.contains(type)

    companion object {
        private val OPENERS = TokenSet.create(LBRACE, LPAREN, LBRACK, COLON)
        private val CLOSERS = TokenSet.create(RBRACE, RPAREN, RBRACK)

        /** Exactly the whitespace of a layout gap (GoLayout guarantees a line break wherever the source has an inserted semicolon). */
        fun layoutSpacing(gap: String): Spacing {
            var lineFeeds = 0
            for (ch in gap) if (ch == '\n') lineFeeds++
            return if (lineFeeds > 0) {
                Spacing.createSpacing(0, 0, lineFeeds, false, 0)
            } else {
                Spacing.createSpacing(gap.length, gap.length, 0, false, 0)
            }
        }

        fun isBlockNode(node: ASTNode): Boolean {
            val type = node.elementType
            return node.textLength > 0 && type != TokenType.WHITE_SPACE && type != SEMICOLON_SYNTHETIC
        }
    }
}
