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

/**
 * The block tree for Reformat Code with a complete layout: the file block with a flat list of leaf
 * blocks ("segments"). A segment is a maximal run of leaves whose whitespace in the source already
 * equals the layout's, so the engine only builds wrappers for, and only looks at, the gaps that
 * change; on gofmt-clean code the file is a single segment. This is what keeps reformat of a large,
 * mostly formatted file cheap: the per-block work of the engine (wrappers, whitespace checks,
 * indent computation) was most of the reformat time with one block per PSI node.
 *
 * Every segment that starts a line gets `Indent.getSpaceIndent(columns)` relative to the file
 * block (column 0), which with `TAB_SIZE` columns per tab reproduces gofmt's tabs; spacing between
 * segments is the layout gap, as in [GoBlock]. Whitespace inside a segment is never touched, which
 * is correct because it already is gofmt's.
 *
 * Indent-only requests (Auto-Indent Lines, line indent queries) and partial layouts keep the
 * PSI-shaped [GoBlock] tree, whose [GoBlock.getChildAttributes] needs the structure.
 */
internal class GoSegmentRootBlock(private val node: ASTNode, private val context: GoBlockContext) : ASTBlock {

    private val segments: List<Block> by lazy(LazyThreadSafetyMode.NONE) { buildSegments() }

    override fun getNode(): ASTNode = node

    override fun getTextRange(): TextRange = node.textRange

    override fun getSubBlocks(): List<Block> = segments

    override fun getWrap(): Wrap? = null

    override fun getIndent(): Indent = Indent.getNoneIndent()

    override fun getAlignment(): Alignment? = null

    override fun getSpacing(child1: Block?, child2: Block): Spacing? {
        if (child1 == null) return null
        val gap = context.layout!!.gapBefore(child2.textRange.startOffset) ?: return null
        return GoBlock.layoutSpacing(gap)
    }

    override fun getChildAttributes(newChildIndex: Int): ChildAttributes = ChildAttributes(Indent.getNoneIndent(), null)

    override fun isIncomplete(): Boolean = false

    override fun isLeaf(): Boolean = false

    override fun toString(): String = "GoSegmentRootBlock(${segments.size} segments)"

    private fun buildSegments(): List<Block> {
        val layout = context.layout!!
        val text = context.text
        val offsets = layout.leafOffsets
        val ends = layout.leafEnds
        val n = layout.leafCount
        val result = ArrayList<Block>()
        var first = 0
        for (i in 1..n) {
            if (i < n && sameWhitespace(text, ends[i - 1], offsets[i], layout.gapAt(i)!!)) continue
            result += Segment(TextRange(offsets[first], ends[i - 1]), indentOf(first))
            first = i
        }
        return result
    }

    private fun indentOf(leafIndex: Int): Indent {
        if (leafIndex == 0) return Indent.getNoneIndent()
        val columns = context.lineIndentColumns(context.layout!!.gapAt(leafIndex)!!)
        return if (columns < 0) Indent.getNoneIndent() else Indent.getSpaceIndent(columns)
    }

    private fun sameWhitespace(text: CharSequence, from: Int, to: Int, gap: String): Boolean {
        if (to - from != gap.length) return false
        for (k in gap.indices) if (text[from + k] != gap[k]) return false
        return true
    }

    /** A run of leaves laid out exactly as gofmt does; opaque to the engine. */
    private class Segment(private val range: TextRange, private val indent: Indent) : Block {
        override fun getTextRange(): TextRange = range
        override fun getSubBlocks(): List<Block> = emptyList()
        override fun getWrap(): Wrap? = null
        override fun getIndent(): Indent = indent
        override fun getAlignment(): Alignment? = null
        override fun getSpacing(child1: Block?, child2: Block): Spacing? = null
        override fun getChildAttributes(newChildIndex: Int): ChildAttributes = ChildAttributes(Indent.getNoneIndent(), null)
        override fun isIncomplete(): Boolean = false
        override fun isLeaf(): Boolean = true
        override fun toString(): String = "Segment$range"
    }
}
