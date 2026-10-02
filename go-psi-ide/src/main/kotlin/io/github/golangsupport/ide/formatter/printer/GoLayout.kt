package io.github.golangsupport.ide.formatter.printer

import com.intellij.lang.ASTNode
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import io.github.golangsupport.lang.psi.GoTypes
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.atomic.AtomicInteger

/**
 * The gofmt layout of a file: for every significant leaf (token or comment, see [GoSource]) the
 * exact whitespace gofmt puts before it, plus the text after the last leaf.
 *
 * A layout is *complete* when the whole file was printed. When the file has syntax errors the
 * layout is *partial*: runs of consecutive error-free top-level declarations are printed on their
 * own (see [compute]) and only the leaves inside a run, except its first one, have a known gap;
 * [gapAt] is null for the others and the formatter falls back to structural rules there.
 *
 * A gap may contain line breaks that the source represents as SEMICOLON_SYNTHETIC tokens; the
 * formatter keeps those tokens and only edits the whitespace around them.
 */
class GoLayout internal constructor(
    /** Offsets of the leaves in the source, in order. */
    val leafOffsets: IntArray,
    /** End offsets of the leaves (index-aligned with [leafOffsets]). */
    val leafEnds: IntArray,
    /** Whitespace before each leaf, null where unknown (index-aligned with [leafOffsets]). */
    private val gaps: Array<String?>,
    /** Whitespace after the last leaf (null for a partial layout). */
    val tail: String?,
    /** True if the whole file was laid out. */
    val isComplete: Boolean,
    private val formatted: String?,
    /** Leaf index of the first leaf of the laid-out run containing each leaf, -1 outside runs. */
    private val runStart: IntArray?,
) {
    /**
     * The complete formatted text; only for a layout computed with `keepText` (tests). The cached
     * layouts do not keep it: the formatter needs only the gaps.
     */
    val text: String get() = formatted ?: throw IllegalStateException("formatted text not kept")

    val leafCount: Int get() = leafOffsets.size

    /** Index of the leaf starting at [offset], or -1. */
    fun leafIndexAt(offset: Int): Int = java.util.Arrays.binarySearch(leafOffsets, offset).let { if (it < 0) -1 else it }

    /** Whitespace gofmt puts before leaf [index], or null if unknown. */
    fun gapAt(index: Int): String? = gaps[index]

    /** Whitespace gofmt puts before the leaf starting at [offset], or null if there is no such leaf or it is unknown. */
    fun gapBefore(offset: Int): String? = leafIndexAt(offset).let { if (it < 0) null else gaps[it] }

    /**
     * True if the layout decides the whitespace inside `[start, end)`: every leaf there lies in one
     * laid-out run (always true for a complete layout).
     */
    fun covers(start: Int, end: Int): Boolean {
        val runs = runStart ?: return true
        val first = java.util.Arrays.binarySearch(leafOffsets, start).let { if (it < 0) -it - 1 else it }
        val last = java.util.Arrays.binarySearch(leafOffsets, end).let { if (it < 0) -it - 2 else it - 1 }
        if (first > last) return true
        // runs are contiguous: the first and the last leaf in the same run means all are
        return runs[first] >= 0 && runs[first] == runs[last]
    }

    companion object {
        private val LOG = Logger.getInstance(GoLayout::class.java)
        private val CACHE_KEY = Key.create<CachedValue<GoLayout?>>("gopsi.formatter.layout")
        private val computations = AtomicInteger()

        /** Number of layout computations so far (test hook for the cache). */
        @TestOnly
        fun computationCount(): Int = computations.get()

        /**
         * The layout of [file], cached until the file or the code style settings change, so that
         * repeated requests (reformat-on-save, range reformat, Auto-Indent Lines) reuse it.
         */
        fun cached(file: PsiFile, settings: CodeStyleSettings): GoLayout? =
            CachedValuesManager.getManager(file.project).getCachedValue(file, CACHE_KEY, {
                CachedValueProvider.Result.create(compute(file.node), file, settings.modificationTracker)
            }, false)

        /**
         * Computes the layout of [file] (a Go file node): complete when the printer handles the
         * whole file, partial when the file has syntax errors, null if not even one declaration
         * could be laid out.
         */
        fun compute(file: ASTNode): GoLayout? {
            val text = file.chars
            if (text.indexOf(GoPrinter.ESC) >= 0) return null
            val source = guarded("gofmt layout") { GoSource.of(text, file) } ?: return null
            if (source.hasErrors) return guarded("partial gofmt layout") { computePartial(file, source) }
            // a construct the printer does not know disables only the declaration that has it
            return guarded("gofmt layout") { layoutOf(file, source, keepText = false) }
                ?: guarded("partial gofmt layout") { computePartial(file, source) }
        }

        private fun <T> guarded(what: String, body: () -> T?): T? = try {
            body()
        } catch (e: GoPrinterMismatch) {
            LOG.debug("$what unavailable: ${e.message}")
            null
        } catch (e: RuntimeException) {
            if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
            LOG.warn("$what failed", e)
            null
        } catch (e: StackOverflowError) {
            LOG.warn("$what failed: deep nesting")
            null
        }

        /** The complete layout of [file]; throws [GoPrinterMismatch] if the printer cannot handle it. */
        internal fun computeOrThrow(file: ASTNode): GoLayout {
            val text = file.chars
            if (text.indexOf(GoPrinter.ESC) >= 0) throw GoPrinterMismatch("text contains U+0000")
            return layoutOf(file, GoSource.of(text, file), keepText = true)
        }

        private fun layoutOf(file: ASTNode, source: GoSource, keepText: Boolean): GoLayout {
            computations.incrementAndGet()
            val ast = GoAstBuilder(source).file(file)
            val printed = print(source, wholeFile = true) { it.printFile(ast) }
            val leaves = source.leaves
            return GoLayout(
                IntArray(leaves.size) { leaves[it].offset },
                IntArray(leaves.size) { leaves[it].end },
                printed.gaps,
                printed.tail,
                true,
                if (keepText) printed.result.toString() else null,
                null,
            )
        }

        /**
         * Lays out each maximal run of consecutive error-free top-level units (package clause,
         * import list, declarations) like gofmt would print it: the units of a run are printed as
         * one declaration list, so blank-line normalisation and alignment between them are exact;
         * the first leaf of every run and everything outside runs are left to the fallback rules.
         */
        private fun computePartial(file: ASTNode, source: GoSource): GoLayout? {
            computations.incrementAndGet()
            val leaves = source.leaves
            val gaps = arrayOfNulls<String>(leaves.size)
            val runStart = IntArray(leaves.size) { -1 }

            // top-level units in order, each marked clean or broken
            val units = ArrayList<ASTNode>()
            val clean = ArrayList<Boolean>()
            var c = file.firstChildNode
            while (c != null) {
                val type = c.elementType
                if (c.textLength > 0 && !GoSource.isTrivia(type) && type != GoTypes.SEMICOLON) {
                    units += c
                    clean += type in TOP_LEVEL && !hasErrorElements(c)
                }
                c = c.treeNext
            }
            var any = false
            var i = 0
            while (i < units.size) {
                if (!clean[i]) {
                    i++
                    continue
                }
                var j = i
                while (j + 1 < units.size && clean[j + 1]) j++
                val from = leafIndexFrom(source, units[i].startOffset)
                val to = leafIndexFrom(source, units[j].startOffset + units[j].textLength)
                if (from < to && layoutRun(source, units.subList(i, j + 1), from, to, gaps)) {
                    for (k in from until to) runStart[k] = from
                    // the gap before a run is decided by what precedes it, except at the start of the file
                    if (from != 0) gaps[from] = null
                    any = true
                }
                i = j + 1
            }
            if (!any) return null
            return GoLayout(
                IntArray(leaves.size) { leaves[it].offset },
                IntArray(leaves.size) { leaves[it].end },
                gaps,
                null,
                false,
                null,
                runStart,
            )
        }

        /** Prints the units of one run into [gaps]; false (and [gaps] untouched) if the printer cannot handle them. */
        private fun layoutRun(source: GoSource, units: List<ASTNode>, from: Int, to: Int, gaps: Array<String?>): Boolean =
            guarded("gofmt layout of a declaration run") {
                val slice = source.slice(from, to)
                val builder = GoAstBuilder(slice)
                val decls = ArrayList<GoDecl>()
                var packageClause: ASTNode? = null
                for (u in units) {
                    if (u.elementType == GoTypes.PACKAGE_CLAUSE) packageClause = u else decls += builder.topLevelDecls(u)
                }
                val printed = print(slice, wholeFile = false) { printer ->
                    if (packageClause != null) {
                        printer.printFile(builder.fileOf(packageClause, decls))
                    } else {
                        printer.printDecls(decls)
                    }
                }
                printed.gaps.copyInto(gaps, from)
                true
            } ?: false

        private class Printed(val gaps: Array<String?>, val tail: String, val result: CharSequence)

        /**
         * Runs the printer and the tabwriter over [source] and splits the result into the gap before
         * every leaf; checks that every leaf is printed once, in order and unchanged, that gaps are
         * whitespace only and that every inserted semicolon keeps a line break (for a run that is not
         * the [wholeFile], only those between its leaves: the gaps around it are not decided here).
         */
        private fun print(source: GoSource, wholeFile: Boolean, body: (GoPrinter) -> Unit): Printed {
            val printer = GoPrinter(source)
            body(printer)

            val result = StringBuilder(source.text.length + 64)
            val regions = GoIntList(printer.pieceCount * 2)
            val tabwriter = GoAlignmentStrategy(GoTrimmer(result, regions))
            tabwriter.write(printer.output.toString())
            tabwriter.flush()

            val pieces = printer.pieces
            val pieceCount = printer.pieceCount
            if (regions.size != pieceCount * 2) throw GoPrinterMismatch("piece count $pieceCount vs regions ${regions.size / 2}")
            val leaves = source.leaves
            val starts = IntArray(leaves.size) { -1 }
            val ends = IntArray(leaves.size) { -1 }
            for (k in 0 until pieceCount) {
                val leaf = pieces[k]
                if (starts[leaf] < 0) starts[leaf] = regions[2 * k]
                ends[leaf] = regions[2 * k + 1]
            }
            val gaps = arrayOfNulls<String>(leaves.size)
            var prevEnd = 0
            for ((i, leaf) in leaves.withIndex()) {
                if (starts[i] < 0) throw GoPrinterMismatch("leaf not printed: $leaf")
                if (starts[i] < prevEnd) throw GoPrinterMismatch("leaf out of order: $leaf")
                val length = leaf.text.length
                if (ends[i] - starts[i] != length || !result.regionMatches(starts[i], leaf.text, 0, length)) {
                    throw GoPrinterMismatch("leaf text changed: $leaf")
                }
                gaps[i] = gapString(result, prevEnd, starts[i]) ?: throw GoPrinterMismatch("non-whitespace gap before $leaf")
                prevEnd = ends[i]
            }
            val tail = gapString(result, prevEnd, result.length) ?: throw GoPrinterMismatch("non-whitespace after the last leaf")
            // an inserted semicolon is a token: the gap that holds it must keep a line break
            if (leaves.isNotEmpty()) {
                val first = leaves[0].offset
                val lastEnd = leaves[leaves.size - 1].end
                var next = 0
                for (offset in source.syntheticSemicolons) {
                    if (!wholeFile && (offset < first || offset >= lastEnd)) continue
                    while (next < leaves.size && leaves[next].offset < offset) next++
                    val gap = if (next < leaves.size) gaps[next]!! else tail
                    if (gap.indexOf('\n') < 0) throw GoPrinterMismatch("line break of an inserted semicolon at $offset removed")
                }
            }
            return Printed(gaps, tail, result)
        }

        /** [s] in `[from, to)` if it is whitespace only (common short gaps are shared), else null. */
        private fun gapString(s: CharSequence, from: Int, to: Int): String? {
            val length = to - from
            if (length == 0) return ""
            for (i in from until to) {
                val ch = s[i]
                if (ch != ' ' && ch != '\t' && ch != '\n') return null
            }
            if (length == 1) {
                when (s[from]) {
                    ' ' -> return " "
                    '\n' -> return "\n"
                }
            }
            if (length <= COMMON_GAPS_MAX && s[from] == '\n') {
                // "\n" followed by tabs: one shared instance per indentation depth
                var tabs = true
                for (i in from + 1 until to) if (s[i] != '\t') tabs = false
                if (tabs) return COMMON_GAPS[length - 1]
            }
            return s.subSequence(from, to).toString()
        }

        private const val COMMON_GAPS_MAX = 16
        private val COMMON_GAPS = Array(COMMON_GAPS_MAX) { "\n" + "\t".repeat(it) }

        private val TOP_LEVEL = setOf(
            GoTypes.PACKAGE_CLAUSE, GoTypes.IMPORT_LIST, GoTypes.IMPORT_DECLARATION, GoTypes.CONST_DECLARATION,
            GoTypes.VAR_DECLARATION, GoTypes.TYPE_DECLARATION, GoTypes.FUNCTION_DECLARATION, GoTypes.METHOD_DECLARATION,
        )

        private fun leafIndexFrom(source: GoSource, offset: Int): Int {
            val leaves = source.leaves
            var lo = 0
            var hi = leaves.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (leaves[mid].offset < offset) lo = mid + 1 else hi = mid
            }
            return lo
        }

        /** True if [root] contains a PsiErrorElement (empty ones included). */
        fun hasErrorElements(root: ASTNode): Boolean {
            if (root.elementType == TokenType.ERROR_ELEMENT) return true
            var n: ASTNode? = root.firstChildNode ?: return false
            while (n != null) {
                if (n.elementType == TokenType.ERROR_ELEMENT) return true
                val child = n.firstChildNode
                if (child != null) {
                    n = child
                    continue
                }
                while (n != null && n.treeNext == null) {
                    n = n.treeParent
                    if (n === root) return false
                }
                n = n?.treeNext
            }
            return false
        }
    }
}

/** A growable int array (no boxing). */
internal class GoIntList(capacity: Int = 16) {
    private var data = IntArray(maxOf(capacity, 4))
    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    operator fun get(i: Int): Int = data[i]
}
