package io.github.golangsupport.lang

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Default
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey

/**
 * The palette of the plugin: the `GO_*` keys of the colour schemes. The schemes and the colour page are in the root module
 * (`GoSyntaxHighlighter`, `colorSchemes/GoDefault.xml`, `GoDarcula.xml`); the keys are hosted here only because the IDE layer below the root has to compile against them.
 */
object GoColors {
    val KEYWORD = createTextAttributesKey("GO_KEYWORD", Default.KEYWORD)
    val STRING = createTextAttributesKey("GO_STRING", Default.STRING)
    val NUMBER = createTextAttributesKey("GO_NUMBER", Default.NUMBER)
    val LINE_COMMENT = createTextAttributesKey("GO_LINE_COMMENT", Default.LINE_COMMENT)
    val BLOCK_COMMENT = createTextAttributesKey("GO_BLOCK_COMMENT", Default.BLOCK_COMMENT)
    val DIRECTIVE = createTextAttributesKey("GO_DIRECTIVE", Default.METADATA)
    val BRACES = createTextAttributesKey("GO_BRACES", Default.BRACES)
    val PARENTHESES = createTextAttributesKey("GO_PARENTHESES", Default.PARENTHESES)
    val BRACKETS = createTextAttributesKey("GO_BRACKETS", Default.BRACKETS)
    val SEMICOLON = createTextAttributesKey("GO_SEMICOLON", Default.SEMICOLON)
    val COMMA = createTextAttributesKey("GO_COMMA", Default.COMMA)
    val DOT = createTextAttributesKey("GO_DOT", Default.DOT)
    val OPERATOR = createTextAttributesKey("GO_OPERATOR", Default.OPERATION_SIGN)
    val BAD_CHARACTER = createTextAttributesKey("GO_BAD_CHARACTER", HighlighterColors.BAD_CHARACTER)

    // by GoIdentifierAnnotator
    val BUILTIN_TYPE = createTextAttributesKey("GO_BUILTIN_TYPE", Default.KEYWORD)
    val BUILTIN_CONSTANT = createTextAttributesKey("GO_BUILTIN_CONSTANT", Default.KEYWORD)
    val BUILTIN_FUNCTION = createTextAttributesKey("GO_BUILTIN_FUNCTION", Default.PREDEFINED_SYMBOL)
    val FUNCTION_DECLARATION = createTextAttributesKey("GO_FUNCTION_DECLARATION", Default.FUNCTION_DECLARATION)
    val TYPE_DECLARATION = createTextAttributesKey("GO_TYPE_DECLARATION", Default.CLASS_NAME)
    val FUNCTION_CALL = createTextAttributesKey("GO_FUNCTION_CALL", Default.FUNCTION_CALL)
    val FIELD = createTextAttributesKey("GO_FIELD", Default.INSTANCE_FIELD)
    val CONSTANT = createTextAttributesKey("GO_CONSTANT", Default.CONSTANT)

    // by the semantic tokens of gopls (GoSemanticColors), and what of it the annotator can tell by itself
    val TYPE_REFERENCE = createTextAttributesKey("GO_TYPE_REFERENCE", Default.CLASS_REFERENCE)
    val PACKAGE = createTextAttributesKey("GO_PACKAGE", Default.IDENTIFIER)
    val PARAMETER = createTextAttributesKey("GO_PARAMETER", Default.PARAMETER)
    val LOCAL_VARIABLE = createTextAttributesKey("GO_LOCAL_VARIABLE", Default.LOCAL_VARIABLE)
    val PACKAGE_VARIABLE = createTextAttributesKey("GO_PACKAGE_VARIABLE", Default.GLOBAL_VARIABLE)
    val LABEL = createTextAttributesKey("GO_LABEL", Default.LABEL)
}
