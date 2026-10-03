package io.github.golangsupport.ide.rules.builtin.staticcheck

import io.github.golangsupport.semantic.types.GoConstant

/**
 * The parse errors of `text/template` (`template.New("").Parse(text)`, Go 1.27 `text/template/parse`): a re-implementation of its
 * lexer and parser that only tracks what decides the first error (delimiters, tokens, declared variables, the builtin functions).
 * [parseError] gives the message `Parse` returns (`template: :1: unexpected "}" in operand`), or null when the text parses.
 * Numbers are taken as the lexer scans them (no `illegal number syntax`), and template redefinitions are not checked.
 */
internal object GoTemplateSyntax {

    fun parseError(text: String): String? = try {
        Parser(Lexer(text)).parse()
        null
    } catch (e: TemplateError) {
        e.message
    }

    private class TemplateError(message: String) : RuntimeException(message, null, false, false)

    private enum class T { ERROR, BOOL, CHAR, CHAR_CONSTANT, COMMENT, COMPLEX, ASSIGN, DECLARE, EOF, FIELD, IDENTIFIER, LEFT_DELIM, LEFT_PAREN,
        NUMBER, PIPE, RAW_STRING, RIGHT_DELIM, RIGHT_PAREN, SPACE, STRING, TEXT, VARIABLE,
        KEYWORD, BLOCK, BREAK, CONTINUE, DOT, DEFINE, ELSE, END, IF, NIL, RANGE, TEMPLATE, WITH }

    private class Item(val type: T, val value: String, val line: Int) {
        /** `item.String()`. */
        override fun toString(): String = when {
            type == T.EOF -> "EOF"
            type == T.ERROR -> value
            type > T.KEYWORD -> "<$value>"
            value.toByteArray(Charsets.UTF_8).size > 10 -> {
                val cps = value.codePoints().limit(10).toArray()
                GoStaticcheckPsi.quote(String(cps, 0, cps.size)) + "..."
            }
            else -> GoStaticcheckPsi.quote(value)
        }
    }

    private val KEYWORDS = mapOf(
        "." to T.DOT, "block" to T.BLOCK, "break" to T.BREAK, "continue" to T.CONTINUE, "define" to T.DEFINE, "else" to T.ELSE,
        "end" to T.END, "if" to T.IF, "range" to T.RANGE, "nil" to T.NIL, "template" to T.TEMPLATE, "with" to T.WITH,
    )

    private val BUILTINS = setOf("and", "call", "html", "index", "slice", "js", "len", "not", "or", "print", "printf", "println", "urlquery",
        "eq", "ge", "gt", "le", "lt", "ne")

    private const val EOF_RUNE = -1
    private const val LEFT = "{{"
    private const val RIGHT = "}}"

    private enum class S { TEXT, LEFT_DELIM, COMMENT, RIGHT_DELIM, INSIDE_ACTION, SPACE, IDENTIFIER, FIELD, VARIABLE, CHAR, NUMBER, QUOTE, RAW_QUOTE }

    private class Lexer(private var input: String) {
        private var pos = 0
        private var start = 0
        private var atEOF = false
        private var parenDepth = 0
        private var line = 1
        private var startLine = 1
        private var insideAction = false
        private lateinit var item: Item

        fun nextItem(): Item {
            item = Item(T.EOF, "EOF", startLine)
            var state: S? = if (insideAction) S.INSIDE_ACTION else S.TEXT
            while (state != null) state = step(state)
            return item
        }

        private fun next(): Int {
            if (pos >= input.length) {
                atEOF = true
                return EOF_RUNE
            }
            val r = input.codePointAt(pos)
            pos += Character.charCount(r)
            if (r == '\n'.code) line++
            return r
        }

        private fun peek(): Int = next().also { backup() }

        private fun backup() {
            if (!atEOF && pos > 0) {
                val r = input.codePointBefore(pos)
                pos -= Character.charCount(r)
                if (r == '\n'.code) line--
            }
        }

        private fun thisItem(t: T): Item = Item(t, input.substring(start, pos), startLine).also {
            start = pos
            startLine = line
        }

        private fun emit(t: T): S? {
            item = thisItem(t)
            return null
        }

        private fun ignore() {
            line += newlines(start, pos)
            start = pos
            startLine = line
        }

