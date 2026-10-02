package io.github.golangsupport.ide.formatter

import com.intellij.formatting.SpacingBuilder
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.tree.TokenSet
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.lang.psi.GoTypes.*

/**
 * gofmt-like spacing rules used when no layout is available (files with syntax errors). They cover
 * the token-level rules (blanks around assignments and comparison operators, after commas and
 * keywords, none inside brackets); precedence-dependent spacing of `+`/`*` and column alignment
 * need the full layout, so binary operators keep their spacing here.
 */
object GoSpacingBuilder {

    private val ASSIGN_OPS = TokenSet.create(
        ASSIGN, DEFINE, ADD_ASSIGN, SUB_ASSIGN, MUL_ASSIGN, QUO_ASSIGN, REM_ASSIGN, AND_ASSIGN, OR_ASSIGN, XOR_ASSIGN,
        SHL_ASSIGN, SHR_ASSIGN, AND_NOT_ASSIGN,
    )
    private val SPACED_OPS = TokenSet.create(LOR, LAND, EQL, NEQ, LSS, LEQ, GTR, GEQ)
    private val KEYWORDS_WITH_BLANK = TokenSet.create(
        BREAK, CASE, CHAN, CONST, CONTINUE, DEFER, ELSE, FOR, GO, GOTO, IF, IMPORT, PACKAGE, RANGE, RETURN, SELECT, SWITCH, TYPE_, VAR,
    )

    fun create(settings: CodeStyleSettings): SpacingBuilder =
        SpacingBuilder(settings, GoLanguage)
            .before(COMMA).spaces(0)
            .after(COMMA).spaces(1)
            .before(SEMICOLON).spaces(0)
            .around(ASSIGN_OPS).spaces(1)
            .around(ASSIGN_OP).spaces(1)
            .around(SPACED_OPS).spaces(1)
            .around(PERIOD).spaces(0)
            .after(LPAREN).spaces(0)
            .before(RPAREN).spaces(0)
            .after(LBRACK).spaces(0)
            .before(RBRACK).spaces(0)
            .before(ARGUMENT_LIST).spaces(0)
            .before(LITERAL_VALUE).spaces(0)
            .before(BLOCK).spaces(1)
            .before(ELSE_STATEMENT).spaces(1)
            .before(COLON).spaces(0)
            .afterInside(IDENTIFIER, FUNCTION_DECLARATION).spaces(0)
            .after(KEYWORDS_WITH_BLANK).spaces(1)
            .afterInside(FUNC, FUNCTION_DECLARATION).spaces(1)
            .afterInside(FUNC, METHOD_DECLARATION).spaces(1)
            .before(INC).spaces(0)
            .before(DEC).spaces(0)
}
