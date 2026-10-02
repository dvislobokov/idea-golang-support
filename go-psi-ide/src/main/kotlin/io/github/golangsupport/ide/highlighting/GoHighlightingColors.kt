package io.github.golangsupport.ide.highlighting

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Default
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey

/** Text attribute keys for Go lexical highlighting, falling back to the platform defaults. */
object GoHighlightingColors {
    @JvmField val KEYWORD: TextAttributesKey = createTextAttributesKey("GO_KEYWORD", Default.KEYWORD)
    @JvmField val IDENTIFIER: TextAttributesKey = createTextAttributesKey("GO_IDENTIFIER", Default.IDENTIFIER)
    @JvmField val STRING: TextAttributesKey = createTextAttributesKey("GO_STRING", Default.STRING)
    @JvmField val RUNE: TextAttributesKey = createTextAttributesKey("GO_RUNE", Default.STRING)
    @JvmField val NUMBER: TextAttributesKey = createTextAttributesKey("GO_NUMBER", Default.NUMBER)
    @JvmField val LINE_COMMENT: TextAttributesKey = createTextAttributesKey("GO_LINE_COMMENT", Default.LINE_COMMENT)
    @JvmField val BLOCK_COMMENT: TextAttributesKey = createTextAttributesKey("GO_BLOCK_COMMENT", Default.BLOCK_COMMENT)
    @JvmField val OPERATOR: TextAttributesKey = createTextAttributesKey("GO_OPERATOR", Default.OPERATION_SIGN)
    @JvmField val BRACES: TextAttributesKey = createTextAttributesKey("GO_BRACES", Default.BRACES)
    @JvmField val BRACKETS: TextAttributesKey = createTextAttributesKey("GO_BRACKETS", Default.BRACKETS)
    @JvmField val PARENTHESES: TextAttributesKey = createTextAttributesKey("GO_PARENTHESES", Default.PARENTHESES)
    @JvmField val COMMA: TextAttributesKey = createTextAttributesKey("GO_COMMA", Default.COMMA)
    @JvmField val SEMICOLON: TextAttributesKey = createTextAttributesKey("GO_SEMICOLON", Default.SEMICOLON)
    @JvmField val DOT: TextAttributesKey = createTextAttributesKey("GO_DOT", Default.DOT)
    @JvmField val BAD_CHARACTER: TextAttributesKey = createTextAttributesKey("GO_BAD_CHARACTER", HighlighterColors.BAD_CHARACTER)

    // Semantic highlighting (GoSemanticHighlightingAnnotator): identifiers by resolved kind.
    @JvmField val PACKAGE: TextAttributesKey = createTextAttributesKey("GO_PACKAGE", Default.IDENTIFIER)
    @JvmField val TYPE: TextAttributesKey = createTextAttributesKey("GO_TYPE", Default.CLASS_NAME)
    @JvmField val TYPE_PARAMETER: TextAttributesKey = createTextAttributesKey("GO_TYPE_PARAMETER", Default.CLASS_REFERENCE)
    @JvmField val BUILTIN_TYPE: TextAttributesKey = createTextAttributesKey("GO_BUILTIN_TYPE", Default.KEYWORD)
    @JvmField val FUNCTION_DECLARATION: TextAttributesKey = createTextAttributesKey("GO_FUNCTION_DECLARATION", Default.FUNCTION_DECLARATION)
    @JvmField val FUNCTION_CALL: TextAttributesKey = createTextAttributesKey("GO_FUNCTION_CALL", Default.FUNCTION_CALL)
    @JvmField val METHOD_DECLARATION: TextAttributesKey = createTextAttributesKey("GO_METHOD_DECLARATION", Default.INSTANCE_METHOD)
    @JvmField val METHOD_CALL: TextAttributesKey = createTextAttributesKey("GO_METHOD_CALL", Default.INSTANCE_METHOD)
    @JvmField val FIELD: TextAttributesKey = createTextAttributesKey("GO_FIELD", Default.INSTANCE_FIELD)
    @JvmField val PARAMETER: TextAttributesKey = createTextAttributesKey("GO_PARAMETER", Default.PARAMETER)
    @JvmField val LOCAL_VARIABLE: TextAttributesKey = createTextAttributesKey("GO_LOCAL_VARIABLE", Default.LOCAL_VARIABLE)
    @JvmField val PACKAGE_VARIABLE: TextAttributesKey = createTextAttributesKey("GO_PACKAGE_VARIABLE", Default.GLOBAL_VARIABLE)
    @JvmField val CONSTANT: TextAttributesKey = createTextAttributesKey("GO_CONSTANT", Default.CONSTANT)
    @JvmField val BUILTIN_FUNCTION: TextAttributesKey = createTextAttributesKey("GO_BUILTIN_FUNCTION", Default.PREDEFINED_SYMBOL)
    @JvmField val BUILTIN_CONSTANT: TextAttributesKey = createTextAttributesKey("GO_BUILTIN_CONSTANT", Default.KEYWORD)
    @JvmField val LABEL: TextAttributesKey = createTextAttributesKey("GO_LABEL", Default.LABEL)
}
