package io.github.golangsupport.lang

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import io.github.golangsupport.GoIcons
import javax.swing.Icon

class GoSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = GoTextLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = pack(KEYS[tokenType])

    /** Aliases of [GoColors], where the keys are defined once for the whole plugin. */
    companion object {
        val KEYWORD = GoColors.KEYWORD
        val STRING = GoColors.STRING
        val NUMBER = GoColors.NUMBER
        val LINE_COMMENT = GoColors.LINE_COMMENT
        val BLOCK_COMMENT = GoColors.BLOCK_COMMENT
        val DIRECTIVE = GoColors.DIRECTIVE
        val BRACES = GoColors.BRACES
        val PARENTHESES = GoColors.PARENTHESES
        val BRACKETS = GoColors.BRACKETS
        val SEMICOLON = GoColors.SEMICOLON
        val COMMA = GoColors.COMMA
        val DOT = GoColors.DOT
        val OPERATOR = GoColors.OPERATOR
        val BAD_CHARACTER = GoColors.BAD_CHARACTER

        // by GoIdentifierAnnotator
        val BUILTIN_TYPE = GoColors.BUILTIN_TYPE
        val BUILTIN_CONSTANT = GoColors.BUILTIN_CONSTANT
        val BUILTIN_FUNCTION = GoColors.BUILTIN_FUNCTION
        val FUNCTION_DECLARATION = GoColors.FUNCTION_DECLARATION
        val TYPE_DECLARATION = GoColors.TYPE_DECLARATION
        val FUNCTION_CALL = GoColors.FUNCTION_CALL
        val FIELD = GoColors.FIELD
        val CONSTANT = GoColors.CONSTANT

        // by the semantic tokens of gopls (GoSemanticColors), and what of it the annotator can tell by itself
        val TYPE_REFERENCE = GoColors.TYPE_REFERENCE
        val PACKAGE = GoColors.PACKAGE
        val PARAMETER = GoColors.PARAMETER
        val LOCAL_VARIABLE = GoColors.LOCAL_VARIABLE
        val PACKAGE_VARIABLE = GoColors.PACKAGE_VARIABLE
        val LABEL = GoColors.LABEL

        private val KEYS: Map<IElementType, TextAttributesKey> = mapOf(
            GoTextTokens.KEYWORD to KEYWORD,
            GoTextTokens.STRING to STRING,
            GoTextTokens.RAW_STRING to STRING,
            GoTextTokens.CHAR to STRING,
            GoTextTokens.NUMBER to NUMBER,
            GoTextTokens.LINE_COMMENT to LINE_COMMENT,
            GoTextTokens.BLOCK_COMMENT to BLOCK_COMMENT,
            GoTextTokens.DIRECTIVE to DIRECTIVE,
            GoTextTokens.LBRACE to BRACES,
            GoTextTokens.RBRACE to BRACES,
            GoTextTokens.LPAREN to PARENTHESES,
            GoTextTokens.RPAREN to PARENTHESES,
            GoTextTokens.LBRACKET to BRACKETS,
            GoTextTokens.RBRACKET to BRACKETS,
            GoTextTokens.SEMICOLON to SEMICOLON,
            GoTextTokens.COMMA to COMMA,
            GoTextTokens.DOT to DOT,
            GoTextTokens.OPERATOR to OPERATOR,
            TokenType.BAD_CHARACTER to BAD_CHARACTER,
        )
    }
}

/**
 * The colour of a semantic token of gopls (https://go.dev/gopls/features/passive#semantic-tokens): its type, and the modifiers gopls
 * adds to the standard ones (`struct`, `interface`, `signature`, ...). Null: the token is coloured well enough by the lexer.
 */
object GoSemanticColors {
    fun key(type: String, modifiers: List<String>): TextAttributesKey? {
        val builtin = "defaultLibrary" in modifiers
        val definition = "definition" in modifiers
        return when (type) {
            "namespace" -> GoSyntaxHighlighter.PACKAGE
            "type", "typeParameter" -> if (builtin) GoSyntaxHighlighter.BUILTIN_TYPE else if (definition) GoSyntaxHighlighter.TYPE_DECLARATION else GoSyntaxHighlighter.TYPE_REFERENCE
            "function", "method" -> if (builtin) GoSyntaxHighlighter.BUILTIN_FUNCTION else if (definition) GoSyntaxHighlighter.FUNCTION_DECLARATION else GoSyntaxHighlighter.FUNCTION_CALL
            "property" -> GoSyntaxHighlighter.FIELD
            "parameter" -> GoSyntaxHighlighter.PARAMETER
            "variable" -> when {
                builtin -> GoSyntaxHighlighter.BUILTIN_CONSTANT // nil, true, false, iota
                "readonly" in modifiers -> GoSyntaxHighlighter.CONSTANT
                "static" in modifiers -> GoSyntaxHighlighter.PACKAGE_VARIABLE
                else -> GoSyntaxHighlighter.LOCAL_VARIABLE
            }
            "label" -> GoSyntaxHighlighter.LABEL
            else -> null
        }
    }

