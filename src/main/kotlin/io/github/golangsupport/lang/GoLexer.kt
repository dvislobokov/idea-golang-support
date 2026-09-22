package io.github.golangsupport.lang

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * Hand-written Go lexer. Every token is self-contained (a raw string is a single token), so the lexer is stateless
 * and can be restarted from any token boundary.
 */
class GoLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var bufferEnd = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        bufferEnd = endOffset
        tokenStart = startOffset
        tokenEnd = startOffset
        advance()
    }

    override fun getState(): Int = 0
    override fun getTokenType(): IElementType? = tokenType
    override fun getTokenStart(): Int = tokenStart
    override fun getTokenEnd(): Int = tokenEnd
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = bufferEnd

    override fun advance() {
        tokenStart = tokenEnd
        if (tokenStart >= bufferEnd) {
            tokenType = null
            return
        }
        val start = tokenStart
        val c = buffer[start]
        when {
            c.isWhitespace() -> token(TokenType.WHITE_SPACE, skipWhile(start) { it.isWhitespace() })
            c == '/' && charAt(start + 1) == '/' -> token(if (isDirective(start)) GoTokenTypes.DIRECTIVE else GoTokenTypes.LINE_COMMENT, lineEnd(start))
            c == '/' && charAt(start + 1) == '*' -> {
                val close = indexOf("*/", start + 2)
                token(GoTokenTypes.BLOCK_COMMENT, if (close < 0) bufferEnd else close + 2)
            }
            c == '"' -> token(GoTokenTypes.STRING, scanQuoted(start, '"'))
            c == '\'' -> token(GoTokenTypes.CHAR, scanQuoted(start, '\''))
            c == '`' -> {
                val close = skipWhile(start + 1) { it != '`' }
                token(GoTokenTypes.RAW_STRING, if (close >= bufferEnd) bufferEnd else close + 1)
            }
            c.isDigit() || (c == '.' && charAt(start + 1).isDigit()) -> token(GoTokenTypes.NUMBER, scanNumber(start))
            isIdentifierStart(c) -> {
                val end = skipWhile(start, ::isIdentifierPart)
                val isKeyword = buffer.subSequence(start, end).toString() in GoTokenTypes.KEYWORDS
                token(if (isKeyword) GoTokenTypes.KEYWORD else GoTokenTypes.IDENTIFIER, end)
            }
            else -> token(punctuation(c), start + 1)
        }
    }

    private fun token(type: IElementType, end: Int) {
        tokenType = type
        tokenEnd = end.coerceIn(tokenStart + 1, bufferEnd)
    }

    private fun punctuation(c: Char): IElementType = when (c) {
        '{' -> GoTokenTypes.LBRACE
        '}' -> GoTokenTypes.RBRACE
        '(' -> GoTokenTypes.LPAREN
        ')' -> GoTokenTypes.RPAREN
        '[' -> GoTokenTypes.LBRACKET
        ']' -> GoTokenTypes.RBRACKET
        ';' -> GoTokenTypes.SEMICOLON
        ',' -> GoTokenTypes.COMMA
        '.' -> GoTokenTypes.DOT
        '+', '-', '*', '/', '%', '&', '|', '^', '!', '~', '=', '<', '>', ':' -> GoTokenTypes.OPERATOR
        else -> TokenType.BAD_CHARACTER
    }

    /** Char at [offset], or NUL when out of the lexed range. */
    private fun charAt(offset: Int): Char = if (offset < bufferEnd) buffer[offset] else '\u0000'

    private inline fun skipWhile(from: Int, predicate: (Char) -> Boolean): Int {
        var i = from
        while (i < bufferEnd && predicate(buffer[i])) i++
        return i
    }

    private fun lineEnd(from: Int): Int = skipWhile(from) { it != '\n' && it != '\r' }

    private fun indexOf(text: String, from: Int): Int {
        var i = from
        while (i + text.length <= bufferEnd) {
            if (buffer.startsWith(text, i)) return i
            i++
        }
        return -1
    }

    /** `//go:build linux`, `//go:generate ...`, `//line ...`: no space after the slashes, a lower-case word and a colon. */
    private fun isDirective(start: Int): Boolean {
        val wordEnd = skipWhile(start + 2) { it in 'a'..'z' }
        return wordEnd > start + 2 && charAt(wordEnd) == ':' && !charAt(wordEnd + 1).isWhitespace() && charAt(wordEnd + 1) != '\u0000'
    }

    /** An unterminated literal ends at the line break: it must not swallow the rest of the file while typing. */
    private fun scanQuoted(start: Int, quote: Char): Int {
        var i = start + 1
        while (i < bufferEnd) {
            when (buffer[i]) {
                '\\' -> i += 2
                quote -> return i + 1
                '\n', '\r' -> return i
                else -> i++
            }
        }
        return bufferEnd
    }

    private fun scanNumber(start: Int): Int {
        val hex = buffer[start] == '0' && (charAt(start + 1) == 'x' || charAt(start + 1) == 'X')
        var i = start
        while (i < bufferEnd) {
            val c = buffer[i]
            val previous = if (i > start) buffer[i - 1] else ' '
            when {
                c.isLetterOrDigit() || c == '_' -> i++
                c == '.' && (charAt(i + 1).isDigit() || !isIdentifierStart(charAt(i + 1))) && charAt(i + 1) != '.' -> i++
                (c == '+' || c == '-') && (if (hex) previous == 'p' || previous == 'P' else previous == 'e' || previous == 'E') -> i++
                else -> break
            }
        }
        return i
    }

    private fun isIdentifierStart(c: Char): Boolean = c == '_' || c.isLetter()
    private fun isIdentifierPart(c: Char): Boolean = c == '_' || c.isLetterOrDigit()
}
