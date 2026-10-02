package io.github.golangsupport.lang

import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

class GoTextTokenType(debugName: String) : IElementType(debugName, GoLanguage) {
    override fun toString(): String = "Go:" + super.toString()
}

object GoTextTokens {
    @JvmField val LINE_COMMENT = GoTextTokenType("LINE_COMMENT")
    @JvmField val BLOCK_COMMENT = GoTextTokenType("BLOCK_COMMENT")

    /** `//go:build`, `//go:generate`, `//go:embed`, ...: a comment for the compiler, a command for the toolchain. */
    @JvmField val DIRECTIVE = GoTextTokenType("DIRECTIVE")

    @JvmField val STRING = GoTextTokenType("STRING")
    @JvmField val RAW_STRING = GoTextTokenType("RAW_STRING")
    @JvmField val CHAR = GoTextTokenType("CHAR")
    @JvmField val NUMBER = GoTextTokenType("NUMBER")
    @JvmField val KEYWORD = GoTextTokenType("KEYWORD")
    @JvmField val IDENTIFIER = GoTextTokenType("IDENTIFIER")

    @JvmField val LBRACE = GoTextTokenType("LBRACE")
    @JvmField val RBRACE = GoTextTokenType("RBRACE")
    @JvmField val LPAREN = GoTextTokenType("LPAREN")
    @JvmField val RPAREN = GoTextTokenType("RPAREN")
    @JvmField val LBRACKET = GoTextTokenType("LBRACKET")
    @JvmField val RBRACKET = GoTextTokenType("RBRACKET")
    @JvmField val SEMICOLON = GoTextTokenType("SEMICOLON")
    @JvmField val COMMA = GoTextTokenType("COMMA")
    @JvmField val DOT = GoTextTokenType("DOT")
    @JvmField val OPERATOR = GoTextTokenType("OPERATOR")

    @JvmField val COMMENTS = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT, DIRECTIVE)
    @JvmField val STRINGS = TokenSet.create(STRING, RAW_STRING, CHAR)

    /** All 25 of them: Go has no contextual keywords. */
    @JvmField val KEYWORDS: Set<String> = setOf(
        "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough", "for", "func", "go", "goto",
        "if", "import", "interface", "map", "package", "range", "return", "select", "struct", "switch", "type", "var",
    )

    /** Predeclared identifiers: not keywords (they can be shadowed), coloured by [GoIdentifierAnnotator]. */
    @JvmField val BUILTIN_TYPES: Set<String> = setOf(
        "any", "bool", "byte", "comparable", "complex64", "complex128", "error", "float32", "float64", "int", "int8", "int16", "int32", "int64",
        "rune", "string", "uint", "uint8", "uint16", "uint32", "uint64", "uintptr",
    )
    @JvmField val BUILTIN_CONSTANTS: Set<String> = setOf("true", "false", "nil", "iota")
    @JvmField val BUILTIN_FUNCTIONS: Set<String> = setOf(
        "append", "cap", "clear", "close", "complex", "copy", "delete", "imag", "len", "make", "max", "min", "new", "panic", "print", "println",
        "real", "recover",
    )
}