        private fun newlines(from: Int, to: Int): Int = (from until to).count { input[it] == '\n' }

        private fun errorf(message: String): S? {
            item = Item(T.ERROR, message, startLine)
            start = 0
            pos = 0
            input = ""
            return null
        }

        private fun accept(valid: String): Boolean {
            val r = next()
            if (r >= 0 && valid.indexOf(r.toChar()) >= 0 && r < 0x10000) return true
            backup()
            return false
        }

        private fun acceptRun(valid: String) {
            while (true) {
                val r = next()
                if (r < 0 || r >= 0x10000 || valid.indexOf(r.toChar()) < 0) break
            }
            backup()
        }

        private fun hasLeftTrimMarker(at: Int): Boolean = input.length - at >= 2 && input[at] == '-' && isSpace(input[at + 1].code)
        private fun hasRightTrimMarker(at: Int): Boolean = at >= 0 && input.length - at >= 2 && isSpace(input[at].code) && input[at + 1] == '-'

        /** (delim, trimSpaces) */
        private fun atRightDelim(): Pair<Boolean, Boolean> {
            if (hasRightTrimMarker(pos) && input.startsWith(RIGHT, pos + 2)) return true to true
            if (input.startsWith(RIGHT, pos)) return true to false
            return false to false
        }

        private fun atTerminator(): Boolean {
            val r = peek()
            if (isSpace(r)) return true
            if (r == EOF_RUNE || r == '.'.code || r == ','.code || r == '|'.code || r == ':'.code || r == ')'.code || r == '('.code) return true
            return input.startsWith(RIGHT, pos)
        }

        private fun step(state: S): S? = when (state) {
            S.TEXT -> {
                val x = input.indexOf(LEFT, pos)
                if (x >= 0) {
                    if (x > pos) {
                        pos = x
                        var trim = 0
                        if (hasLeftTrimMarker(pos + LEFT.length)) trim = rightTrimLength(start, pos)
                        pos -= trim
                        line += newlines(start, pos)
                        val i = thisItem(T.TEXT)
                        pos += trim
                        ignore()
                        if (i.value.isNotEmpty()) {
                            item = i
                            null
                        } else S.LEFT_DELIM
                    } else S.LEFT_DELIM
                } else {
                    pos = input.length
                    if (pos > start) {
                        line += newlines(start, pos)
                        emit(T.TEXT)
                    } else emit(T.EOF)
                }
            }
            S.LEFT_DELIM -> {
                pos += LEFT.length
                val trimSpace = hasLeftTrimMarker(pos)
                val afterMarker = if (trimSpace) 2 else 0
                if (input.startsWith("/*", pos + afterMarker)) {
                    pos += afterMarker
                    ignore()
                    S.COMMENT
                } else {
                    val i = thisItem(T.LEFT_DELIM)
                    insideAction = true
                    pos += afterMarker
                    ignore()
                    parenDepth = 0
                    item = i
                    null
                }
            }
            S.COMMENT -> {
                pos += 2
                val x = input.indexOf("*/", pos)
                if (x < 0) errorf("unclosed comment")
                else {
                    pos = x + 2
                    val (delim, trimSpace) = atRightDelim()
                    if (!delim) errorf("comment ends before closing delimiter")
                    else {
                        line += newlines(start, pos)
                        thisItem(T.COMMENT)
                        if (trimSpace) pos += 2
                        pos += RIGHT.length
                        if (trimSpace) pos += leftTrimLength(pos)
                        ignore()
                        S.TEXT
                    }
                }
            }
            S.RIGHT_DELIM -> {
                val trimSpace = atRightDelim().second
                if (trimSpace) {
                    pos += 2
                    ignore()
                }
                pos += RIGHT.length
                val i = thisItem(T.RIGHT_DELIM)
                if (trimSpace) {
                    pos += leftTrimLength(pos)
                    ignore()
                }
                insideAction = false
                item = i
                null
            }
            S.INSIDE_ACTION -> insideAction()
            S.SPACE -> {
                var spaces = 0
                while (isSpace(peek())) {
                    next()
                    spaces++
                }
                if (hasRightTrimMarker(pos - 1) && input.startsWith(RIGHT, pos - 1 + 2)) {
                    backup()
                    if (spaces == 1) S.RIGHT_DELIM else emit(T.SPACE)
                } else emit(T.SPACE)
            }
            S.IDENTIFIER -> {
                var r: Int
                while (true) {
                    r = next()
                    if (!isAlphaNumeric(r)) break
                }
                backup()
                val word = input.substring(start, pos)
                if (!atTerminator()) errorf("bad character ${runeU(r)}")
                else {
                    val key = KEYWORDS[word]
                    when {
                        key != null && key > T.KEYWORD -> emit(key)
                        word.startsWith(".") -> emit(T.FIELD)
                        word == "true" || word == "false" -> emit(T.BOOL)
                        else -> emit(T.IDENTIFIER)
                    }
                }
            }
            S.FIELD -> fieldOrVariable(T.FIELD)
            S.VARIABLE -> if (atTerminator()) emit(T.VARIABLE) else fieldOrVariable(T.VARIABLE)
            S.CHAR -> quoted('\'', "unterminated character constant", T.CHAR_CONSTANT)
            S.QUOTE -> quoted('"', "unterminated quoted string", T.STRING)
            S.RAW_QUOTE -> {
                var result: S? = null
                var done = false
                while (!done) {
                    when (next()) {
                        EOF_RUNE -> { result = errorf("unterminated raw quoted string"); done = true }
                        '`'.code -> { result = emit(T.RAW_STRING); done = true }
                    }
                }
                result
            }
            S.NUMBER -> {
                if (!scanNumber()) errorf("bad number syntax: ${GoStaticcheckPsi.quote(input.substring(start, pos))}")
                else {
                    val sign = peek()
                    if (sign == '+'.code || sign == '-'.code) {
                        if (!scanNumber() || input[pos - 1] != 'i') errorf("bad number syntax: ${GoStaticcheckPsi.quote(input.substring(start, pos))}")
                        else emit(T.COMPLEX)
                    } else emit(T.NUMBER)
                }
            }
        }

