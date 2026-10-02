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
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import io.github.golangsupport.GoIcons
import io.github.golangsupport.lang.lexer.GoLexer
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoTokenSets
import io.github.golangsupport.lang.psi.GoTypes
import javax.swing.Icon

class GoSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = GoLexer()

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

        private val KEYS: Map<IElementType, TextAttributesKey> = HashMap<IElementType, TextAttributesKey>().apply {
            GoTokenSets.KEYWORDS.types.forEach { put(it, KEYWORD) }
            GoTokenSets.OPERATORS.types.forEach { put(it, OPERATOR) }
            GoTokenSets.NUMBERS.types.forEach { put(it, NUMBER) }
            put(GoTypes.STRING, STRING)
            put(GoTypes.RAW_STRING, STRING)
            put(GoTypes.CHAR, STRING)
            put(GoTypes.LINE_COMMENT, LINE_COMMENT)
            put(GoTypes.BLOCK_COMMENT, BLOCK_COMMENT)
            put(GoTypes.LBRACE, BRACES)
            put(GoTypes.RBRACE, BRACES)
            put(GoTypes.LPAREN, PARENTHESES)
            put(GoTypes.RPAREN, PARENTHESES)
            put(GoTypes.LBRACK, BRACKETS)
            put(GoTypes.RBRACK, BRACKETS)
            put(GoTypes.SEMICOLON, SEMICOLON)
            put(GoTypes.COMMA, COMMA)
            put(GoTypes.PERIOD, DOT)
            put(TokenType.BAD_CHARACTER, BAD_CHARACTER)
        }
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
 * Colours what the lexer cannot tell apart: predeclared identifiers, the names of declarations, calls, compiler directives (the lexer has
 * no token of their own: `//go:build` is a line comment). No resolve here: a shadowed `len` is still coloured as the builtin. The semantic
 * tokens of gopls, where it runs, are laid over this. With the Built-in source of the semantic colours the identifiers are left to the
 * semantic annotator of go-psi-ide, which colours them by resolve (MIGRATION.md step 8d); the directives are coloured here in every mode,
 * no other source knows them.
 */
class GoIdentifierAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val key = when {
            element is PsiComment -> GoSyntaxHighlighter.DIRECTIVE.takeIf { isDirective(element.text) }
            element.elementType == GoTypes.IDENTIFIER -> if (coloursIdentifiers(element.project)) classify(element) else null
            else -> null
        } ?: return
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(element).textAttributes(key).create()
    }

    private fun classify(element: PsiElement): TextAttributesKey? {
        val declaration = GoDeclarationKind.ofName(element)
        if (declaration != null) return when (GoDeclarationKind.of(declaration)) {
            GoDeclarationKind.FUNCTION, GoDeclarationKind.METHOD, GoDeclarationKind.INTERFACE_METHOD -> GoSyntaxHighlighter.FUNCTION_DECLARATION
            GoDeclarationKind.STRUCT, GoDeclarationKind.INTERFACE, GoDeclarationKind.TYPE -> GoSyntaxHighlighter.TYPE_DECLARATION
            GoDeclarationKind.FIELD -> GoSyntaxHighlighter.FIELD
            GoDeclarationKind.CONST -> GoSyntaxHighlighter.CONSTANT
            GoDeclarationKind.VAR, null -> null
        }
        val previous = GoCodeLeaves.before(element)
        val afterDot = previous.elementType == GoTypes.PERIOD
        val next = GoCodeLeaves.after(element).elementType
        val isCall = next == GoTypes.LPAREN
        val text = element.text
        return when {
            // the names of packages are coloured here and not by gopls, which is told not to (GoplsDefaults): the clause of the file,
            // the name an import is given, and `fmt.` where the file imports something named fmt; a variable that shadows it gopls repaints
            previous?.text == "package" -> GoSyntaxHighlighter.PACKAGE
            (next == GoTypes.STRING || next == GoTypes.RAW_STRING) && isImportAlias(element) -> GoSyntaxHighlighter.PACKAGE
            !afterDot && next == GoTypes.PERIOD && text in importedNames(element) -> GoSyntaxHighlighter.PACKAGE
            !afterDot && text in GoNames.BUILTIN_TYPES -> GoSyntaxHighlighter.BUILTIN_TYPE
            !afterDot && text in GoNames.BUILTIN_CONSTANTS -> GoSyntaxHighlighter.BUILTIN_CONSTANT
            !afterDot && isCall && text in GoNames.BUILTIN_FUNCTIONS -> GoSyntaxHighlighter.BUILTIN_FUNCTION
            isCall -> GoSyntaxHighlighter.FUNCTION_CALL
            else -> null
        }
    }

    companion object {
        /** `//go:build`, `//go:generate`, `//line f.go:1`, `//export F`: what the toolchain reads, as gofmt and go/build tell them. */
        fun isDirective(comment: String): Boolean = comment.startsWith("//go:") || comment.startsWith("//line ") || comment.startsWith("//export ")

        /**
         * The text rules colour the identifiers while gopls is the source of the semantic colours (the tokens of the server are laid over
         * them) and while the IDE indexes (the semantic annotator of go-psi-ide is not dumb-aware); with the Built-in source in smart mode
         * that annotator colours every identifier it resolves, and these rules would paint a shadowed `len` over it.
         */
        fun coloursIdentifiers(project: Project): Boolean = !GoFeatures.native(GoFeature.SEMANTIC_COLORS, project) || DumbService.isDumb(project)
    }
}

/**
 * The neighbour tokens of a PSI leaf across the tree, skipping whitespace, comments and the semicolons the lexer inserts at line ends
 * (a [GoTypes.SEMICOLON_SYNTHETIC] is a token of the parser, not whitespace). `fmt` of `fmt.Println` is a node of its own, so the dot
 * is not its sibling: the walk goes by leaves.
 */
object GoCodeLeaves {
    fun before(element: PsiElement): PsiElement? = generateSequence(PsiTreeUtil.prevLeaf(element)) { PsiTreeUtil.prevLeaf(it) }.firstOrNull(::isCode)
    fun after(element: PsiElement): PsiElement? = generateSequence(PsiTreeUtil.nextLeaf(element)) { PsiTreeUtil.nextLeaf(it) }.firstOrNull(::isCode)
    private fun isCode(leaf: PsiElement): Boolean = leaf !is PsiWhiteSpace && leaf !is PsiComment && leaf.elementType != GoTypes.SEMICOLON_SYNTHETIC && leaf.textLength > 0
}

/** `f` of `import f "fmt"`: the name of an import spec. */
private fun isImportAlias(element: PsiElement): Boolean = (element.parent as? GoImportSpec)?.identifier == element

private fun importedNames(element: PsiElement): Set<String> =
    (element.containingFile as? GoFile)?.imports.orEmpty().mapTo(HashSet()) { it.alias?.takeIf { alias -> alias != "_" && alias != "." } ?: GoSemanticColors.packageName(it.path) }

class GoColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName(): String = "Go"
    override fun getIcon(): Icon = GoIcons.File
    override fun getHighlighter(): SyntaxHighlighter = GoSyntaxHighlighter()
    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = TAGS

    override fun getDemoText(): String = """
        <directive>//go:build linux</directive>

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
            "tref" to GoSyntaxHighlighter.TYPE_REFERENCE, "directive" to GoSyntaxHighlighter.DIRECTIVE, "pkg" to GoSyntaxHighlighter.PACKAGE, "param" to GoSyntaxHighlighter.PARAMETER,
        )
    }
}
