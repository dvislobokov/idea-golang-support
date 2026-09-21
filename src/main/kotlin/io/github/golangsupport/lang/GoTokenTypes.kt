package io.github.golangsupport.lang

import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

class GoTokenType(debugName: String) : IElementType(debugName, GoLanguage) {
    override fun toString(): String = "Go:" + super.toString()
}

object GoTokenTypes {
    @JvmField val LINE_COMMENT = GoTokenType("LINE_COMMENT")
    @JvmField val BLOCK_COMMENT = GoTokenType("BLOCK_COMMENT")

    /** `//go:build`, `//go:generate`, `//go:embed`, ...: a comment for the compiler, a command for the toolchain. */
    @JvmField val DIRECTIVE = GoTokenType("DIRECTIVE")

    @JvmField val STRING = GoTokenType("STRING")
    @JvmField val RAW_STRING = GoTokenType("RAW_STRING")
    @JvmField val CHAR = GoTokenType("CHAR")
    @JvmField val NUMBER = GoTokenType("NUMBER")
    @JvmField val KEYWORD = GoTokenType("KEYWORD")
    @JvmField val IDENTIFIER = GoTokenType("IDENTIFIER")

    @JvmField val LBRACE = GoTokenType("LBRACE")
    @JvmField val RBRACE = GoTokenType("RBRACE")
    @JvmField val LPAREN = GoTokenType("LPAREN")
    @JvmField val RPAREN = GoTokenType("RPAREN")
    @JvmField val LBRACKET = GoTokenType("LBRACKET")
    @JvmField val RBRACKET = GoTokenType("RBRACKET")
    @JvmField val SEMICOLON = GoTokenType("SEMICOLON")
    @JvmField val COMMA = GoTokenType("COMMA")
    @JvmField val DOT = GoTokenType("DOT")
    @JvmField val OPERATOR = GoTokenType("OPERATOR")

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