        private fun insideAction(): S? {
            if (atRightDelim().first) return if (parenDepth == 0) S.RIGHT_DELIM else errorf("unclosed left paren")
            val r = next()
            return when {
                r == EOF_RUNE -> errorf("unclosed action")
                isSpace(r) -> { backup(); S.SPACE }
                r == '='.code -> emit(T.ASSIGN)
                r == ':'.code -> if (next() != '='.code) errorf("expected :=") else emit(T.DECLARE)
                r == '|'.code -> emit(T.PIPE)
                r == '"'.code -> S.QUOTE
                r == '`'.code -> S.RAW_QUOTE
                r == '$'.code -> S.VARIABLE
                r == '\''.code -> S.CHAR
                r == '.'.code && pos < input.length && (input[pos] < '0' || input[pos] > '9') -> S.FIELD
                r == '.'.code || r == '+'.code || r == '-'.code || r in '0'.code..'9'.code -> { backup(); S.NUMBER }
                isAlphaNumeric(r) -> { backup(); S.IDENTIFIER }
                r == '('.code -> { parenDepth++; emit(T.LEFT_PAREN) }
                r == ')'.code -> { parenDepth--; if (parenDepth < 0) errorf("unexpected right paren") else emit(T.RIGHT_PAREN) }
                r <= 0x7f && r >= 0x20 && r != 0x7f -> emit(T.CHAR)
                else -> errorf("unrecognized character in action: ${runeU(r)}")
            }
        }

        private fun fieldOrVariable(t: T): S? {
            if (atTerminator()) return if (t == T.VARIABLE) emit(T.VARIABLE) else emit(T.DOT)
            var r: Int
            while (true) {
                r = next()
                if (!isAlphaNumeric(r)) {
                    backup()
                    break
                }
            }
            if (!atTerminator()) return errorf("bad character ${runeU(r)}")
            return emit(t)
        }

        private fun quoted(quote: Char, error: String, t: T): S? {
            while (true) {
                when (next()) {
                    '\\'.code -> {
                        val e = next()
                        if (e == EOF_RUNE || e == '\n'.code) return errorf(error)
                    }
                    EOF_RUNE, '\n'.code -> return errorf(error)
                    quote.code -> return emit(t)
                }
            }
        }

