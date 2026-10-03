package io.github.golangsupport.ide.asm

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import io.github.golangsupport.ide.asm.GoAsmTokenTypes as T

/**
 * Hand-written lexer of Go assembly. Its one bit of state is the position in a statement: at the start (after a newline, `;`, a block
 * comment spanning lines or a label) the first word is the instruction or directive, afterwards words are operands. That is all the
 * highlighter needs; there is no grammar of operands. State 0 is "statement start", so the platform restarts relexing at line starts.
 */
class GoAsmLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var end = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null
    private var tokenState = STATEMENT
    private var state = STATEMENT

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        end = endOffset
        tokenEnd = startOffset
        state = initialState
        advance()
    }

    override fun getState(): Int = tokenState
    override fun getTokenType(): IElementType? = tokenType
    override fun getTokenStart(): Int = tokenStart
    override fun getTokenEnd(): Int = tokenEnd
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = end

    override fun advance() {
        tokenStart = tokenEnd
        tokenState = state
        if (tokenStart >= end) {
            tokenType = null
            return
        }
        tokenType = lex(tokenStart)
    }

    private fun at(i: Int): Char = if (i < end) buffer[i] else '\u0000'

    /** Lexes the token at [i]: sets [tokenEnd] and the [state] for the next token. */
    private fun lex(i: Int): IElementType {
        val c = buffer[i]
        when {
            c.isWhitespace() -> {
                var j = i
                while (j < end && buffer[j].isWhitespace()) {
                    if (buffer[j] == '\n') state = STATEMENT
                    j++
                }
                return token(j, TokenType.WHITE_SPACE)
            }
            c == '/' && at(i + 1) == '/' -> {
                var j = i + 2
                while (j < end && buffer[j] != '\n') j++
                return token(j, T.LINE_COMMENT)
            }
            c == '/' && at(i + 1) == '*' -> {
                var j = i + 2
                while (j < end && !(buffer[j] == '*' && at(j + 1) == '/')) j++
                val close = minOf(end, j + 2)
                if ((i until minOf(j, end)).any { buffer[it] == '\n' }) state = STATEMENT // cmd/asm reads such a comment as a newline
                return token(close, T.BLOCK_COMMENT)
            }
            c == '#' && state == STATEMENT -> {
                var j = i + 1
                while (j < end && buffer[j].isLetter()) j++
                state = OPERANDS
                return token(j, T.PREPROCESSOR)
            }
            c == '"' || c == '\'' || c == '`' -> {
                var j = i + 1
                while (j < end && buffer[j] != c && buffer[j] != '\n') j += if (buffer[j] == '\\' && c != '`') 2 else 1
                state = OPERANDS
                return token(if (j < end && buffer[j] == c) j + 1 else minOf(j, end), T.STRING)
            }
            c == '$' && (at(i + 1).isDigit() || at(i + 1) == '-' && at(i + 2).isDigit()) -> {
                state = OPERANDS
                return token(number(if (at(i + 1) == '-') i + 2 else i + 1), T.NUMBER)
            }
            c.isDigit() -> {
                state = OPERANDS
                return token(number(i), T.NUMBER)
            }
            isNameStart(c) -> {
                var j = i + 1
                while (j < end && isNamePart(buffer[j])) j++
                val word = buffer.subSequence(i, j).toString()
                val type = when {
                    state == STATEMENT && at(j) == ':' && at(j + 1) != ':' -> T.LABEL // the statement after the label still starts
                    state == STATEMENT -> {
                        state = OPERANDS
                        if (word in DIRECTIVES) T.DIRECTIVE else T.INSTRUCTION
                    }
                    '·' in word || '∕' in word -> T.SYMBOL
                    word in PSEUDO_REGISTERS -> T.PSEUDO_REGISTER
                    REGISTER.matches(word) -> T.REGISTER
                    else -> T.IDENTIFIER
                }
                return token(j, type)
            }
            c == '<' && i > 0 && isNamePart(buffer[i - 1]) -> {
                var j = i + 1
                while (j < end && buffer[j].isLetterOrDigit()) j++
                if (at(j) == '>') return token(j + 1, T.ABI_SUFFIX)
                return token(if (at(i + 1) == '<') i + 2 else i + 1, T.OPERATOR)
            }
            c == ';' -> {
                state = STATEMENT
                return token(i + 1, T.SEMICOLON)
            }
            c == ':' -> return token(i + 1, T.COLON)
            c == '(' -> return token(i + 1, T.LPAREN)
            c == ')' -> return token(i + 1, T.RPAREN)
            c == '[' -> return token(i + 1, T.LBRACKET)
            c == ']' -> return token(i + 1, T.RBRACKET)
            c == ',' -> return token(i + 1, T.COMMA)
            (c == '<' || c == '>') && at(i + 1) == c -> return token(i + 2, T.OPERATOR)
            c in OPERATOR_CHARS -> return token(i + 1, T.OPERATOR)
            else -> return token(i + 1, TokenType.BAD_CHARACTER)
        }
    }

    private fun token(endOffset: Int, type: IElementType): IElementType {
        tokenEnd = endOffset
        return type
    }

    /** End of a number starting with a digit at [i]: hex, octal/binary prefixes, decimals with a fraction and an exponent. */
    private fun number(i: Int): Int {
        var j = i
        if (at(j) == '0' && at(j + 1).lowercaseChar() in "xob") {
            j += 2
            while (j < end && (buffer[j].isLetterOrDigit() || buffer[j] == '_')) j++
            return j
        }
        while (j < end && buffer[j].isDigit()) j++
        if (at(j) == '.' && at(j + 1).isDigit()) {
            j++
            while (j < end && buffer[j].isDigit()) j++
        }
        if (at(j).lowercaseChar() == 'e' && (at(j + 1).isDigit() || at(j + 1) in "+-" && at(j + 2).isDigit())) {
            j += 2
            while (j < end && buffer[j].isDigit()) j++
        }
        return j
    }

    companion object {
        const val STATEMENT = 0
        const val OPERANDS = 1

        private const val OPERATOR_CHARS = "+-*/|&^~!%=<>.\\@{}$?"

        val DIRECTIVES = setOf("TEXT", "DATA", "GLOBL", "FUNCDATA", "PCDATA")
        val PSEUDO_REGISTERS = setOf("SB", "FP", "SP", "PC")

        /** General registers of the Go ports (amd64/386, arm64/arm, ppc64, s390x, riscv64, loong64, mips): a heuristic over names only. */
        private val REGISTER = Regex("R\\d{1,2}[BWL]?|[XYZVFKD]\\d{1,2}|VS\\d{1,2}|CR\\d|[ABCD][XLH]|[SD]IB?|BPB?|SPB|RSP|ZR|LR|CTR|g")

        /** `·` (U+00B7) separates package and name, `∕` (U+2215) stands for `/` in import paths. */
        fun isNameStart(c: Char): Boolean = c.isLetter() || c == '_' || c == '·' || c == '∕'

        fun isNamePart(c: Char): Boolean = isNameStart(c) || c.isDigit()
    }
}
