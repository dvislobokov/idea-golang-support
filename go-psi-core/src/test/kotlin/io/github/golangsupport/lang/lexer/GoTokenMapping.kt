package io.github.golangsupport.lang.lexer

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import io.github.golangsupport.lang.psi.GoTypes
import java.lang.reflect.Modifier

/**
 * Maps GoLexer token types to the go/token names printed by `tools/astdump tokens`.
 *
 * Fixed tokens have their text as debug name (Grammar-Kit), so the go/token name is taken from
 * the [GoTypes] field name, which follows go/token by convention.
 */
object GoTokenMapping {
    private val fieldNames: Map<IElementType, String> =
        GoTypes::class.java.fields
            .filter { Modifier.isStatic(it.modifiers) && IElementType::class.java.isAssignableFrom(it.type) }
            .associate { it.get(null) as IElementType to it.name }

    private val renames = mapOf(
        "IDENTIFIER" to "IDENT",
        "RAW_STRING" to "STRING",
        "LINE_COMMENT" to "COMMENT",
        "BLOCK_COMMENT" to "COMMENT",
        "SEMICOLON_SYNTHETIC" to "SEMICOLON",
        "TYPE_" to "TYPE",
    )

    /** go/token name of [type], or null when go/scanner has no counterpart (whitespace). */
    fun goName(type: IElementType): String? = when (type) {
        TokenType.WHITE_SPACE -> null
        TokenType.BAD_CHARACTER -> "ILLEGAL"
        else -> {
            val name = fieldNames[type] ?: error("Not a GoTypes token: $type")
            renames[name] ?: name
        }
    }

    /** GoTypes field name of [type] (for example `LBRACE` for the `{` token). */
    fun fieldName(type: IElementType): String = fieldNames[type] ?: type.toString()
}
