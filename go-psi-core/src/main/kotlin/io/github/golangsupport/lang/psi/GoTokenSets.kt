package io.github.golangsupport.lang.psi

import com.intellij.psi.TokenType
import com.intellij.psi.tree.TokenSet
import io.github.golangsupport.lang.psi.GoTypes.*

/**
 * Token sets over the generated [GoTypes] token types. [SEMICOLON_SYNTHETIC] is a real token
 * consumed by the parser, so it is neither whitespace nor a comment.
 */
object GoTokenSets {
    @JvmField
    val KEYWORDS: TokenSet = TokenSet.create(
        BREAK, CASE, CHAN, CONST, CONTINUE, DEFAULT, DEFER, ELSE, FALLTHROUGH, FOR, FUNC, GO, GOTO,
        IF, IMPORT, INTERFACE, MAP, PACKAGE, RANGE, RETURN, SELECT, STRUCT, SWITCH, TYPE_, VAR,
    )

    /** Operators and punctuation, including the explicit [SEMICOLON]; not [SEMICOLON_SYNTHETIC]. */
    @JvmField
    val OPERATORS: TokenSet = TokenSet.create(
        ADD, SUB, MUL, QUO, REM, AND, OR, XOR, SHL, SHR, AND_NOT,
        ADD_ASSIGN, SUB_ASSIGN, MUL_ASSIGN, QUO_ASSIGN, REM_ASSIGN, AND_ASSIGN, OR_ASSIGN,
        XOR_ASSIGN, SHL_ASSIGN, SHR_ASSIGN, AND_NOT_ASSIGN,
        LAND, LOR, ARROW, INC, DEC, EQL, LSS, GTR, ASSIGN, NOT, NEQ, LEQ, GEQ, DEFINE, ELLIPSIS,
        LPAREN, LBRACK, LBRACE, COMMA, PERIOD, RPAREN, RBRACK, RBRACE, SEMICOLON, COLON, TILDE,
    )

    @JvmField
    val NUMBERS: TokenSet = TokenSet.create(INT, FLOAT, IMAG)

    /** Interpreted and raw string literals (not runes). */
    @JvmField
    val STRING_LITERALS: TokenSet = TokenSet.create(STRING, RAW_STRING)

    @JvmField
    val LITERALS: TokenSet = TokenSet.orSet(NUMBERS, STRING_LITERALS, TokenSet.create(CHAR))

    @JvmField
    val COMMENTS: TokenSet = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT)

    @JvmField
    val WHITESPACES: TokenSet = TokenSet.create(TokenType.WHITE_SPACE)

    /** Explicit and automatically inserted semicolons. */
    @JvmField
    val SEMICOLONS: TokenSet = TokenSet.create(SEMICOLON, SEMICOLON_SYNTHETIC)
}
