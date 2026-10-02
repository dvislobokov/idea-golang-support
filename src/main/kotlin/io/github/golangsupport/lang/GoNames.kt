package io.github.golangsupport.lang

/** The words of Go that are not names of the code: keywords and predeclared identifiers, for the text helpers that name things. */
object GoNames {
    /** All 25 of them: Go has no contextual keywords. */
    val KEYWORDS: Set<String> = setOf(
        "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough", "for", "func", "go", "goto",
        "if", "import", "interface", "map", "package", "range", "return", "select", "struct", "switch", "type", "var",
    )

    /** Predeclared identifiers: not keywords (they can be shadowed), coloured by [GoIdentifierAnnotator]. */
    val BUILTIN_TYPES: Set<String> = setOf(
        "any", "bool", "byte", "comparable", "complex64", "complex128", "error", "float32", "float64", "int", "int8", "int16", "int32", "int64",
        "rune", "string", "uint", "uint8", "uint16", "uint32", "uint64", "uintptr",
    )
    val BUILTIN_CONSTANTS: Set<String> = setOf("true", "false", "nil", "iota")
    val BUILTIN_FUNCTIONS: Set<String> = setOf(
        "append", "cap", "clear", "close", "complex", "copy", "delete", "imag", "len", "make", "max", "min", "new", "panic", "print", "println",
        "real", "recover",
    )
}