    /** What gopls adds to the token modifiers of the protocol; a client has to name the ones it wants to hear. */
    val MODIFIERS = listOf("array", "bool", "chan", "format", "interface", "map", "number", "pointer", "signature", "slice", "string", "struct", "shadowing")

    /** `gopkg.in/yaml.v3` is `yaml`, `github.com/go-chi/chi/v5` is `chi`: the name a package is used by, guessed from its path by convention. */
    fun packageName(importPath: String): String {
        val segments = importPath.split('/')
        val last = if (segments.size > 1 && VERSION.matches(segments.last())) segments[segments.size - 2] else segments.last()
        return last.substringBefore('.').removePrefix("go-")
    }

    private val VERSION = Regex("v[0-9]+")
}

class GoSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = GoSyntaxHighlighter()
}

/**
 * Colours what the lexer cannot tell apart: predeclared identifiers, the names of declarations, calls. No resolve here: a shadowed
 * `len` is still coloured as the builtin. The semantic tokens of gopls, where it runs, are laid over this.
 */
class GoIdentifierAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element.elementType != GoTextTokens.IDENTIFIER) return
        val key = classify(element) ?: return
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(element).textAttributes(key).create()
    }

    private fun classify(element: PsiElement): TextAttributesKey? {
        val declaration = element.parent as? GoDeclaration
        if (declaration != null && declaration.info?.nameRange == element.textRange) return when (declaration.kind) {
            GoDeclarationKind.FUNCTION, GoDeclarationKind.METHOD, GoDeclarationKind.INTERFACE_METHOD -> GoSyntaxHighlighter.FUNCTION_DECLARATION
            GoDeclarationKind.STRUCT, GoDeclarationKind.INTERFACE, GoDeclarationKind.TYPE -> GoSyntaxHighlighter.TYPE_DECLARATION
            GoDeclarationKind.FIELD -> GoSyntaxHighlighter.FIELD
            GoDeclarationKind.CONST -> GoSyntaxHighlighter.CONSTANT
            GoDeclarationKind.VAR -> null
        }
        val previous = PsiTreeUtil.skipWhitespacesAndCommentsBackward(element)
        val afterDot = previous.elementType == GoTextTokens.DOT
        val next = PsiTreeUtil.skipWhitespacesAndCommentsForward(element).elementType
        val isCall = next == GoTextTokens.LPAREN
        val text = element.text
        return when {
            // the names of packages are coloured here and not by gopls, which is told not to (GoplsDefaults): the clause of the file,
            // the name an import is given, and `fmt.` where the file imports something named fmt; a variable that shadows it gopls repaints
            previous?.text == "package" -> GoSyntaxHighlighter.PACKAGE
            (next == GoTextTokens.STRING || next == GoTextTokens.RAW_STRING) && isImportAlias(element) -> GoSyntaxHighlighter.PACKAGE
            !afterDot && next == GoTextTokens.DOT && text in importedNames(element) -> GoSyntaxHighlighter.PACKAGE
            !afterDot && text in GoTextTokens.BUILTIN_TYPES -> GoSyntaxHighlighter.BUILTIN_TYPE
            !afterDot && text in GoTextTokens.BUILTIN_CONSTANTS -> GoSyntaxHighlighter.BUILTIN_CONSTANT
            !afterDot && isCall && text in GoTextTokens.BUILTIN_FUNCTIONS -> GoSyntaxHighlighter.BUILTIN_FUNCTION
            isCall -> GoSyntaxHighlighter.FUNCTION_CALL
            else -> null
        }
    }
}

/** `f` of `import f "fmt"`: the import begins with it. */
private fun isImportAlias(element: PsiElement): Boolean =
    GoStructure.of(element.containingFile).imports.any { it.alias != null && it.range.startOffset == element.textRange.startOffset }

private fun importedNames(element: PsiElement): Set<String> =
    GoStructure.of(element.containingFile).imports.mapTo(HashSet()) { it.alias?.takeIf { alias -> alias != "_" && alias != "." } ?: GoSemanticColors.packageName(it.path) }

class GoColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName(): String = "Go"
    override fun getIcon(): Icon = GoIcons.File
    override fun getHighlighter(): SyntaxHighlighter = GoSyntaxHighlighter()
    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = TAGS

    override fun getDemoText(): String = """
        //go:build linux

        // Package shop sells things.
        package shop

        import "fmt"

        const <const>DefaultPort</const> = 8080

        /* A Server serves. */
        type <type>Server</type> struct {
            <field>Port</field> <bt>int</bt>
            <field>name</field> <bt>string</bt> `json:"name"`
        }

        func (s *<tref>Server</tref>) <fn>Start</fn>(<param>args</param> ...<bt>string</bt>) <bt>error</bt> {
            if <bf>len</bf>(<param>args</param>) == 0 || s == <bc>nil</bc> {
                return <pkg>fmt</pkg>.<call>Errorf</call>("no arguments: %d, %q", 0x1F, 'x')
            }
            return <bc>nil</bc>
        }
    """.trimIndent()

    private companion object {
        val DESCRIPTORS = arrayOf(
            AttributesDescriptor("Keyword", GoSyntaxHighlighter.KEYWORD),
            AttributesDescriptor("String", GoSyntaxHighlighter.STRING),
            AttributesDescriptor("Number", GoSyntaxHighlighter.NUMBER),
            AttributesDescriptor("Comments//Line comment", GoSyntaxHighlighter.LINE_COMMENT),
            AttributesDescriptor("Comments//Block comment", GoSyntaxHighlighter.BLOCK_COMMENT),
            AttributesDescriptor("Comments//Compiler directive", GoSyntaxHighlighter.DIRECTIVE),
            AttributesDescriptor("Braces and Operators//Braces", GoSyntaxHighlighter.BRACES),
            AttributesDescriptor("Braces and Operators//Parentheses", GoSyntaxHighlighter.PARENTHESES),
            AttributesDescriptor("Braces and Operators//Brackets", GoSyntaxHighlighter.BRACKETS),
            AttributesDescriptor("Braces and Operators//Semicolon", GoSyntaxHighlighter.SEMICOLON),
            AttributesDescriptor("Braces and Operators//Comma", GoSyntaxHighlighter.COMMA),
            AttributesDescriptor("Braces and Operators//Dot", GoSyntaxHighlighter.DOT),
            AttributesDescriptor("Braces and Operators//Operator", GoSyntaxHighlighter.OPERATOR),
            AttributesDescriptor("Builtins//Type", GoSyntaxHighlighter.BUILTIN_TYPE),
            AttributesDescriptor("Builtins//Constant", GoSyntaxHighlighter.BUILTIN_CONSTANT),
            AttributesDescriptor("Builtins//Function", GoSyntaxHighlighter.BUILTIN_FUNCTION),
            AttributesDescriptor("Declarations//Function", GoSyntaxHighlighter.FUNCTION_DECLARATION),
            AttributesDescriptor("Declarations//Type", GoSyntaxHighlighter.TYPE_DECLARATION),
            AttributesDescriptor("Declarations//Struct field", GoSyntaxHighlighter.FIELD),
            AttributesDescriptor("Declarations//Constant", GoSyntaxHighlighter.CONSTANT),
            AttributesDescriptor("Function call", GoSyntaxHighlighter.FUNCTION_CALL),
            AttributesDescriptor("References//Type", GoSyntaxHighlighter.TYPE_REFERENCE),
            AttributesDescriptor("References//Package", GoSyntaxHighlighter.PACKAGE),
            AttributesDescriptor("Variables//Parameter", GoSyntaxHighlighter.PARAMETER),
            AttributesDescriptor("Variables//Local variable", GoSyntaxHighlighter.LOCAL_VARIABLE),
            AttributesDescriptor("Variables//Package variable", GoSyntaxHighlighter.PACKAGE_VARIABLE),
            AttributesDescriptor("Label", GoSyntaxHighlighter.LABEL),
            AttributesDescriptor("Bad character", GoSyntaxHighlighter.BAD_CHARACTER),
        )

        val TAGS = mapOf(
            "bt" to GoSyntaxHighlighter.BUILTIN_TYPE, "bc" to GoSyntaxHighlighter.BUILTIN_CONSTANT, "bf" to GoSyntaxHighlighter.BUILTIN_FUNCTION,
            "fn" to GoSyntaxHighlighter.FUNCTION_DECLARATION, "type" to GoSyntaxHighlighter.TYPE_DECLARATION, "field" to GoSyntaxHighlighter.FIELD,
            "const" to GoSyntaxHighlighter.CONSTANT, "call" to GoSyntaxHighlighter.FUNCTION_CALL,
            "tref" to GoSyntaxHighlighter.TYPE_REFERENCE, "pkg" to GoSyntaxHighlighter.PACKAGE, "param" to GoSyntaxHighlighter.PARAMETER,
        )
    }
}