        private fun scanNumber(): Boolean {
            accept("+-")
            var digits = "0123456789_"
            if (accept("0")) {
                if (accept("xX")) digits = "0123456789abcdefABCDEF_"
                else if (accept("oO")) digits = "01234567_"
                else if (accept("bB")) digits = "01_"
            }
            acceptRun(digits)
            if (accept(".")) acceptRun(digits)
            if (digits.length == 11 && accept("eE")) {
                accept("+-")
                acceptRun("0123456789_")
            }
            if (digits.length == 23 && accept("pP")) {
                accept("+-")
                acceptRun("0123456789_")
            }
            accept("i")
            if (isAlphaNumeric(peek())) {
                next()
                return false
            }
            return true
        }

        private fun rightTrimLength(from: Int, to: Int): Int {
            var i = to
            while (i > from && isSpace(input[i - 1].code)) i--
            return to - i
        }

        private fun leftTrimLength(from: Int): Int {
            var i = from
            while (i < input.length && isSpace(input[i].code)) i++
            return i - from
        }
    }

    private fun isSpace(r: Int): Boolean = r == ' '.code || r == '\t'.code || r == '\r'.code || r == '\n'.code

    private fun isAlphaNumeric(r: Int): Boolean = r == '_'.code || r >= 0 && (Character.isLetter(r) || Character.isDigit(r))

    /** Go's `%#U`. */
    private fun runeU(r: Int): String {
        val hex = "U+" + String.format("%04X", r)
        if (r < 0 || r > Character.MAX_CODE_POINT) return hex
        val s = String(Character.toChars(r))
        val printable = s == "\"" || s == "\\" || GoStaticcheckPsi.quote(s) == "\"$s\""
        return if (printable) "$hex '$s'" else hex
    }

    // ---- parser

    private enum class N { TEXT, ACTION, COMMENT, IF, RANGE, WITH, END, ELSE, BREAK, CONTINUE, TEMPLATE, BOOL, DOT, NIL, NUMBER, STRING, FIELD,
        VARIABLE, IDENTIFIER, CHAIN, PIPE }

    private class Node(val type: N, val text: String) {
        override fun toString(): String = text
    }

    private class Parser(private val lex: Lexer) {
        private val token = arrayOfNulls<Item>(3)
        private var peekCount = 0
        private val vars = arrayListOf("$")
        private var actionLine = 0
        private var rangeDepth = 0

        private fun next(): Item {
            if (peekCount > 0) peekCount-- else token[0] = lex.nextItem()
            return token[peekCount]!!
        }

        private fun backup() {
            peekCount++
        }

        private fun backup2(t1: Item) {
            token[1] = t1
            peekCount = 2
        }

        private fun backup3(t2: Item, t1: Item) {
            token[1] = t1
            token[2] = t2
            peekCount = 3
        }

        private fun peek(): Item {
            if (peekCount > 0) return token[peekCount - 1]!!
            peekCount = 1
            token[0] = lex.nextItem()
            return token[0]!!
        }

        private fun nextNonSpace(): Item {
            var t: Item
            do t = next() while (t.type == T.SPACE)
            return t
        }

        private fun peekNonSpace(): Item = nextNonSpace().also { backup() }

        private fun errorf(message: String): Nothing = throw TemplateError("template: :${token[0]?.line ?: 0}: $message")

        private fun expect(expected: T, context: String): Item = nextNonSpace().also { if (it.type != expected) unexpected(it, context) }

        private fun unexpected(token: Item, context: String): Nothing {
            if (token.type == T.ERROR) {
                var extra = ""
                if (actionLine != 0 && actionLine != token.line) {
                    extra = " in action started at :$actionLine"
                    if (token.value.endsWith(" action")) extra = extra.substring(" in action".length)
                }
                errorf("$token$extra")
            }
            errorf("unexpected $token in $context")
        }

        fun parse() {
            while (peek().type != T.EOF) {
                if (peek().type == T.LEFT_DELIM) {
                    val delim = next()
                    if (nextNonSpace().type == T.DEFINE) {
                        Parser(lex).parseDefinition()
                        continue
                    }
                    backup2(delim)
                }
                val n = textOrAction()
                if (n.type == N.END || n.type == N.ELSE) errorf("unexpected $n")
            }
        }

