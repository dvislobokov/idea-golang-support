package io.github.golangsupport.ide.formatter.printer

import com.intellij.lang.ASTNode
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import io.github.golangsupport.lang.psi.GoTokenSets
import io.github.golangsupport.lang.psi.GoTypes

/** A leaf of the source in file order: a token or a comment. */
internal class GoLeaf(val index: Int, val type: IElementType, val offset: Int, val text: String) {
    val end: Int get() = offset + text.length
    val isComment: Boolean get() = type == GoTypes.LINE_COMMENT || type == GoTypes.BLOCK_COMMENT
    override fun toString(): String = "$type@$offset '$text'"
}

/**
 * The flat view of a file used by the printer: line table, the significant leaves (tokens and
 * comments, without whitespace and inserted semicolons) and the inserted-semicolon offsets.
 *
 * A source may be a [slice] of a file (a run of top-level declarations, see `GoLayout`): its
 * leaves are then a contiguous part of the file's leaves, re-indexed from 0, while offsets, the
 * text and the line table stay those of the whole file.
 *
 * Lookups by offset are binary searches over sorted offset arrays; [lineFor] remembers the last
 * line it found because the printer asks for positions mostly in increasing order.
 */
internal class GoSource private constructor(
    val text: CharSequence,
    /** Tokens and comments in file order. */
    val leaves: List<GoLeaf>,
    /** Offsets of SEMICOLON_SYNTHETIC tokens (of the whole file). */
    val syntheticSemicolons: IntArray,
    private val lineStarts: IntArray,
    /** True if the tree has a PsiErrorElement (empty ones included); only set for a whole file. */
    val hasErrors: Boolean,
) {
    /** Tokens only (no comments). */
    val tokens: List<GoLeaf>

    /** Comments only. */
    val comments: List<GoLeaf>

    private val tokenOffsets: IntArray
    private val leafOffsets = IntArray(leaves.size) { leaves[it].offset }
    private var lineHint = 0

    init {
        val toks = ArrayList<GoLeaf>(leaves.size)
        val comms = ArrayList<GoLeaf>()
        for (leaf in leaves) if (leaf.isComment) comms += leaf else toks += leaf
        tokens = toks
        comments = comms
        tokenOffsets = IntArray(toks.size) { toks[it].offset }
    }

    /** The leaves `[from, to)` as a source of their own (indices restart at 0). */
    fun slice(from: Int, to: Int): GoSource {
        val sub = ArrayList<GoLeaf>(to - from)
        for (i in from until to) {
            val l = leaves[i]
            sub += GoLeaf(i - from, l.type, l.offset, l.text)
        }
        return GoSource(text, sub, syntheticSemicolons, lineStarts, false)
    }

    fun tokenIndexAt(offset: Int): Int {
        val i = java.util.Arrays.binarySearch(tokenOffsets, offset)
        if (i < 0) throw GoPrinterMismatch("no token at $offset")
        return i
    }

    fun leafIndexAt(offset: Int): Int? {
        val i = java.util.Arrays.binarySearch(leafOffsets, offset)
        return if (i < 0) null else i
    }

    /** The last token starting before [offset], or null. */
    fun lastTokenBefore(offset: Int): GoLeaf? {
        val i = java.util.Arrays.binarySearch(tokenOffsets, offset).let { if (it < 0) -it - 2 else it - 1 }
        return if (i >= 0) tokens[i] else null
    }

    /** True if an inserted semicolon starts in `[from, to)`. */
    fun hasSyntheticSemicolonIn(from: Int, to: Int): Boolean {
        val i = java.util.Arrays.binarySearch(syntheticSemicolons, from).let { if (it < 0) -it - 1 else it }
        return i < syntheticSemicolons.size && syntheticSemicolons[i] < to
    }

    /** 1-based line of [offset]; offsets past the end map to the last line. */
    fun lineFor(offset: Int): Int {
        if (offset < 0) return 0
        val starts = lineStarts
        val last = starts.size - 1
        // the printer moves forward through the file: try the remembered line and the next ones first
        var h = lineHint
        if (starts[h] <= offset) {
            var k = 0
            while (k < 4 && h < last && starts[h + 1] <= offset) {
                h++
                k++
            }
            if (h == last || starts[h + 1] > offset) {
                lineHint = h
                return h + 1
            }
        }
        var lo = 0
        var hi = last
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= offset) lo = mid else hi = mid - 1
        }
        lineHint = lo
        return lo + 1
    }

    fun columnFor(offset: Int): Int = offset - lineStarts[lineFor(offset) - 1] + 1

    companion object {
        /** The source of the file [root] whose text is [text]. */
        fun of(text: CharSequence, root: ASTNode): GoSource {
            val all = ArrayList<GoLeaf>()
            var synth = IntArray(16)
            var synthCount = 0
            // leaves partition the text, so offsets are a running sum (no getStartOffset walks); every
            // node is entered once on the way down, which is also where error elements are noticed
            var hasErrors = false
            var offset = 0
            var leaf: ASTNode? = root.firstChildNode
            if (leaf != null) {
                while (true) {
                    if (leaf!!.elementType === TokenType.ERROR_ELEMENT) hasErrors = true
                    leaf = leaf.firstChildNode ?: break
                }
            }
            while (leaf != null) {
                val length = leaf.textLength
                if (length > 0) {
                    val type = leaf.elementType
                    when {
                        type === TokenType.WHITE_SPACE -> {}
                        type === GoTypes.SEMICOLON_SYNTHETIC -> {
                            if (synthCount == synth.size) synth = synth.copyOf(synthCount * 2)
                            synth[synthCount++] = offset
                        }
                        else -> all += GoLeaf(all.size, type, offset, leaf.text)
                    }
                    offset += length
                }
                // next leaf: the first leaf of the next sibling of the nearest ancestor that has one
                var n: ASTNode = leaf
                leaf = null
                while (true) {
                    val next = n.treeNext
                    if (next != null) {
                        var d: ASTNode = next
                        while (true) {
                            if (d.elementType === TokenType.ERROR_ELEMENT) hasErrors = true
                            d = d.firstChildNode ?: break
                        }
                        leaf = d
                        break
                    }
                    n = n.treeParent ?: break
                    if (n === root) break
                }
            }
            var starts = IntArray(256)
            var count = 1
            for (i in 0 until text.length) {
                if (text[i] == '\n') {
                    if (count == starts.size) starts = starts.copyOf(count * 2)
                    starts[count++] = i + 1
                }
            }
            return GoSource(text, all, synth.copyOf(synthCount), starts.copyOf(count), hasErrors)
        }

        fun isTrivia(type: IElementType): Boolean =
            type == TokenType.WHITE_SPACE || type == GoTypes.SEMICOLON_SYNTHETIC || GoTokenSets.COMMENTS.contains(type)
    }
}

/** Thrown when the PSI does not have the shape the printer expects; the formatter then falls back. */
internal class GoPrinterMismatch(message: String) : RuntimeException(message, null, false, false)
