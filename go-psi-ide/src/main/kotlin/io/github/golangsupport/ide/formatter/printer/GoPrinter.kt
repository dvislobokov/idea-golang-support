package io.github.golangsupport.ide.formatter.printer

import com.intellij.psi.tree.IElementType
import io.github.golangsupport.lang.psi.GoTypes.*
import kotlin.math.max
import kotlin.math.min

/**
 * A port of `go/printer` (printer.go and nodes.go of Go 1.27) that prints the [GoAst] of a file
 * into the printer's intermediate format: tokens bracketed by [ESC], and whitespace/control
 * characters (`\t`, `\v`, `\n`, `\f`) that [GoAlignmentStrategy] (the `text/tabwriter` port)
 * turns into the final layout.
 *
 * Differences from `go/printer`, all forced by the rule that formatting only changes whitespace:
 * - tokens are taken from the source token stream in order; tokens gofmt would drop (explicit
 *   semicolons, trailing commas before a closer on the same line, redundant parentheses) are kept
 *   and printed glued to the previous token or as written;
 * - comments are interspersed strictly in source order (a comment is flushed before the first
 *   token that follows it in the source); their text is never rewritten (no `//` trimming, no
 *   doc-comment reformatting, no re-indentation of `/* */` comment lines);
 * - number literals and import paths are not normalised.
 *
 * In size mode (used by `nodeSize`) the printer prints a single node without comments.
 */
internal class GoPrinter private constructor(
    private val src: GoSource,
    private val sizeMode: Boolean,
    private val nodeSizes: MutableMap<GoNode, Int>,
    startToken: Int,
) {
    constructor(src: GoSource) : this(src, false, HashMap(), 0)

    private class Pos(var offset: Int = 0, var line: Int = 0, var column: Int = 0) {
        val isValid: Boolean get() = line > 0
        fun copy() = Pos(offset, line, column)
        fun set(o: Pos) {
            offset = o.offset; line = o.line; column = o.column
        }
    }

    /** Raw printer result (`p.output`). */
    val output = StringBuilder()

    /** Leaf index of each escaped piece written to [output], in order (the first [pieceCount] entries). */
    var pieces = IntArray(if (sizeMode) 0 else 1024)
        private set

    var pieceCount = 0
        private set

    private var indent = 0
    private var level = 0
    private var mode = 0
    private var endAlignment = false
    private var impliedSemi = false
    private var lastTok: Any? = null
    private var prevOpen: Any? = null
    private val wsbuf = ArrayList<Char>(16)

    private var pos = Pos(0, 1, 1)
    private val out = Pos(0, 1, 1)
    private var last = Pos()
    private var linePtr: IntArray? = null

    private var cursor = startToken
    private var cindex = 0
    private val comments: List<GoLeaf> = if (sizeMode) emptyList() else src.comments

    // ---------------------------------------------------------------------------------------------
    // Entry points

    fun printFile(file: GoFileNode) {
        file(file)
        impliedSemi = false
        flush(Pos(INFINITY, INFINITY, 0), EOF, INFINITY)
        if (cursor != src.tokens.size) throw GoPrinterMismatch("unprinted token ${src.tokens[cursor]}")
    }

    /** Prints top-level declarations without a package clause (a run of a partial layout, see `GoLayout`). */
    fun printDecls(decls: List<GoDecl>) {
        declList(decls)
        impliedSemi = false
        flush(Pos(INFINITY, INFINITY, 0), EOF, INFINITY)
        if (cursor != src.tokens.size) throw GoPrinterMismatch("unprinted token ${src.tokens[cursor]}")
    }

    private fun printNode(node: GoNode) {
        when (node) {
            is GoExpr -> expr(node)
            is GoStmt -> {
                if (node is GoLabeledStmt) indent = 1
                stmt(node, false)
            }
            else -> throw GoPrinterMismatch("size of ${node.javaClass.simpleName}")
        }
        impliedSemi = false
        flush(Pos(INFINITY, INFINITY, 0), EOF, INFINITY)
    }

    // ---------------------------------------------------------------------------------------------
    // printer.go

    private fun posFor(offset: Int): Pos =
        if (offset == NO_POS) Pos() else Pos(offset, src.lineFor(offset), src.columnFor(offset))

    private fun lineFor(offset: Int): Int = if (offset == NO_POS) 0 else src.lineFor(offset)

    private fun linesFrom(line: IntArray): Int = out.line - line[0]

    private fun recordLine(line: IntArray) {
        linePtr = line
    }

    private fun setPos(offset: Int) {
        if (offset != NO_POS) pos = posFor(offset)
    }

    private fun writeIndent() {
        val n = indent
        repeat(n) { output.append('\t') }
        pos.offset += n
        pos.column += n
        out.column += n
    }

    private fun writeByte(c: Char, n: Int) {
        var ch = c
        if (endAlignment) {
            when (ch) {
                '\t', VTAB -> ch = ' '
                '\n', FORMFEED -> {
                    ch = FORMFEED
                    endAlignment = false
                }
            }
        }
        if (out.column == 1) writeIndent()
        repeat(n) { output.append(ch) }
        pos.offset += n
        if (ch == '\n' || ch == FORMFEED) {
            pos.line += n
            out.line += n
            pos.column = 1
            out.column = 1
            return
        }
        pos.column += n
        out.column += n
    }

    private fun writeString(p: Pos, s: String, leafIndex: Int, indentFirst: Boolean = true) {
        if (out.column == 1 && indentFirst) writeIndent()
        if (p.isValid) pos = p.copy()
        output.append(ESC)
        output.append(s)
        output.append(ESC)
        if (!sizeMode) {
            if (pieceCount == pieces.size) pieces = pieces.copyOf(pieceCount * 2)
            pieces[pieceCount++] = leafIndex
        }
        var nlines = 0
        var li = 0
        for (i in s.indices) {
            val ch = s[i]
            if (ch == '\n' || ch == FORMFEED) {
                nlines++
                li = i
                endAlignment = true
            }
        }
        pos.offset += s.length
        if (nlines > 0) {
            pos.line += nlines
            out.line += nlines
            val c = utf8Length(s, li, s.length)
            pos.column = c
            out.column = c
        } else {
            val bytes = utf8Length(s)
            pos.column += bytes
            out.column += bytes
        }
        last.set(pos)
    }

    private fun writeCommentPrefix(p: Pos, next: Pos, prev: GoLeaf?, tok: Any?) {
        if (output.isEmpty()) return
        if (p.line == last.line && (prev == null || prev.text[1] != '/')) {
            var hasSep = false
            if (prev == null) {
                var j = 0
                var i = 0
                while (i < wsbuf.size) {
                    val ch = wsbuf[i]
                    if (ch == BLANK) {
                        wsbuf[i] = IGNORE
                        i++
                        continue
                    }
                    if (ch == VTAB) {
                        hasSep = true
                        i++
                        continue
                    }
                    if (ch == INDENT) {
                        i++
                        continue
                    }
                    j = i
                    break
                }
                writeWhitespace(j)
            }
            if (!hasSep) {
                var sep = '\t'
                if (p.line == next.line) sep = ' '
                writeByte(sep, 1)
            }
        } else {
            var droppedLinebreak = false
            var j = 0
            var i = 0
            while (i < wsbuf.size) {
                val ch = wsbuf[i]
                if (ch == BLANK || ch == VTAB) {
                    wsbuf[i] = IGNORE
                    i++
                    continue
                }
                if (ch == INDENT) {
                    i++
                    continue
                }
                if (ch == UNINDENT) {
                    if (i + 1 < wsbuf.size && wsbuf[i + 1] == UNINDENT) {
                        i++
                        continue
                    }
                    if (tok != RBRACE && p.column == next.column) {
                        i++
                        continue
                    }
                } else if (ch == NEWLINE || ch == FORMFEED) {
                    wsbuf[i] = IGNORE
                    droppedLinebreak = prev == null
                }
                j = i
                break
            }
            writeWhitespace(j)
            var n = 0
            if (p.isValid && last.isValid) {
                n = max(p.line - last.line, 0)
            }
            if (indent == 0 && droppedLinebreak) n++
            if (n == 0 && prev != null && prev.text[1] == '/') n = 1
            if (n > 0) writeByte(FORMFEED, nlimit(n))
        }
    }

    private fun writeComment(c: GoLeaf) {
        val text = c.text
        val p = posFor(c.offset)
        val savedIndent = indent
        if (text.startsWith("//line ") && p.column == 1) indent = 0
        try {
            if (text[1] == '/' || !text.contains('\n')) {
                writeString(p, text, c.index)
                return
            }
            // /*-style comment spanning lines: written line by line like go/printer, but the
            // continuation lines keep their original text (and thus their original indentation)
            val lines = text.split('\n')
            var lp = p
            for ((i, line) in lines.withIndex()) {
                if (i > 0) {
                    writeByte(FORMFEED, 1)
                    lp = pos.copy()
                }
                if (line.isNotEmpty()) writeString(lp, line, c.index, indentFirst = i == 0)
            }
        } finally {
            indent = savedIndent
        }
    }

    /** Returns (wroteNewline, droppedFF) packed as bits 1 and 2. */
    private fun writeCommentSuffix(needsLinebreakIn: Boolean): Int {
        var needsLinebreak = needsLinebreakIn
        var wroteNewline = false
        var droppedFF = false
        for (i in wsbuf.indices) {
            when (val ch = wsbuf[i]) {
                BLANK, VTAB -> wsbuf[i] = IGNORE
                INDENT, UNINDENT -> {}
                NEWLINE, FORMFEED -> {
                    if (needsLinebreak) {
                        needsLinebreak = false
                        wroteNewline = true
                    } else {
                        if (ch == FORMFEED) droppedFF = true
                        wsbuf[i] = IGNORE
                    }
                }
            }
        }
        writeWhitespace(wsbuf.size)
        if (needsLinebreak) {
            writeByte('\n', 1)
            wroteNewline = true
        }
        return (if (wroteNewline) 1 else 0) or (if (droppedFF) 2 else 0)
    }

    private fun containsLinebreak(): Boolean = wsbuf.any { it == NEWLINE || it == FORMFEED }

    private fun commentBefore(offset: Int): Boolean = cindex < comments.size && comments[cindex].offset < offset

    private fun commentSizeBefore(offset: Int): Int {
        var size = 0
        var i = cindex
        while (i < comments.size && comments[i].offset < offset) {
            size += utf8Length(comments[i].text)
            i++
        }
        return size
    }

    private fun intersperseComments(next: Pos, tok: Any?, nextOffset: Int): Int {
        var lastComment: GoLeaf? = null
        while (commentBefore(nextOffset)) {
            val c = comments[cindex]
            writeCommentPrefix(posFor(c.offset), next, lastComment, tok)
            writeComment(c)
            lastComment = c
            cindex++
        }
        val lc = lastComment ?: return 0
        var needsLinebreak = false
        if (mode and NO_EXTRA_BLANK == 0 &&
            lc.text[1] == '*' && lineFor(lc.offset) == next.line &&
            tok != COMMA &&
            (tok != RPAREN || prevOpen == LPAREN) &&
            (tok != RBRACK || prevOpen == LBRACK)
        ) {
            if (containsLinebreak() && mode and NO_EXTRA_LINEBREAK == 0 && level == 0) {
                needsLinebreak = true
            } else {
                writeByte(' ', 1)
            }
        }
        if (lc.text[1] == '/' || tok == EOF || tok == RBRACE && mode and NO_EXTRA_LINEBREAK == 0) {
            needsLinebreak = true
        }
        return writeCommentSuffix(needsLinebreak)
    }

    private fun writeWhitespace(n: Int) {
        var i = 0
        while (i < n) {
            when (val ch = wsbuf[i]) {
                IGNORE -> {}
                INDENT -> indent++
                UNINDENT -> {
                    indent--
                    if (indent < 0) indent = 0
                }
                NEWLINE, FORMFEED -> {
                    if (i + 1 < n && wsbuf[i + 1] == UNINDENT) {
                        wsbuf[i] = UNINDENT
                        wsbuf[i + 1] = FORMFEED
                        continue // do it again
                    }
                    writeByte(ch, 1)
                }
                else -> writeByte(ch, 1)
            }
            i++
        }
        // shift the rest down (wsbuf = wsbuf[n:]) without allocating
        if (n >= wsbuf.size) wsbuf.clear() else if (n > 0) wsbuf.subList(0, n).clear()
    }

    private fun flush(next: Pos, tok: Any?, nextOffset: Int): Int =
        if (commentBefore(nextOffset)) {
            intersperseComments(next, tok, nextOffset)
        } else {
            writeWhitespace(wsbuf.size)
            0
        }

    // --- print ------------------------------------------------------------------------------------

    private fun updatePrevOpen() {
        prevOpen = when (lastTok) {
            null -> prevOpen
            LPAREN, LBRACK -> lastTok
            else -> null
        }
    }

    private fun ws(vararg items: Char) {
        for (x in items) {
            updatePrevOpen()
            if (x == IGNORE) continue
            wsbuf += x
            if (x == NEWLINE || x == FORMFEED) impliedSemi = false
            lastTok = null
        }
    }

    private fun toggle(m: Int) {
        updatePrevOpen()
        mode = mode xor m
    }

    /** Glues source tokens that go/printer would not print (explicit `;`, a trailing `,`). */
    private fun glueExtraTokens(expected: IElementType?) {
        while (cursor < src.tokens.size) {
            val t = src.tokens[cursor]
            val extra = t.type == SEMICOLON && expected != SEMICOLON ||
                t.type == COMMA && expected != COMMA && (expected == RPAREN || expected == RBRACK || expected == RBRACE)
            if (!extra) return
            if (commentBefore(t.offset)) throw GoPrinterMismatch("comment before extra token $t")
            writeString(posFor(t.offset), t.text, t.index)
            impliedSemi = false
            cursor++
        }
    }

    private fun nextToken(expected: IElementType?): GoLeaf {
        glueExtraTokens(expected)
        val t = src.tokens.getOrNull(cursor) ?: throw GoPrinterMismatch("expected $expected at end of file")
        if (expected != null && t.type != expected) throw GoPrinterMismatch("expected $expected, found $t")
        cursor++
        return t
    }

    private fun hasToken(type: IElementType): Boolean {
        var i = cursor
        while (i < src.tokens.size && src.tokens[i].type == SEMICOLON && type != SEMICOLON) i++
        return src.tokens.getOrNull(i)?.type == type
    }

    /** `p.print(token)` for keywords, operators and punctuation. */
    private fun tok(type: IElementType) {
        val t = nextToken(type)
        printLeaf(t, type, isToken = true, impliedSemiAfter = type in IMPLIED_SEMI_TOKENS)
    }

    /** Prints [type] only if the source has it next. */
    private fun optTok(type: IElementType) {
        if (hasToken(type)) tok(type)
    }

    private fun ident(id: GoIdent) {
        val t = nextToken(null)
        if (t.offset != id.pos) throw GoPrinterMismatch("expected identifier at ${id.pos}, found $t")
        printLeaf(t, IDENTIFIER, isToken = false, impliedSemiAfter = true)
    }

    private fun basicLit(x: GoBasicLit) {
        val t = nextToken(null)
        if (t.offset != x.pos) throw GoPrinterMismatch("expected literal at ${x.pos}, found $t")
        printLeaf(t, x.kind, isToken = false, impliedSemiAfter = true)
    }

    private fun printLeaf(t: GoLeaf, kind: IElementType, isToken: Boolean, impliedSemiAfter: Boolean) {
        updatePrevOpen()
        var impliedSemiNext = impliedSemiAfter
        if (isToken && mayCombine(lastTok, t.text[0])) {
            wsbuf.clear()
            wsbuf += ' '
        }
        lastTok = kind
        val next = pos.copy()
        val r = flush(next, kind, t.offset)
        val wroteNewline = r and 1 != 0
        val droppedFF = r and 2 != 0
        if (!impliedSemi) {
            var n = nlimit(next.line - pos.line)
            if (wroteNewline && n == MAX_NEWLINES) n = MAX_NEWLINES - 1
            if (n > 0) {
                writeByte(if (droppedFF) FORMFEED else '\n', n)
                impliedSemiNext = false
            }
        }
        linePtr?.let {
            it[0] = out.line
            linePtr = null
        }
        writeString(next, t.text, t.index)
        impliedSemi = impliedSemiNext
    }

    // ---------------------------------------------------------------------------------------------
    // nodes.go: common

    private fun linebreak(line: Int, min: Int, w: Char, newSection: Boolean): Int {
        var n = max(nlimit(line - pos.line), min)
        var nbreaks = 0
        if (n > 0) {
            ws(w)
            if (newSection) {
                ws(FORMFEED)
                n--
                nbreaks = 2
            }
            nbreaks += n
            while (n > 0) {
                ws(NEWLINE)
                n--
            }
        }
        return nbreaks
    }

    private fun identList(list: List<GoIdent>, indent: Boolean) {
        exprList(NO_POS, list, 1, if (indent) 0 else NO_INDENT, NO_POS)
    }

    private fun exprList(prev0: Int, list: List<GoExpr>, depth: Int, mode: Int, next0: Int) {
        if (list.isEmpty()) return
        val prev = posFor(prev0)
        val next = posFor(next0)
        var line = lineFor(list[0].pos)
        val endLine = lineFor(list.last().end)

        if (prev.isValid && prev.line == line && line == endLine) {
            for ((i, x) in list.withIndex()) {
                if (i > 0) {
                    setPos(x.pos)
                    tok(COMMA)
                    ws(BLANK)
                }
                expr0(x, depth)
            }
            return
        }

        var w = if (mode and NO_INDENT == 0) INDENT else IGNORE
        var prevBreak = -1
        if (prev.isValid && prev.line < line && linebreak(line, 0, w, true) > 0) {
            w = IGNORE
            prevBreak = 0
        }

        var size = 0
        var log2sum = 0.0
        var count = 0
        var prevLine = prev.line
        for ((i, x) in list.withIndex()) {
            line = lineFor(x.pos)
            var useFF = true
            val prevSize = size
            val inf = 1_000_000
            size = nodeSize(x, inf)
            val pair = x as? GoKeyValueExpr
            if (size <= inf && prev.isValid && next.isValid) {
                if (pair != null) size = nodeSize(pair.key, inf)
            } else {
                size = 0
            }
            if (prevSize > 0 && size > 0) {
                if (count == 0 || prevSize <= SMALL_SIZE && size <= SMALL_SIZE) {
                    useFF = false
                } else {
                    val r = 2.5
                    val geomean = exp2ish(log2sum / count)
                    val ratio = size / geomean
                    useFF = r * ratio <= 1 || r <= ratio
                }
            }

            val needsLinebreak = 0 < prevLine && prevLine < line
            if (i > 0) {
                if (!needsLinebreak) setPos(x.pos)
                tok(COMMA)
                var needsBlank = true
                if (needsLinebreak) {
                    val nbreaks = linebreak(line, 0, w, useFF || prevBreak + 1 < i)
                    if (nbreaks > 0) {
                        w = IGNORE
                        prevBreak = i
                        needsBlank = false
                    }
                    if (nbreaks > 1) {
                        log2sum = 0.0
                        count = 0
                    }
                }
                if (needsBlank) ws(BLANK)
            }

            if (list.size > 1 && pair != null && size > 0 && needsLinebreak) {
                expr(pair.key)
                setPos(pair.colon)
                tok(COLON)
                ws(VTAB)
                expr(pair.value)
            } else {
                expr0(x, depth)
            }

            if (size > 0) {
                log2sum += log2ish(size.toDouble())
                count++
            }
            prevLine = line
        }

        if (mode and COMMA_TERM != 0 && next.isValid && pos.line < next.line) {
            optTok(COMMA)
            if (w == IGNORE && mode and NO_INDENT == 0) ws(UNINDENT)
            ws(FORMFEED)
            return
        }
        if (w == IGNORE && mode and NO_INDENT == 0) ws(UNINDENT)
    }

    private fun parameters(fields: GoFieldList, pmode: Int) {
        val open = if (pmode == FUNC_PARAM) LPAREN else LBRACK
        val close = if (pmode == FUNC_PARAM) RPAREN else RBRACK
        setPos(fields.opening)
        tok(open)
        if (fields.list.isNotEmpty()) {
            var prevLine = lineFor(fields.opening)
            var w = INDENT
            for ((i, par) in fields.list.withIndex()) {
                val parLineBeg = lineFor(par.pos)
                val parLineEnd = lineFor(par.end)
                val needsLinebreak = 0 < prevLine && prevLine < parLineBeg
                if (i > 0) {
                    if (!needsLinebreak) setPos(par.pos)
                    tok(COMMA)
                }
                if (needsLinebreak && linebreak(parLineBeg, 0, w, true) > 0) {
                    w = IGNORE
                } else if (i > 0) {
                    ws(BLANK)
                }
                if (par.names.isNotEmpty()) {
                    identList(par.names, w == INDENT)
                    ws(BLANK)
                }
                expr(par.type)
                prevLine = parLineEnd
            }
            val closing = lineFor(fields.closing)
            if (0 < prevLine && prevLine < closing) {
                optTok(COMMA)
                linebreak(closing, 0, IGNORE, true)
            } else if (pmode == TYPE_TPARAM && fields.numFields() == 1 && combinesWithName(fields.list[0].type)) {
                optTok(COMMA)
            }
            if (w == IGNORE) ws(UNINDENT)
        }
        setPos(fields.closing)
        tok(close)
    }

    private fun combinesWithName(x: GoExpr): Boolean = when (x) {
        is GoStarExpr -> !isTypeElem(x.x)
        is GoBinaryExpr -> combinesWithName(x.x) && !isTypeElem(x.y)
        is GoParenExpr -> !isTypeElem(x.x)
        else -> false
    }

    private fun isTypeElem(x: GoExpr): Boolean = when (x) {
        is GoArrayType, is GoStructType, is GoFuncType, is GoInterfaceType, is GoMapType, is GoChanType -> true
        is GoUnaryExpr -> x.op == TILDE
        is GoBinaryExpr -> isTypeElem(x.x) || isTypeElem(x.y)
        is GoParenExpr -> isTypeElem(x.x)
        else -> false
    }

    private fun signature(sig: GoFuncType) {
        sig.typeParams?.let { parameters(it, FUNC_TPARAM) }
        parameters(sig.params, FUNC_PARAM)
        val res = sig.results ?: return
        val n = res.numFields()
        if (n > 0 || res.opening != NO_POS) {
            ws(BLANK)
            if (n == 1 && res.list[0].names.isEmpty() && res.opening == NO_POS) {
                expr(res.list[0].type)
                return
            }
            parameters(res, FUNC_PARAM)
        }
    }

    private fun identListSize(list: List<GoIdent>, maxSize: Int): Int {
        var size = 0
        for ((i, x) in list.withIndex()) {
            if (i > 0) size += 2
            size += src.text.subSequence(x.pos, x.end).let { Character.codePointCount(it, 0, it.length) }
            if (size >= maxSize) break
        }
        return size
    }

    private fun isOneLineFieldList(list: List<GoField>): Boolean {
        if (list.size != 1) return false
        val f = list[0]
        if (f.tag != null || f.hasLineComment) return false
        val maxSize = 30
        var namesSize = identListSize(f.names, maxSize)
        if (namesSize > 0) namesSize = 1
        val typeSize = nodeSize(f.type, maxSize)
        return namesSize + typeSize <= maxSize
    }

    private fun fieldList(fields: GoFieldList, isStruct: Boolean) {
        val lbrace = fields.opening
        val list = fields.list
        val rbrace = fields.closing
        val hasComments = commentBefore(rbrace)
        val srcIsOneLine = lbrace != NO_POS && rbrace != NO_POS && lineFor(lbrace) == lineFor(rbrace)

        if (!hasComments && srcIsOneLine) {
            if (list.isEmpty()) {
                setPos(lbrace)
                tok(LBRACE)
                setPos(rbrace)
                tok(RBRACE)
                return
            } else if (isOneLineFieldList(list)) {
                setPos(lbrace)
                tok(LBRACE)
                ws(BLANK)
                val f = list[0]
                if (isStruct) {
                    for ((i, x) in f.names.withIndex()) {
                        if (i > 0) {
                            tok(COMMA)
                            ws(BLANK)
                        }
                        expr(x)
                    }
                    if (f.names.isNotEmpty()) ws(BLANK)
                    expr(f.type)
                } else {
                    if (f.names.isNotEmpty()) {
                        expr(f.names[0])
                        signature(f.type as GoFuncType)
                    } else {
                        expr(f.type)
                    }
                }
                ws(BLANK)
                setPos(rbrace)
                tok(RBRACE)
                return
            }
        }

        ws(BLANK)
        setPos(lbrace)
        tok(LBRACE)
        ws(INDENT)
        if (hasComments || list.isNotEmpty()) ws(FORMFEED)

        if (isStruct) {
            val sep = if (list.size == 1) BLANK else VTAB
            val line = IntArray(1)
            for ((i, f) in list.withIndex()) {
                if (i > 0) linebreak(lineFor(f.pos), 1, IGNORE, linesFrom(line) > 0)
                var extraTabs: Int
                recordLine(line)
                if (f.names.isNotEmpty()) {
                    identList(f.names, false)
                    ws(sep)
                    expr(f.type)
                    extraTabs = 1
                } else {
                    expr(f.type)
                    extraTabs = 2
                }
                if (f.tag != null) {
                    if (f.names.isNotEmpty() && sep == VTAB) ws(sep)
                    ws(sep)
                    expr(f.tag)
                    extraTabs = 0
                }
                if (f.hasLineComment) {
                    while (extraTabs > 0) {
                        ws(sep)
                        extraTabs--
                    }
                }
            }
        } else {
            val line = IntArray(1)
            for ((i, f) in list.withIndex()) {
                if (i > 0) linebreak(lineFor(f.pos), 1, IGNORE, linesFrom(line) > 0)
                recordLine(line)
                if (f.names.isNotEmpty()) {
                    expr(f.names[0])
                    signature(f.type as GoFuncType)
                } else {
                    expr(f.type)
                }
            }
        }
        ws(UNINDENT, FORMFEED)
        setPos(rbrace)
        tok(RBRACE)
    }

    // ---------------------------------------------------------------------------------------------
    // nodes.go: expressions

    private fun walkBinary(e: GoBinaryExpr): IntArray {
        // [has4, has5, maxProblem]
        var has4 = false
        var has5 = false
        var maxProblem = 0
        when (precedence(e.op)) {
            4 -> has4 = true
            5 -> has5 = true
        }
        val l = e.x
        if (l is GoBinaryExpr && precedence(l.op) >= precedence(e.op)) {
            val r = walkBinary(l)
            has4 = has4 || r[0] == 1
            has5 = has5 || r[1] == 1
            maxProblem = max(maxProblem, r[2])
        }
        when (val r = e.y) {
            is GoBinaryExpr -> if (precedence(r.op) > precedence(e.op)) {
                val w = walkBinary(r)
                has4 = has4 || w[0] == 1
                has5 = has5 || w[1] == 1
                maxProblem = max(maxProblem, w[2])
            }
            is GoStarExpr -> if (e.op == QUO) maxProblem = 5
            is GoUnaryExpr -> when (opText(e.op) + opText(r.op)) {
                "/*", "&&", "&^" -> maxProblem = 5
                "++", "--" -> maxProblem = max(maxProblem, 4)
            }
            else -> {}
        }
        return intArrayOf(if (has4) 1 else 0, if (has5) 1 else 0, maxProblem)
    }

    private fun cutoff(e: GoBinaryExpr, depth: Int): Int {
        val w = walkBinary(e)
        if (w[2] > 0) return w[2] + 1
        if (w[0] == 1 && w[1] == 1) return if (depth == 1) 5 else 4
        return if (depth == 1) 6 else 4
    }

    private fun diffPrec(x: GoExpr, prec: Int): Int =
        if (x !is GoBinaryExpr || prec != precedence(x.op)) 1 else 0

    private fun reduceDepth(depth: Int): Int = max(depth - 1, 1)

    private fun binaryExpr(x: GoBinaryExpr, cutoff: Int, depth: Int) {
        val prec = precedence(x.op)
        var printBlank = prec < cutoff
        var w = INDENT
        expr1(x.x, prec, depth + diffPrec(x.x, prec))
        if (printBlank) ws(BLANK)
        val xline = pos.line
        val yline = lineFor(x.y.pos)
        setPos(x.opPos)
        tok(x.op)
        if (xline != yline && xline > 0 && yline > 0) {
            if (linebreak(yline, 1, w, true) > 0) {
                w = IGNORE
                printBlank = false
            }
        }
        if (printBlank) ws(BLANK)
        expr1(x.y, prec + 1, depth + 1)
        if (w == IGNORE) ws(UNINDENT)
    }

    private fun expr1(x: GoExpr, prec1: Int, depth: Int) {
        setPos(x.pos)
        when (x) {
            is GoIdent -> ident(x)
            is GoBinaryExpr -> binaryExpr(x, cutoff(x, max(depth, 1)), max(depth, 1))
            is GoKeyValueExpr -> {
                expr(x.key)
                setPos(x.colon)
                tok(COLON)
                ws(BLANK)
                expr(x.value)
            }
            is GoStarExpr -> {
                tok(MUL)
                expr(x.x)
            }
            is GoUnaryExpr -> {
                tok(x.op)
                expr1(x.x, UNARY_PREC, depth)
            }
            is GoBasicLit -> basicLit(x)
            is GoFuncLit -> {
                setPos(x.type.pos)
                tok(FUNC)
                val startCol = out.column - 4
                signature(x.type)
                funcBody(distanceFrom(x.type.pos, startCol), BLANK, x.body)
            }
            is GoParenExpr -> {
                tok(LPAREN)
                if (x.x is GoParenExpr) {
                    expr0(x.x, depth) // go/printer drops the outer parentheses; they are kept here
                } else {
                    expr0(x.x, reduceDepth(depth))
                }
                setPos(x.rparen)
                tok(RPAREN)
            }
            is GoSelectorExpr -> selectorExpr(x, depth, false)
            is GoTypeAssertExpr -> {
                expr1(x.x, HIGHEST_PREC, depth)
                tok(PERIOD)
                setPos(x.lparen)
                tok(LPAREN)
                if (x.type != null) expr(x.type) else tok(TYPE_)
                setPos(x.rparen)
                tok(RPAREN)
            }
            is GoIndexExpr -> {
                expr1(x.x, HIGHEST_PREC, 1)
                setPos(x.lbrack)
                tok(LBRACK)
                expr0(x.index, depth + 1)
                setPos(x.rbrack)
                tok(RBRACK)
            }
            is GoIndexListExpr -> {
                expr1(x.x, HIGHEST_PREC, 1)
                setPos(x.lbrack)
                tok(LBRACK)
                exprList(x.lbrack, x.indices, depth + 1, COMMA_TERM, x.rbrack)
                setPos(x.rbrack)
                tok(RBRACK)
            }
            is GoSliceExpr -> sliceExpr(x, depth)
            is GoCallExpr -> {
                val d = if (x.args.size > 1) depth + 1 else depth
                val wasIndented = possibleSelectorExpr(x.fn, HIGHEST_PREC, d)
                setPos(x.lparen)
                tok(LPAREN)
                if (x.ellipsis != NO_POS) {
                    exprList(x.lparen, x.args, d, 0, x.ellipsis)
                    setPos(x.ellipsis)
                    tok(ELLIPSIS)
                    if (lineFor(x.ellipsis) < lineFor(x.rparen)) {
                        optTok(COMMA)
                        ws(FORMFEED)
                    }
                } else {
                    exprList(x.lparen, x.args, d, COMMA_TERM, x.rparen)
                }
                setPos(x.rparen)
                tok(RPAREN)
                if (wasIndented) ws(UNINDENT)
            }
            is GoCompositeLit -> {
                if (x.type != null) expr1(x.type, HIGHEST_PREC, depth)
                level++
                setPos(x.lbrace)
                tok(LBRACE)
                exprList(x.lbrace, x.elts, 1, COMMA_TERM, x.rbrace)
                var m = NO_EXTRA_LINEBREAK
                if (x.elts.isNotEmpty()) m = m or NO_EXTRA_BLANK
                ws(INDENT, UNINDENT)
                toggle(m)
                setPos(x.rbrace)
                tok(RBRACE)
                toggle(m)
                level--
            }
            is GoEllipsis -> {
                tok(ELLIPSIS)
                x.elt?.let { expr(it) }
            }
            is GoArrayType -> {
                tok(LBRACK)
                x.len?.let { expr(it) }
                tok(RBRACK)
                expr(x.elt)
            }
            is GoStructType -> {
                tok(STRUCT)
                fieldList(x.fields, true)
            }
            is GoFuncType -> {
                if (x.funcPos == NO_POS) {
                    // a method signature sized as a function type (isOneLineFieldList): "func" is
                    // part of the size but not of the source
                    if (!sizeMode) throw GoPrinterMismatch("function type without 'func'")
                    updatePrevOpen()
                    lastTok = FUNC
                    flush(pos.copy(), FUNC, x.pos)
                    writeString(pos.copy(), "func", -1)
                    impliedSemi = false
                } else {
                    tok(FUNC)
                }
                signature(x)
            }
            is GoInterfaceType -> {
                tok(INTERFACE)
                fieldList(x.methods, false)
            }
            is GoMapType -> {
                tok(MAP)
                tok(LBRACK)
                expr(x.key)
                tok(RBRACK)
                expr(x.value)
            }
            is GoChanType -> {
                when (x.dir) {
                    ChanDir.BOTH -> tok(CHAN)
                    ChanDir.RECV -> {
                        tok(ARROW)
                        tok(CHAN)
                    }
                    ChanDir.SEND -> {
                        tok(CHAN)
                        setPos(x.arrow)
                        tok(ARROW)
                    }
                }
                ws(BLANK)
                expr(x.value)
            }
        }
    }

    private fun sliceExpr(x: GoSliceExpr, depth: Int) {
        expr1(x.x, HIGHEST_PREC, 1)
        setPos(x.lbrack)
        tok(LBRACK)
        val indices = if (x.slice3) listOf(x.low, x.high, x.max) else listOf(x.low, x.high)
        var needsBlanks = false
        if (depth <= 1) {
            var indexCount = 0
            var hasBinaries = false
            for (i in indices) {
                if (i != null) {
                    indexCount++
                    if (i is GoBinaryExpr) hasBinaries = true
                }
            }
            if (indexCount > 1 && hasBinaries) needsBlanks = true
        }
        for ((i, e) in indices.withIndex()) {
            if (i > 0) {
                if (indices[i - 1] != null && needsBlanks) ws(BLANK)
                tok(COLON)
                if (e != null && needsBlanks) ws(BLANK)
            }
            if (e != null) expr0(e, depth + 1)
        }
        setPos(x.rbrack)
        tok(RBRACK)
    }

    private fun possibleSelectorExpr(x: GoExpr, prec1: Int, depth: Int): Boolean {
        if (x is GoSelectorExpr) return selectorExpr(x, depth, true)
        expr1(x, prec1, depth)
        return false
    }

    private fun selectorExpr(x: GoSelectorExpr, depth: Int, isMethod: Boolean): Boolean {
        expr1(x.x, HIGHEST_PREC, depth)
        tok(PERIOD)
        val line = lineFor(x.sel.pos)
        if (pos.isValid && pos.line < line) {
            ws(INDENT, NEWLINE)
            setPos(x.sel.pos)
            ident(x.sel)
            if (!isMethod) ws(UNINDENT)
            return true
        }
        setPos(x.sel.pos)
        ident(x.sel)
        return false
    }

    private fun expr0(x: GoExpr, depth: Int) = expr1(x, LOWEST_PREC, depth)

    private fun expr(x: GoExpr) = expr1(x, LOWEST_PREC, 1)

    // ---------------------------------------------------------------------------------------------
    // nodes.go: statements

    private fun stmtList(list: List<GoStmt>, nindent: Int, nextIsRBrace: Boolean) {
        if (nindent > 0) ws(INDENT)
        val line = IntArray(1)
        var i = 0
        for (s in list) {
            if (s is GoEmptyStmt) continue
            if (output.isNotEmpty()) {
                linebreak(lineFor(s.pos), 1, IGNORE, i == 0 || nindent == 0 || linesFrom(line) > 0)
            }
            recordLine(line)
            stmt(s, nextIsRBrace && i == list.size - 1)
            var t: GoStmt = s
            while (t is GoLabeledStmt) {
                line[0]++
                t = t.stmt
            }
            i++
        }
        if (nindent > 0) ws(UNINDENT)
    }

    private fun block(b: GoBlockStmt, nindent: Int) {
        setPos(b.lbrace)
        tok(LBRACE)
        stmtList(b.list, nindent, true)
        linebreak(lineFor(b.rbrace), 1, IGNORE, true)
        setPos(b.rbrace)
        tok(RBRACE)
    }

    private fun controlClause(isForStmt: Boolean, init: GoStmt?, cond: GoExpr?, post: GoStmt?) {
        ws(BLANK)
        var needsBlank = false
        if (init == null && post == null) {
            if (cond != null) {
                expr(cond)
                needsBlank = true
            }
        } else {
            if (init != null) stmt(init, false)
            tok(SEMICOLON)
            ws(BLANK)
            if (cond != null) {
                expr(cond)
                needsBlank = true
            }
            if (isForStmt) {
                tok(SEMICOLON)
                ws(BLANK)
                needsBlank = false
                if (post != null) {
                    stmt(post, false)
                    needsBlank = true
                }
            }
        }
        if (needsBlank) ws(BLANK)
    }

    private fun isCompositeLitLike(x: GoExpr): Boolean {
        val s = stripParensAlways(x)
        return s is GoCompositeLit || s is GoUnaryExpr && s.op == AND && stripParensAlways(s.x) is GoCompositeLit
    }

    private fun stripParensAlways(x: GoExpr): GoExpr = if (x is GoParenExpr) stripParensAlways(x.x) else x

    private fun indentList(list: List<GoExpr>): Boolean {
        if (list.size >= 2) {
            val b = lineFor(list[0].pos)
            val e = lineFor(list.last().end)
            if (b in 1 until e) {
                var n = 0
                var line = b
                for (x in list) {
                    val xb = lineFor(x.pos)
                    val xe = lineFor(x.end)
                    if (line < xb) return true
                    if (xb < xe && !isCompositeLitLike(x)) n++
                    line = xe
                }
                return n > 1
            }
        }
        return false
    }

    private fun stmt(s: GoStmt, nextIsRBrace: Boolean) {
        setPos(s.pos)
        when (s) {
            is GoDeclStmt -> genDecl(s.decl)
            is GoEmptyStmt -> {}
            is GoLabeledStmt -> {
                ws(UNINDENT)
                expr(s.label)
                setPos(s.colon)
                tok(COLON)
                ws(INDENT)
                val inner = s.stmt
                if (inner is GoEmptyStmt) {
                    if (!nextIsRBrace) {
                        ws(NEWLINE)
                        setPos(inner.pos)
                        optTok(SEMICOLON)
                        return
                    }
                } else {
                    linebreak(lineFor(inner.pos), 1, IGNORE, true)
                }
                stmt(inner, nextIsRBrace)
            }
            is GoExprStmt -> expr0(s.x, 1)
            is GoSendStmt -> {
                expr0(s.chan, 1)
                ws(BLANK)
                setPos(s.arrow)
                tok(ARROW)
                ws(BLANK)
                expr0(s.value, 1)
            }
            is GoIncDecStmt -> {
                expr0(s.x, 2)
                setPos(s.tokPos)
                tok(s.tok)
            }
            is GoAssignStmt -> {
                val depth = if (s.lhs.size > 1 && s.rhs.size > 1) 2 else 1
                exprList(s.pos, s.lhs, depth, 0, s.tokPos)
                ws(BLANK)
                setPos(s.tokPos)
                tok(s.tok)
                ws(BLANK)
                exprList(s.tokPos, s.rhs, depth, 0, NO_POS)
            }
            is GoGoStmt -> {
                tok(GO)
                ws(BLANK)
                expr(s.call)
            }
            is GoDeferStmt -> {
                tok(DEFER)
                ws(BLANK)
                expr(s.call)
            }
            is GoReturnStmt -> {
                tok(RETURN)
                if (s.results.isNotEmpty()) {
                    ws(BLANK)
                    if (indentList(s.results)) {
                        ws(INDENT)
                        exprList(NO_POS, s.results, 1, NO_INDENT, NO_POS)
                        ws(UNINDENT)
                    } else {
                        exprList(NO_POS, s.results, 1, 0, NO_POS)
                    }
                }
            }
            is GoBranchStmt -> {
                nextToken(null).let { t -> printLeaf(t, t.type, isToken = true, impliedSemiAfter = t.type in IMPLIED_SEMI_TOKENS) }
                if (s.label != null) {
                    ws(BLANK)
                    expr(s.label)
                }
            }
            is GoBlockStmt -> block(s, 1)
            is GoIfStmt -> {
                tok(IF)
                controlClause(false, s.init, s.cond, null)
                block(s.body, 1)
                if (s.els != null) {
                    ws(BLANK)
                    tok(ELSE)
                    ws(BLANK)
                    stmt(s.els, nextIsRBrace)
                }
            }
            is GoCaseClause -> {
                if (s.list != null) {
                    tok(CASE)
                    ws(BLANK)
                    exprList(s.pos, s.list, 1, 0, s.colon)
                } else {
                    tok(DEFAULT)
                }
                setPos(s.colon)
                tok(COLON)
                stmtList(s.body, 1, nextIsRBrace)
            }
            is GoSwitchStmt -> {
                tok(SWITCH)
                controlClause(false, s.init, s.tag, null)
                block(s.body, 0)
            }
            is GoTypeSwitchStmt -> {
                tok(SWITCH)
                if (s.init != null) {
                    ws(BLANK)
                    stmt(s.init, false)
                    tok(SEMICOLON)
                }
                ws(BLANK)
                stmt(s.assign, false)
                ws(BLANK)
                block(s.body, 0)
            }
            is GoCommClause -> {
                if (s.comm != null) {
                    tok(CASE)
                    ws(BLANK)
                    stmt(s.comm, false)
                } else {
                    tok(DEFAULT)
                }
                setPos(s.colon)
                tok(COLON)
                stmtList(s.body, 1, nextIsRBrace)
            }
            is GoSelectStmt -> {
                tok(SELECT)
                ws(BLANK)
                val body = s.body
                if (body.list.isEmpty() && !commentBefore(body.rbrace)) {
                    setPos(body.lbrace)
                    tok(LBRACE)
                    setPos(body.rbrace)
                    tok(RBRACE)
                } else {
                    block(body, 0)
                }
            }
            is GoForStmt -> {
                tok(FOR)
                controlClause(true, s.init, s.cond, s.post)
                block(s.body, 1)
            }
            is GoRangeStmt -> {
                tok(FOR)
                ws(BLANK)
                if (s.key != null) {
                    expr(s.key)
                    if (s.value != null) {
                        setPos(s.value.pos)
                        tok(COMMA)
                        ws(BLANK)
                        expr(s.value)
                    }
                    ws(BLANK)
                    setPos(s.tokPos)
                    tok(s.tok!!)
                    ws(BLANK)
                }
                tok(RANGE)
                ws(BLANK)
                expr(s.x)
                ws(BLANK)
                block(s.body, 1)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // nodes.go: declarations

    private fun keepTypeColumn(specs: List<GoSpec>): BooleanArray {
        val m = BooleanArray(specs.size)
        fun populate(from: Int, to: Int, keepType: Boolean) {
            if (keepType) for (i in from until to) m[i] = true
        }
        var i0 = -1
        var keepType = false
        for ((i, s) in specs.withIndex()) {
            val t = s as GoValueSpec
            if (t.values != null) {
                if (i0 < 0) {
                    i0 = i
                    keepType = false
                }
            } else if (i0 >= 0) {
                populate(i0, i, keepType)
                i0 = -1
            }
            if (t.type != null) keepType = true
        }
        if (i0 >= 0) populate(i0, specs.size, keepType)
        return m
    }

    private fun valueSpec(s: GoValueSpec, keepType: Boolean) {
        identList(s.names, false)
        var extraTabs = 3
        if (s.type != null || keepType) {
            ws(VTAB)
            extraTabs--
        }
        s.type?.let { expr(it) }
        if (s.values != null) {
            ws(VTAB)
            tok(ASSIGN)
            ws(BLANK)
            exprList(NO_POS, s.values, 1, 0, NO_POS)
            extraTabs--
        }
        if (s.hasLineComment) {
            while (extraTabs > 0) {
                ws(VTAB)
                extraTabs--
            }
        }
    }

    private fun spec(spec: GoSpec, n: Int, doIndent: Boolean) {
        when (spec) {
            is GoImportSpec -> {
                if (spec.name != null) {
                    expr(spec.name)
                    ws(BLANK)
                }
                expr(spec.path)
            }
            is GoValueSpec -> {
                identList(spec.names, doIndent)
                if (spec.type != null) {
                    ws(BLANK)
                    expr(spec.type)
                }
                if (spec.values != null) {
                    ws(BLANK)
                    tok(ASSIGN)
                    ws(BLANK)
                    exprList(NO_POS, spec.values, 1, 0, NO_POS)
                }
            }
            is GoTypeSpec -> {
                expr(spec.name)
                spec.typeParams?.let { parameters(it, TYPE_TPARAM) }
                if (n == 1) ws(BLANK) else ws(VTAB)
                if (spec.assign != NO_POS) {
                    tok(ASSIGN)
                    ws(BLANK)
                }
                expr(spec.type)
            }
        }
    }

    private fun genDecl(d: GoGenDecl) {
        setPos(d.pos)
        tok(d.tok)
        ws(BLANK)
        if (d.lparen != NO_POS || d.specs.size != 1) {
            setPos(d.lparen)
            tok(LPAREN)
            val n = d.specs.size
            if (n > 0) {
                ws(INDENT, FORMFEED)
                val line = IntArray(1)
                if (n > 1 && (d.tok == CONST || d.tok == VAR)) {
                    val keepType = keepTypeColumn(d.specs)
                    for ((i, s) in d.specs.withIndex()) {
                        if (i > 0) linebreak(lineFor(s.pos), 1, IGNORE, linesFrom(line) > 0)
                        recordLine(line)
                        valueSpec(s as GoValueSpec, keepType[i])
                    }
                } else {
                    for ((i, s) in d.specs.withIndex()) {
                        if (i > 0) linebreak(lineFor(s.pos), 1, IGNORE, linesFrom(line) > 0)
                        recordLine(line)
                        spec(s, n, false)
                    }
                }
                ws(UNINDENT, FORMFEED)
            }
            setPos(d.rparen)
            tok(RPAREN)
        } else if (d.specs.isNotEmpty()) {
            spec(d.specs[0], 1, true)
        }
    }

    private fun nodeSize(n: GoNode, maxSize: Int): Int {
        nodeSizes[n]?.let { return it }
        var size = maxSize + 1
        nodeSizes[n] = size
        val sub = GoPrinter(src, true, nodeSizes, src.tokenIndexAt(n.pos))
        try {
            sub.printNode(n)
        } catch (_: GoPrinterMismatch) {
            return size
        }
        // what the trimmer would make of the output, without running it: tokens are escaped and
        // everything unescaped is whitespace, so a single line is the output without its escapes
        // and without the trailing blanks after the last token
        val out = sub.output
        var hasNewline = false
        var escaped = false
        for (i in 0 until out.length) {
            val ch = out[i]
            // a formfeed becomes a line break only outside escapes (inside it is literal text)
            if (ch == ESC) escaped = !escaped else if (ch == '\n' || ch == FORMFEED && !escaped) {
                hasNewline = true
                break
            }
        }
        var end = out.length
        while (end > 0 && (out[end - 1] == ' ' || out[end - 1] == '\t' || out[end - 1] == VTAB)) end--
        val count = utf8Length(out, 0, end)
        if (count <= maxSize && !hasNewline) {
            size = count
            nodeSizes[n] = size
        }
        return size
    }

    private fun bodySize(b: GoBlockStmt, maxSize: Int): Int {
        if (lineFor(b.lbrace) != lineFor(b.rbrace)) return maxSize + 1
        if (b.list.size > 5) return maxSize + 1
        var bodySize = commentSizeBefore(b.rbrace)
        for ((i, s) in b.list.withIndex()) {
            if (bodySize > maxSize) break
            if (i > 0) bodySize += 2
            bodySize += nodeSize(s, maxSize)
        }
        return bodySize
    }

    private fun funcBody(headerSize: Int, sep: Char, b: GoBlockStmt?) {
        if (b == null) return
        val savedLevel = level
        level = 0
        try {
            val maxSize = 100
            if (headerSize + bodySize(b, maxSize) <= maxSize) {
                ws(sep)
                setPos(b.lbrace)
                tok(LBRACE)
                if (b.list.isNotEmpty()) {
                    ws(BLANK)
                    for ((i, s) in b.list.withIndex()) {
                        if (i > 0) {
                            tok(SEMICOLON)
                            ws(BLANK)
                        }
                        stmt(s, i == b.list.size - 1)
                    }
                    ws(BLANK)
                }
                toggle(NO_EXTRA_LINEBREAK)
                setPos(b.rbrace)
                tok(RBRACE)
                toggle(NO_EXTRA_LINEBREAK)
                return
            }
            if (sep != IGNORE) ws(BLANK)
            block(b, 1)
        } finally {
            level = savedLevel
        }
    }

    private fun distanceFrom(startPos: Int, startOutCol: Int): Int {
        if (startPos != NO_POS && pos.isValid && lineFor(startPos) == pos.line) return out.column - startOutCol
        return INFINITY
    }

    private fun funcDecl(d: GoFuncDecl) {
        setPos(d.pos)
        tok(FUNC)
        ws(BLANK)
        val startCol = out.column - 5
        if (d.recv != null) {
            parameters(d.recv, FUNC_PARAM)
            ws(BLANK)
        }
        expr(d.name)
        signature(d.type)
        funcBody(distanceFrom(d.pos, startCol), VTAB, d.body)
    }

    private fun decl(d: GoDecl) {
        when (d) {
            is GoGenDecl -> genDecl(d)
            is GoFuncDecl -> funcDecl(d)
        }
    }

    private fun numLines(n: GoNode): Int = lineFor(n.end) - lineFor(n.pos) + 1

    private fun declList(list: List<GoDecl>) {
        var tok: IElementType? = null
        for (d in list) {
            val prev = tok
            tok = if (d is GoGenDecl) d.tok else FUNC
            if (output.isNotEmpty()) {
                val min = if (prev != tok || hasDoc(d.pos)) 2 else 1
                linebreak(lineFor(d.pos), min, IGNORE, tok == FUNC && numLines(d) > 1)
            }
            decl(d)
        }
    }

    /** `getDoc(d) != nil`: go/parser's lead comment rule for the token at [offset]. */
    private fun hasDoc(offset: Int): Boolean {
        val leafIndex = src.leafIndexAt(offset) ?: return false
        val leaves = src.leaves
        var i = leafIndex - 1
        while (i >= 0 && leaves[i].isComment) i--
        val first = i + 1
        if (first == leafIndex) return false
        val prevLine = if (i >= 0) src.lineFor(leaves[i].end - 1) else 0
        var k = first
        // a first group on the same line as the previous token is a line comment
        if (i >= 0 && src.lineFor(leaves[k].offset) == prevLine) {
            var endLine = src.lineFor(leaves[k].end - 1)
            k++
            while (k < leafIndex && src.lineFor(leaves[k].offset) <= endLine) {
                endLine = src.lineFor(leaves[k].end - 1)
                k++
            }
        }
        if (k >= leafIndex) return false
        // successor groups (n = 1): the last one is the lead comment if it ends on the line before
        var endLine = -1
        while (k < leafIndex) {
            endLine = src.lineFor(leaves[k].end - 1)
            k++
            while (k < leafIndex && src.lineFor(leaves[k].offset) <= endLine + 1) {
                endLine = src.lineFor(leaves[k].end - 1)
                k++
            }
        }
        return endLine + 1 == src.lineFor(offset)
    }

    private fun file(f: GoFileNode) {
        setPos(f.pos)
        tok(PACKAGE)
        ws(BLANK)
        expr(f.name)
        declList(f.decls)
        ws(NEWLINE)
    }

    companion object {
        /**
         * Brackets of escaped text (tabwriter.Escape). go/printer uses U+FFFF; U+0000 is used here
         * because it cannot occur in Go source either (go/scanner rejects NUL everywhere) and keeps
         * the printer and tabwriter buffers Latin-1, which makes appending to them much cheaper.
         */
        const val ESC = '\u0000'
        const val VTAB = '\u000B'
        const val FORMFEED = '\u000C'
        /** A whitespace-buffer entry that writes nothing; never reaches the output (it would show as text there). */
        private const val IGNORE = '\u0001'
        private const val BLANK = ' '
        private const val NEWLINE = '\n'
        private const val INDENT = '>'
        private const val UNINDENT = '<'

        private const val NO_EXTRA_BLANK = 1
        private const val NO_EXTRA_LINEBREAK = 2

        private const val COMMA_TERM = 1
        private const val NO_INDENT = 2

        private const val FUNC_PARAM = 0
        private const val FUNC_TPARAM = 1
        private const val TYPE_TPARAM = 2

        private const val MAX_NEWLINES = 2
        private const val INFINITY = 1 shl 30
        private const val SMALL_SIZE = 40

        private const val LOWEST_PREC = 0
        private const val UNARY_PREC = 6
        private const val HIGHEST_PREC = 7

        private val EOF = Any()

        private val IMPLIED_SEMI_TOKENS = setOf(BREAK, CONTINUE, FALLTHROUGH, RETURN, INC, DEC, RPAREN, RBRACK, RBRACE)

        private fun nlimit(n: Int): Int = min(n, MAX_NEWLINES)

        fun precedence(op: IElementType): Int = when (op) {
            LOR -> 1
            LAND -> 2
            EQL, NEQ, LSS, LEQ, GTR, GEQ -> 3
            ADD, SUB, OR, XOR -> 4
            MUL, QUO, REM, SHL, SHR, AND, AND_NOT -> 5
            else -> 0
        }

        private fun opText(op: IElementType): String = op.toString()

        private fun mayCombine(prev: Any?, next: Char): Boolean = when (prev) {
            INT -> next == '.'
            ADD -> next == '+'
            SUB -> next == '-'
            QUO -> next == '*'
            LSS -> next == '-' || next == '<'
            AND -> next == '&' || next == '^'
            else -> false
        }

        private fun log2ish(x: Double): Double {
            // math.Frexp: x = f * 2^e, f in [0.5, 1)
            val e = Math.getExponent(x) + 1
            val f = x / Math.scalb(1.0, e)
            return e + 2 * (f - 1)
        }

        private fun exp2ish(x: Double): Double {
            val n = Math.floor(x)
            val f = x - n
            return Math.scalb(1 + f, n.toInt())
        }

        fun utf8Length(s: CharSequence, from: Int = 0, to: Int = s.length): Int {
            var n = 0
            var i = from
            while (i < to) {
                val c = s[i]
                when {
                    c == ESC -> {}
                    c.code < 0x80 -> n += 1
                    c.code < 0x800 -> n += 2
                    Character.isHighSurrogate(c) && i + 1 < to && Character.isLowSurrogate(s[i + 1]) -> {
                        n += 4
                        i++
                    }
                    else -> n += 3
                }
                i++
            }
            return n
        }
    }
}