        private fun parseDefinition() {
            val context = "define clause"
            val name = nextNonSpace()
            if (name.type != T.STRING && name.type != T.RAW_STRING) unexpected(name, context)
            unquote(name)
            expect(T.RIGHT_DELIM, context)
            val end = itemList()
            if (end.type != N.END) errorf("unexpected $end in $context")
        }

        private fun unquote(item: Item): String {
            val v = item.value
            if (item.type == T.RAW_STRING) return v.substring(1, v.length - 1)
            return GoConstant.unescape(v.substring(1, v.length - 1)) ?: errorf("invalid syntax")
        }

        /** The node that ended the list (`{{end}}` / `{{else}}`). */
        private fun itemList(): Node {
            while (peekNonSpace().type != T.EOF) {
                val n = textOrAction()
                if (n.type == N.END || n.type == N.ELSE) return n
            }
            errorf("unexpected EOF")
        }

        private fun textOrAction(): Node {
            val token = nextNonSpace()
            return when (token.type) {
                T.TEXT -> Node(N.TEXT, token.value)
                T.LEFT_DELIM -> {
                    actionLine = token.line
                    try {
                        action()
                    } finally {
                        actionLine = 0
                    }
                }
                T.COMMENT -> Node(N.COMMENT, token.value)
                else -> unexpected(token, "input")
            }
        }

        private fun action(): Node {
            val token = nextNonSpace()
            when (token.type) {
                T.BLOCK -> return blockControl()
                T.BREAK -> return loopControl("{{break}}", N.BREAK)
                T.CONTINUE -> return loopControl("{{continue}}", N.CONTINUE)
                T.ELSE -> return elseControl()
                T.END -> {
                    expect(T.RIGHT_DELIM, "end")
                    return Node(N.END, "{{end}}")
                }
                T.IF -> return parseControl("if", N.IF)
                T.RANGE -> return parseControl("range", N.RANGE)
                T.TEMPLATE -> return templateControl()
                T.WITH -> return parseControl("with", N.WITH)
                else -> {}
            }
            backup()
            peek()
            pipeline("command", T.RIGHT_DELIM)
            return Node(N.ACTION, "")
        }

        private fun loopControl(text: String, type: N): Node {
            val token = nextNonSpace()
            if (token.type != T.RIGHT_DELIM) unexpected(token, text)
            if (rangeDepth == 0) errorf("$text outside {{range}}")
            return Node(type, text)
        }

        private fun pipeline(context: String, end: T): Node {
            peekNonSpace()
            var decls = 0
            decl@ while (true) {
                val v = peekNonSpace()
                if (v.type != T.VARIABLE) break
                next()
                val afterVariable = peek()
                val nextToken = peekNonSpace()
                when {
                    nextToken.type == T.ASSIGN || nextToken.type == T.DECLARE -> {
                        nextNonSpace()
                        decls++
                        vars += v.value
                    }
                    nextToken.type == T.CHAR && nextToken.value == "," -> {
                        nextNonSpace()
                        decls++
                        vars += v.value
                        if (context == "range" && decls < 2) {
                            when (peekNonSpace().type) {
                                T.VARIABLE, T.RIGHT_DELIM, T.RIGHT_PAREN -> continue@decl
                                else -> errorf("range can only initialize variables")
                            }
                        }
                        errorf("too many declarations in $context")
                    }
                    afterVariable.type == T.SPACE -> backup3(v, afterVariable)
                    else -> backup2(v)
                }
                break
            }
            val commands = ArrayList<List<Node>>()
            while (true) {
                val token = nextNonSpace()
                when (token.type) {
                    end -> {
                        if (commands.isEmpty()) errorf("missing value for $context")
                        for (i in 1 until commands.size) {
                            if (commands[i][0].type in LITERALS) errorf("non executable command in pipeline stage ${i + 1}")
                        }
                        return Node(N.PIPE, "")
                    }
                    T.BOOL, T.CHAR_CONSTANT, T.COMPLEX, T.DOT, T.FIELD, T.IDENTIFIER, T.NUMBER, T.NIL, T.RAW_STRING, T.STRING, T.VARIABLE, T.LEFT_PAREN -> {
                        backup()
                        commands += command()
                    }
                    else -> unexpected(token, context)
                }
            }
        }

