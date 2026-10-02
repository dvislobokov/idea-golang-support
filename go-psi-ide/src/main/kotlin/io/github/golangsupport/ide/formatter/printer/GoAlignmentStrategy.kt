package io.github.golangsupport.ide.formatter.printer

import io.github.golangsupport.ide.formatter.printer.GoPrinter.Companion.ESC
import io.github.golangsupport.ide.formatter.printer.GoPrinter.Companion.FORMFEED
import io.github.golangsupport.ide.formatter.printer.GoPrinter.Companion.VTAB

/**
 * Column alignment of gofmt: a port of `text/tabwriter` configured as `cmd/gofmt` does
 * (`minwidth=0, tabwidth=8, padding=1, padchar=' ', DiscardEmptyColumns | TabIndent`).
 *
 * The printer separates cells with `\t`/`\v`; a column block is a run of consecutive lines that
 * all have a cell in that column, so blank lines (a line with one cell), formfeeds (`\f`, used by
 * the printer for section breaks such as a multi-line field or a comment on its own line) and a
 * change of indentation (indentation tabs are leading empty cells) all end an alignment group.
 * Escaped text ([ESC]...[ESC]) is opaque; its width counts code points minus the two escapes.
 *
 * The buffered cells are kept in flat primitive arrays (line `i` owns the cells
 * `[lineStart[i], lineStart[i + 1])`) instead of a list of cell objects per line.
 */
internal class GoAlignmentStrategy(private val output: GoTrimmer) {

    private val buf = StringBuilder()
    private var pos = 0

    // the cell being collected
    private var cellSize = 0
    private var cellWidth = 0
    private var endChar = NO_ESCAPE

    // terminated cells of the buffered lines
    private var cellSizes = IntArray(256)
    private var cellWidths = IntArray(256)
    private var cellHtab = BooleanArray(256)
    private var cellCount = 0
    private var lineStart = IntArray(64)
    private var lineCount = 0

    // column widths of the enclosing column blocks (a stack)
    private var widths = IntArray(16)
    private var widthCount = 0

    init {
        reset()
    }

    private fun reset() {
        buf.setLength(0)
        pos = 0
        cellSize = 0
        cellWidth = 0
        endChar = NO_ESCAPE
        cellCount = 0
        lineCount = 0
        widthCount = 0
        addLine()
    }

    private fun addLine() {
        if (lineCount == lineStart.size) lineStart = lineStart.copyOf(lineCount * 2)
        lineStart[lineCount++] = cellCount
    }

    private fun lineSize(line: Int): Int = (if (line + 1 < lineCount) lineStart[line + 1] else cellCount) - lineStart[line]

    private fun writePadding(textw: Int, cellwIn: Int, useTabs: Boolean) {
        if (useTabs) {
            val cellw = (cellwIn + TABWIDTH - 1) / TABWIDTH * TABWIDTH
            val n = cellw - textw
            output.writeRepeated('\t', (n + TABWIDTH - 1) / TABWIDTH)
            return
        }
        output.writeRepeated(' ', cellwIn - textw)
    }

    private fun writeLines(pos0: Int, line0: Int, line1: Int): Int {
        var p = pos0
        for (i in line0 until line1) {
            val start = lineStart[i]
            val size = lineSize(i)
            var useTabs = true
            for (j in 0 until size) {
                val c = start + j
                val s = cellSizes[c]
                if (s == 0) {
                    if (j < widthCount) writePadding(cellWidths[c], widths[j], useTabs)
                } else {
                    useTabs = false
                    output.write(buf, p, p + s)
                    p += s
                    if (j < widthCount) writePadding(cellWidths[c], widths[j], false)
                }
            }
            if (i + 1 == lineCount) {
                output.write(buf, p, p + cellSize)
                p += cellSize
            } else {
                output.write('\n')
            }
        }
        return p
    }

    private fun format(pos0: Int, line0In: Int, line1: Int): Int {
        var p = pos0
        var line0 = line0In
        val column = widthCount
        var thisLine = line0
        while (thisLine < line1) {
            if (column >= lineSize(thisLine) - 1) {
                thisLine++
                continue
            }
            p = writeLines(p, line0, thisLine)
            line0 = thisLine
            var width = MINWIDTH
            var discardable = true
            while (thisLine < line1) {
                if (column >= lineSize(thisLine) - 1) break
                val c = lineStart[thisLine] + column
                val w = cellWidths[c] + PADDING
                if (w > width) width = w
                if (cellWidths[c] > 0 || cellHtab[c]) discardable = false
                thisLine++
            }
            if (discardable) width = 0
            if (widthCount == widths.size) widths = widths.copyOf(widthCount * 2)
            widths[widthCount++] = width
            p = format(p, line0, thisLine)
            widthCount--
            line0 = thisLine
        }
        return writeLines(p, line0, line1)
    }

    private fun append(text: CharSequence, from: Int, to: Int) {
        buf.append(text, from, to)
        cellSize += to - from
    }

