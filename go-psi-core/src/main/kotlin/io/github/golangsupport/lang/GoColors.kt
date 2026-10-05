package io.github.golangsupport.lang

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Default
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey

/**
 * The palette of the plugin: the `GO_*` keys of the colour schemes. The schemes and the colour page are in the root module
 * (`GoSyntaxHighlighter`, `colorSchemes/GoDefault.xml`, `GoDarcula.xml`); the keys are hosted here only because the IDE layer below the root has to compile against them.
 *
 * The keys of GoLand (`docs/goland-analysis/dumps/color-keys-go.txt`) carry the same external names, so a scheme written for GoLand
 * colours Go the same here. Each falls back onto the key that coloured the element before it existed (the base keys of the first
 * block), not onto GoLand's fallback, so a scheme a user tuned keeps its look; GoLand's fallback is taken where there was no such key.
 */
object GoColors {
    // base keys: what the lexer, the text rules and the semantic tokens of gopls colour with; the finer keys of GoLand inherit from them
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

    // the keys of GoLand: tokens (GoSyntaxHighlighter)
    val IDENTIFIER = createTextAttributesKey("GO_IDENTIFIER", Default.IDENTIFIER)
    val BAD_TOKEN = createTextAttributesKey("GO_BAD_TOKEN", BAD_CHARACTER)
    val BRACKET = createTextAttributesKey("GO_BRACKET", BRACKETS)
    val COLON = createTextAttributesKey("GO_COLON", OPERATOR)
    val VALID_STRING_ESCAPE = createTextAttributesKey("GO_VALID_STRING_ESCAPE", Default.VALID_STRING_ESCAPE)
    val INVALID_STRING_ESCAPE = createTextAttributesKey("GO_INVALID_STRING_ESCAPE", Default.INVALID_STRING_ESCAPE)

    // comments: directives and doc comments (GoIdentifierAnnotator, the semantic annotator)
    val COMMENT_KEYWORD = createTextAttributesKey("GO_COMMENT_KEYWORD", DIRECTIVE)
    val COMMENT_REFERENCE = createTextAttributesKey("GO_COMMENT_REFERENCE", LINE_COMMENT)
    val BUILD_TAG = createTextAttributesKey("GO_BUILD_TAG", DIRECTIVE)
    val BUILD_PAREN = createTextAttributesKey("GO_BUILD_PAREN", DIRECTIVE)
    val BUILD_OPERATOR = createTextAttributesKey("GO_BUILD_OPERATOR", DIRECTIVE)

    // declarations
    val EXPORTED_FUNCTION = createTextAttributesKey("GO_EXPORTED_FUNCTION", FUNCTION_DECLARATION)
    val LOCAL_FUNCTION = createTextAttributesKey("GO_LOCAL_FUNCTION", FUNCTION_DECLARATION)
    val TYPE_SPECIFICATION = createTextAttributesKey("GO_TYPE_SPECIFICATION", TYPE_DECLARATION)
    val PACKAGE_EXPORTED_INTERFACE = createTextAttributesKey("GO_PACKAGE_EXPORTED_INTERFACE", TYPE_DECLARATION)
    val PACKAGE_EXPORTED_STRUCT = createTextAttributesKey("GO_PACKAGE_EXPORTED_STRUCT", TYPE_DECLARATION)
    val PACKAGE_LOCAL_INTERFACE = createTextAttributesKey("GO_PACKAGE_LOCAL_INTERFACE", TYPE_DECLARATION)
    val PACKAGE_LOCAL_STRUCT = createTextAttributesKey("GO_PACKAGE_LOCAL_STRUCT", TYPE_DECLARATION)
    val METHOD_RECEIVER = createTextAttributesKey("GO_METHOD_RECEIVER", PARAMETER)
    val FUNCTION_PARAMETER = createTextAttributesKey("GO_FUNCTION_PARAMETER", PARAMETER)
    val PACKAGE_EXPORTED_CONSTANT = createTextAttributesKey("GO_PACKAGE_EXPORTED_CONSTANT", CONSTANT)
    val PACKAGE_LOCAL_CONSTANT = createTextAttributesKey("GO_PACKAGE_LOCAL_CONSTANT", CONSTANT)
    val LOCAL_CONSTANT = createTextAttributesKey("GO_LOCAL_CONSTANT", CONSTANT)
    val BUILTIN_VARIABLE = createTextAttributesKey("GO_BUILTIN_VARIABLE", BUILTIN_CONSTANT)
    val PACKAGE_EXPORTED_VARIABLE = createTextAttributesKey("GO_PACKAGE_EXPORTED_VARIABLE", PACKAGE_VARIABLE)
    val PACKAGE_LOCAL_VARIABLE = createTextAttributesKey("GO_PACKAGE_LOCAL_VARIABLE", PACKAGE_VARIABLE)
    val SCOPE_VARIABLE = createTextAttributesKey("GO_SCOPE_VARIABLE", LOCAL_VARIABLE)
    val REASSIGNMENT_IN_SHORT_VAR_DECLARATION = createTextAttributesKey("GO_REASSIGNMENT_IN_SHORT_VAR_DECLARATION", LOCAL_VARIABLE)
    /** For the shadowing inspection (PLAN.md G1, a later step): nothing colours with it yet. */
    val SHADOWING_VARIABLE = createTextAttributesKey("GO_SHADOWING_VARIABLE", LOCAL_VARIABLE)
    val STRUCT_EXPORTED_MEMBER = createTextAttributesKey("GO_STRUCT_EXPORTED_MEMBER", FIELD)
    val STRUCT_LOCAL_MEMBER = createTextAttributesKey("GO_STRUCT_LOCAL_MEMBER", FIELD)