        private fun parseControl(context: String, type: N): Node {
            val saved = vars.size
            try {
                pipeline(context, T.RIGHT_DELIM)
                if (context == "range") rangeDepth++
                val next = itemList()
                if (context == "range") rangeDepth--
                if (next.type == N.ELSE) {
                    if (context == "if" && peek().type == T.IF) {
                        next()
                        parseControl("if", N.IF)
                    } else if (context == "with" && peek().type == T.WITH) {
                        next()
                        parseControl("with", N.WITH)
                    } else {
                        val end = itemList()
                        if (end.type != N.END) errorf("expected end; found $end")
                    }
                }
                return Node(type, "")
            } finally {
                while (vars.size > saved) vars.removeAt(vars.lastIndex)
            }
        }

        private fun elseControl(): Node {
            val peek = peekNonSpace()
            if (peek.type == T.IF || peek.type == T.WITH) return Node(N.ELSE, "{{else}}")
            expect(T.RIGHT_DELIM, "else")
            return Node(N.ELSE, "{{else}}")
        }

        private fun blockControl(): Node {
            val context = "block clause"
            val token = nextNonSpace()
            templateName(token, context)
            pipeline(context, T.RIGHT_DELIM)
            val end = Parser(lex).itemList()
            if (end.type != N.END) errorf("unexpected $end in $context")
            return Node(N.TEMPLATE, "")
        }

        private fun templateControl(): Node {
            val context = "template clause"
            val token = nextNonSpace()
            templateName(token, context)
            if (nextNonSpace().type != T.RIGHT_DELIM) {
                backup()
                pipeline(context, T.RIGHT_DELIM)
            }
            return Node(N.TEMPLATE, "")
        }

        private fun templateName(token: Item, context: String) {
            if (token.type == T.STRING || token.type == T.RAW_STRING) unquote(token) else unexpected(token, context)
        }

        private fun command(): List<Node> {
            val args = ArrayList<Node>()
            while (true) {
                peekNonSpace()
                operand()?.let { args += it }
                val token = next()
                when (token.type) {
                    T.SPACE -> continue
                    T.RIGHT_DELIM, T.RIGHT_PAREN -> backup()
                    T.PIPE -> {}
                    else -> unexpected(token, "operand")
                }
                break
            }
            if (args.isEmpty()) errorf("empty command")
            return args
        }

        private fun operand(): Node? {
            val node = term() ?: return null
            if (peek().type != T.FIELD) return node
            val chain = StringBuilder(node.text)
            while (peek().type == T.FIELD) chain.append(next().value)
            return when (node.type) {
                N.FIELD -> Node(N.FIELD, chain.toString())
                N.VARIABLE -> Node(N.VARIABLE, chain.toString())
                N.BOOL, N.STRING, N.NUMBER, N.NIL, N.DOT -> errorf("unexpected . after term ${GoStaticcheckPsi.quote(node.text)}")
                else -> Node(N.CHAIN, chain.toString())
            }
        }

        private fun term(): Node? {
            val token = nextNonSpace()
            return when (token.type) {
                T.IDENTIFIER -> {
                    if (token.value !in BUILTINS) errorf("function ${GoStaticcheckPsi.quote(token.value)} not defined")
                    Node(N.IDENTIFIER, token.value)
                }
                T.DOT -> Node(N.DOT, ".")
                T.NIL -> Node(N.NIL, "nil")
                T.VARIABLE -> {
                    val name = token.value.substringBefore('.')
                    if (name !in vars) errorf("undefined variable ${GoStaticcheckPsi.quote(name)}")
                    Node(N.VARIABLE, token.value)
                }
                T.FIELD -> Node(N.FIELD, token.value)
                T.BOOL -> Node(N.BOOL, token.value)
                T.CHAR_CONSTANT, T.COMPLEX, T.NUMBER -> Node(N.NUMBER, token.value)
                T.LEFT_PAREN -> pipeline("parenthesized pipeline", T.RIGHT_PAREN)
                T.STRING, T.RAW_STRING -> {
                    unquote(token)
                    Node(N.STRING, token.value)
                }
                else -> {
                    backup()
                    null
                }
            }
        }

        private companion object {
            val LITERALS = setOf(N.BOOL, N.DOT, N.NIL, N.NUMBER, N.STRING)
        }
    }
}