    private fun updateWidth() {
        cellWidth += Character.codePointCount(buf, pos, buf.length)
        pos = buf.length
    }

    private fun endEscape() {
        updateWidth()
        cellWidth -= 2
        pos = buf.length
        endChar = NO_ESCAPE
    }

    /** Terminates the current cell; returns the number of cells of the current line. */
    private fun terminateCell(htab: Boolean): Int {
        if (cellCount == cellSizes.size) {
            val n = cellCount * 2
            cellSizes = cellSizes.copyOf(n)
            cellWidths = cellWidths.copyOf(n)
            cellHtab = cellHtab.copyOf(n)
        }
        cellSizes[cellCount] = cellSize
        cellWidths[cellCount] = cellWidth
        cellHtab[cellCount] = htab
        cellCount++
        cellSize = 0
        cellWidth = 0
        return lineSize(lineCount - 1)
    }

    private fun flushNoDefers() {
        if (cellSize > 0) {
            if (endChar != NO_ESCAPE) endEscape()
            terminateCell(false)
        }
        format(0, 0, lineCount)
        reset()
    }

    fun write(text: String) {
        var n = 0
        for (i in 0 until text.length) {
            val ch = text[i]
            if (endChar == NO_ESCAPE) {
                when (ch) {
                    '\t', VTAB, '\n', FORMFEED -> {
                        append(text, n, i)
                        updateWidth()
                        n = i + 1
                        val ncells = terminateCell(ch == '\t')
                        if (ch == '\n' || ch == FORMFEED) {
                            addLine()
                            if (ch == FORMFEED || ncells == 1) flushNoDefers()
                        }
                    }
                    ESC -> {
                        append(text, n, i)
                        updateWidth()
                        n = i
                        endChar = ESC
                    }
                }
            } else if (ch == endChar) {
                append(text, n, i + 1)
                n = i + 1
                endEscape()
            }
        }
        append(text, n, text.length)
    }

    fun flush() {
        flushNoDefers()
        output.finish()
    }

    companion object {
        /** "Not inside an escape" (the escape character itself is U+0000). */
        private const val NO_ESCAPE = '￿'
        private const val MINWIDTH = 0
        private const val TABWIDTH = 8
        private const val PADDING = 1
    }
}

/**
 * `go/printer`'s trimmer: strips trailing blanks and tabs before line breaks and at the end,
 * converts formfeeds to newlines and removes the [ESC] brackets. When [regions] is given, the
 * output range of every escaped piece is recorded (start, end pairs in output order).
 */
internal class GoTrimmer(private val out: StringBuilder, private val regions: GoIntList?) {
    private var state = IN_SPACE
    private val space = StringBuilder()

    private fun resetSpace() {
        state = IN_SPACE
        space.setLength(0)
    }

    fun write(ch: Char) {
        when {
            // padding and line ends outside escapes (all the tabwriter writes char by char)
            state != IN_ESCAPE && (ch == ' ' || ch == '\t') -> {
                if (state == IN_TEXT) resetSpace()
                space.append(ch)
            }
            state != IN_ESCAPE && ch == '\n' -> {
                resetSpace()
                out.append('\n')
            }
            else -> write(ch.toString(), 0, 1)
        }
    }

    /** Writes [ch] [n] times. */
    fun writeRepeated(ch: Char, n: Int) {
        for (i in 0 until n) write(ch)
    }

    fun write(data: CharSequence) = write(data, 0, data.length)

    fun write(data: CharSequence, from: Int, to: Int) {
        var m = from
        for (n in from until to) {
            var b = data[n]
            if (b == VTAB) b = '\t'
            when (state) {
                IN_SPACE -> when (b) {
                    '\t', ' ' -> space.append(b)
                    '\n', FORMFEED -> {
                        resetSpace()
                        out.append('\n')
                    }
                    ESC -> {
                        out.append(space)
                        state = IN_ESCAPE
                        m = n + 1
                        regions?.add(out.length)
                    }
                    else -> {
                        out.append(space)
                        state = IN_TEXT
                        m = n
                    }
                }
                IN_ESCAPE -> if (b == ESC) {
                    out.append(data, m, n)
                    regions?.add(out.length)
                    resetSpace()
                }
                IN_TEXT -> when (b) {
                    '\t', ' ' -> {
                        out.append(data, m, n)
                        resetSpace()
                        space.append(b)
                    }
                    '\n', FORMFEED -> {
                        out.append(data, m, n)
                        resetSpace()
                        out.append('\n')
                    }
                    ESC -> {
                        out.append(data, m, n)
                        state = IN_ESCAPE
                        m = n + 1
                        regions?.add(out.length)
                    }
                }
            }
        }
        when (state) {
            IN_ESCAPE, IN_TEXT -> {
                out.append(data, m, to)
                if (state == IN_TEXT) resetSpace() else m = to
            }
        }
    }

    fun finish() {}

    companion object {
        private const val IN_SPACE = 0
        private const val IN_ESCAPE = 1
        private const val IN_TEXT = 2
    }
}