    // references
    val BUILTIN_TYPE_REFERENCE = createTextAttributesKey("GO_BUILTIN_TYPE_REFERENCE", BUILTIN_TYPE)
    val EXPORTED_INTERFACE_REFERENCE = createTextAttributesKey("GO_EXPORTED_INTERFACE_REFERENCE", TYPE_REFERENCE)
    val EXPORTED_STRUCT_REFERENCE = createTextAttributesKey("GO_EXPORTED_STRUCT_REFERENCE", TYPE_REFERENCE)
    val LOCAL_INTERFACE_REFERENCE = createTextAttributesKey("GO_LOCAL_INTERFACE_REFERENCE", TYPE_REFERENCE)
    val LOCAL_STRUCT_REFERENCE = createTextAttributesKey("GO_LOCAL_STRUCT_REFERENCE", TYPE_REFERENCE)
    val BUILTIN_FUNCTION_CALL = createTextAttributesKey("GO_BUILTIN_FUNCTION_CALL", BUILTIN_FUNCTION)
    val EXPORTED_FUNCTION_CALL = createTextAttributesKey("GO_EXPORTED_FUNCTION_CALL", FUNCTION_CALL)
    val LOCAL_FUNCTION_CALL = createTextAttributesKey("GO_LOCAL_FUNCTION_CALL", FUNCTION_CALL)
    val PACKAGE_EXPORTED_VARIABLE_CALL = createTextAttributesKey("GO_PACKAGE_EXPORTED_VARIABLE_CALL", PACKAGE_VARIABLE)
    val PACKAGE_LOCAL_VARIABLE_CALL = createTextAttributesKey("GO_PACKAGE_LOCAL_VARIABLE_CALL", PACKAGE_VARIABLE)
    val LOCAL_VARIABLE_CALL = createTextAttributesKey("GO_LOCAL_VARIABLE_CALL", LOCAL_VARIABLE)
    val STRUCT_LOCAL_MEMBER_CALL = createTextAttributesKey("GO_STRUCT_LOCAL_MEMBER_CALL", FIELD)
    val STRUCT_EXPORTED_MEMBER_CALL = createTextAttributesKey("GO_STRUCT_EXPORTED_MEMBER_CALL", FIELD)

    // inside string literals: struct tags and printf verbs (GoStringContentAnnotator); a tag used to be all string
    val TAG_KEY = createTextAttributesKey("GO_TAG_KEY", STRING)
    val TAG_COLON = createTextAttributesKey("GO_TAG_COLON", STRING)
    val TAG_VALUE = createTextAttributesKey("GO_TAG_VALUE", STRING)
    val TAG_TEXT = createTextAttributesKey("GO_TAG_TEXT", STRING)
    /** Not a key of GoLand (it colours verbs with [VALID_STRING_ESCAPE]): the same look by default, and a colour of its own if wanted. */
    val FORMAT_VERB = createTextAttributesKey("GO_FORMAT_VERB", VALID_STRING_ESCAPE)

    /** For the "Go fix" (modernizer) inspections of PLAN.md G5, the level GoLand shows them with; nothing uses it yet, not on the colour page. */
    val SYNTAX_UPDATE: TextAttributesKey = createTextAttributesKey("GO_SYNTAX_UPDATE", CodeInsightColors.WEAK_WARNING_ATTRIBUTES)
}
