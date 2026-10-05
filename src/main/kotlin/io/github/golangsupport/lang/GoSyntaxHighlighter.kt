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
import com.intellij.openapi.util.TextRange
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
            put(GoTypes.LBRACK, GoColors.BRACKET)
            put(GoTypes.RBRACK, GoColors.BRACKET)
            put(GoTypes.COLON, GoColors.COLON)
            put(GoTypes.IDENTIFIER, GoColors.IDENTIFIER)
            put(GoTypes.SEMICOLON, SEMICOLON)
            put(GoTypes.COMMA, COMMA)
            put(GoTypes.PERIOD, DOT)
            put(TokenType.BAD_CHARACTER, GoColors.BAD_TOKEN)
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
 * no other source knows them: the name as a comment keyword, the build expression by parts ([directiveRanges]).
 */
class GoIdentifierAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element is PsiComment) {
            val start = element.textRange.startOffset
            for ((range, key) in directiveRanges(element.text)) {
                holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(range.shiftRight(start)).textAttributes(key).create()
            }
            return
        }
        if (element.elementType != GoTypes.IDENTIFIER || !coloursIdentifiers(element.project)) return
        val key = classify(element) ?: return
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
         * The parts of a directive comment, as GoLand colours them: the name (`go:generate`, `line`, `export`) is a comment keyword and
         * its arguments the directive; the expression of `//go:build` and of `// +build` is split into tags, parentheses and operators.
         * Ranges are relative to the comment; empty for other comments.
         */
        fun directiveRanges(comment: String): List<Pair<TextRange, TextAttributesKey>> {
            val plusBuild = PLUS_BUILD.find(comment)
            if (plusBuild != null) return buildExpression(comment, plusBuild.range.last + 1)
            if (!isDirective(comment)) return emptyList()
            var nameEnd = 2
            while (nameEnd < comment.length && !comment[nameEnd].isWhitespace()) nameEnd++
            val out = arrayListOf(TextRange(2, nameEnd) to GoColors.COMMENT_KEYWORD)
            if (comment.startsWith("//go:build") && nameEnd == 10) return out + buildExpression(comment, nameEnd)
            val argsStart = (nameEnd until comment.length).firstOrNull { !comment[it].isWhitespace() } ?: return out
            out += TextRange(argsStart, comment.trimEnd().length) to GoSyntaxHighlighter.DIRECTIVE
            return out
        }

        private val PLUS_BUILD = Regex("""^//\s*\+build(?=\s)""")

        /** Tags (`linux`, `go1.21`), `(` `)` and `&&` `||` `!` `,` of a build constraint expression starting at [from]. */
        private fun buildExpression(text: String, from: Int): List<Pair<TextRange, TextAttributesKey>> {
            val out = ArrayList<Pair<TextRange, TextAttributesKey>>()
            var i = from
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '(' || c == ')' -> { out += TextRange(i, i + 1) to GoColors.BUILD_PAREN; i++ }
                    c == '!' || c == ',' -> { out += TextRange(i, i + 1) to GoColors.BUILD_OPERATOR; i++ }
                    (c == '&' || c == '|') && text.getOrNull(i + 1) == c -> { out += TextRange(i, i + 2) to GoColors.BUILD_OPERATOR; i += 2 }
                    c.isLetterOrDigit() || c == '_' || c == '.' -> {
                        val start = i
                        while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '.')) i++
                        out += TextRange(start, i) to GoColors.BUILD_TAG
                    }
                    else -> i++
                }
            }
            return out
        }

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

/**
 * Settings | Editor | Color Scheme | Go: the keys of GoLand in GoLand's groups and names (`docs/goland-analysis/dumps/color-keys-go.txt`,
 * pinned by `GoColorSettingsPageTest`), then the base keys they inherit from (what the text rules, gopls and older schemes colour with).
 * The annotators do not run on the demo, so every semantic key has a tag of its own.
 */
class GoColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName(): String = "Go"
    override fun getIcon(): Icon = GoIcons.File
    override fun getHighlighter(): SyntaxHighlighter = GoSyntaxHighlighter()
    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = TAGS

    override fun getDemoText(): String = DEMO

    companion object {
        private fun d(name: String, key: TextAttributesKey) = AttributesDescriptor(name, key)

        val DESCRIPTORS = arrayOf(
            d("Keyword", GoColors.KEYWORD),
            d("Identifier", GoColors.IDENTIFIER),
            d("Number", GoColors.NUMBER),
            d("Bad character", GoColors.BAD_TOKEN),
            d("String//Text", GoColors.STRING),
            d("String//Valid escape", GoColors.VALID_STRING_ESCAPE),
            d("String//Invalid escape", GoColors.INVALID_STRING_ESCAPE),
            d("String//Format verb", GoColors.FORMAT_VERB),
            d("Comments//Line comment", GoColors.LINE_COMMENT),
            d("Comments//Block comment", GoColors.BLOCK_COMMENT),
            d("Comments//Comment keyword", GoColors.COMMENT_KEYWORD),
            d("Comments//Comment reference", GoColors.COMMENT_REFERENCE),
            d("Comments//Build constraints//Tag", GoColors.BUILD_TAG),
            d("Comments//Build constraints//Parentheses", GoColors.BUILD_PAREN),
            d("Comments//Build constraints//Operators", GoColors.BUILD_OPERATOR),
            d("Braces and operators//Semicolon", GoColors.SEMICOLON),
            d("Braces and operators//Colon", GoColors.COLON),
            d("Braces and operators//Comma", GoColors.COMMA),
            d("Braces and operators//Dot", GoColors.DOT),
            d("Braces and operators//Operator", GoColors.OPERATOR),
            d("Braces and operators//Brackets", GoColors.BRACKET),
            d("Braces and operators//Braces", GoColors.BRACES),
            d("Braces and operators//Parentheses", GoColors.PARENTHESES),
            d("References//Type references//Builtin type reference", GoColors.BUILTIN_TYPE_REFERENCE),
            d("References//Type references//Type specification", GoColors.TYPE_REFERENCE),
            d("References//Type references//Package exported interface", GoColors.EXPORTED_INTERFACE_REFERENCE),
            d("References//Type references//Package exported struct", GoColors.EXPORTED_STRUCT_REFERENCE),
            d("References//Type references//Package local interface", GoColors.LOCAL_INTERFACE_REFERENCE),
            d("References//Type references//Package local struct", GoColors.LOCAL_STRUCT_REFERENCE),
            d("References//Function calls//Builtin function call", GoColors.BUILTIN_FUNCTION_CALL),
            d("References//Function calls//Exported function call", GoColors.EXPORTED_FUNCTION_CALL),
            d("References//Function calls//Local function call", GoColors.LOCAL_FUNCTION_CALL),
            d("References//Variable calls//Exported variable call", GoColors.PACKAGE_EXPORTED_VARIABLE_CALL),
            d("References//Variable calls//Package local variable call", GoColors.PACKAGE_LOCAL_VARIABLE_CALL),
            d("References//Variable calls//Local variable call", GoColors.LOCAL_VARIABLE_CALL),
            d("References//Variable calls//Struct local member call", GoColors.STRUCT_LOCAL_MEMBER_CALL),
            d("References//Variable calls//Struct exported member call", GoColors.STRUCT_EXPORTED_MEMBER_CALL),
            d("Declarations//Package", GoColors.PACKAGE),
            d("Declarations//Method receiver", GoColors.METHOD_RECEIVER),
            d("Declarations//Function parameter", GoColors.FUNCTION_PARAMETER),
            d("Declarations//Label", GoColors.LABEL),
            d("Declarations//Types//Type specification", GoColors.TYPE_SPECIFICATION),
            d("Declarations//Types//Package exported interface", GoColors.PACKAGE_EXPORTED_INTERFACE),
            d("Declarations//Types//Package exported struct", GoColors.PACKAGE_EXPORTED_STRUCT),
            d("Declarations//Types//Package local interface", GoColors.PACKAGE_LOCAL_INTERFACE),
            d("Declarations//Types//Package local struct", GoColors.PACKAGE_LOCAL_STRUCT),
            d("Declarations//Struct tags//Key", GoColors.TAG_KEY),
            d("Declarations//Struct tags//Colon", GoColors.TAG_COLON),
            d("Declarations//Struct tags//Value", GoColors.TAG_VALUE),
            d("Declarations//Struct tags//Arbitrary text", GoColors.TAG_TEXT),
            d("Declarations//Constants//Builtin constant", GoColors.BUILTIN_CONSTANT),
            d("Declarations//Constants//Package exported constant", GoColors.PACKAGE_EXPORTED_CONSTANT),
            d("Declarations//Constants//Package local constant", GoColors.PACKAGE_LOCAL_CONSTANT),
            d("Declarations//Constants//Local constant", GoColors.LOCAL_CONSTANT),
            d("Declarations//Variables//Builtin variable", GoColors.BUILTIN_VARIABLE),
            d("Declarations//Variables//Package exported variable", GoColors.PACKAGE_EXPORTED_VARIABLE),
            d("Declarations//Variables//Package local variable", GoColors.PACKAGE_LOCAL_VARIABLE),
            d("Declarations//Variables//Local variable", GoColors.LOCAL_VARIABLE),
            d("Declarations//Variables//Scope declared variable", GoColors.SCOPE_VARIABLE),
            d("Declarations//Variables//Reassignment in short variable declaration", GoColors.REASSIGNMENT_IN_SHORT_VAR_DECLARATION),
            d("Declarations//Variables//Shadowing variable", GoColors.SHADOWING_VARIABLE),
            d("Declarations//Variables//Struct local member", GoColors.STRUCT_LOCAL_MEMBER),
            d("Declarations//Variables//Struct exported member", GoColors.STRUCT_EXPORTED_MEMBER),
            d("Declarations//Functions//Builtin function", GoColors.BUILTIN_FUNCTION),
            d("Declarations//Functions//Exported function", GoColors.EXPORTED_FUNCTION),
            d("Declarations//Functions//Package local function", GoColors.LOCAL_FUNCTION),
            // the keys the finer ones above inherit from: a scheme tuned before them keeps its look, and the text rules and gopls use them
            d("Base colors//Compiler directive", GoColors.DIRECTIVE),
            d("Base colors//Builtin type", GoColors.BUILTIN_TYPE),
            d("Base colors//Function declaration", GoColors.FUNCTION_DECLARATION),
            d("Base colors//Function call", GoColors.FUNCTION_CALL),
            d("Base colors//Type declaration", GoColors.TYPE_DECLARATION),
            d("Base colors//Struct field", GoColors.FIELD),
            d("Base colors//Constant", GoColors.CONSTANT),
            d("Base colors//Parameter", GoColors.PARAMETER),
            d("Base colors//Package variable", GoColors.PACKAGE_VARIABLE),
            d("Base colors//Brackets", GoColors.BRACKETS),
            d("Base colors//Bad character", GoColors.BAD_CHARACTER),
        )

        val TAGS: Map<String, TextAttributesKey> = mapOf(
            "goBuildParens" to GoColors.BUILD_PAREN, "goBuildTag" to GoColors.BUILD_TAG, "goBuildOperator" to GoColors.BUILD_OPERATOR,
            "package" to GoColors.PACKAGE, "goGenerate" to GoColors.COMMENT_KEYWORD, "directive" to GoColors.DIRECTIVE, "goCommentRef" to GoColors.COMMENT_REFERENCE,
            "pei" to GoColors.PACKAGE_EXPORTED_INTERFACE, "pli" to GoColors.PACKAGE_LOCAL_INTERFACE, "pes" to GoColors.PACKAGE_EXPORTED_STRUCT,
            "pls" to GoColors.PACKAGE_LOCAL_STRUCT, "ts" to GoColors.TYPE_SPECIFICATION,
            "ef" to GoColors.EXPORTED_FUNCTION, "lf" to GoColors.LOCAL_FUNCTION, "bt" to GoColors.BUILTIN_TYPE_REFERENCE,
            "sem" to GoColors.STRUCT_EXPORTED_MEMBER, "slm" to GoColors.STRUCT_LOCAL_MEMBER,
            "goTagKey" to GoColors.TAG_KEY, "goTagColon" to GoColors.TAG_COLON, "goTagValue" to GoColors.TAG_VALUE, "goTagText" to GoColors.TAG_TEXT,
            "pec" to GoColors.PACKAGE_EXPORTED_CONSTANT, "plc" to GoColors.PACKAGE_LOCAL_CONSTANT, "lc" to GoColors.LOCAL_CONSTANT, "bc" to GoColors.BUILTIN_CONSTANT,
            "pev" to GoColors.PACKAGE_EXPORTED_VARIABLE, "plv" to GoColors.PACKAGE_LOCAL_VARIABLE, "lv" to GoColors.LOCAL_VARIABLE, "bv" to GoColors.BUILTIN_VARIABLE,
            "sv" to GoColors.SCOPE_VARIABLE, "re_in_svd" to GoColors.REASSIGNMENT_IN_SHORT_VAR_DECLARATION, "shv" to GoColors.SHADOWING_VARIABLE,
            "mr" to GoColors.METHOD_RECEIVER, "fp" to GoColors.FUNCTION_PARAMETER, "ll" to GoColors.LABEL,
            "esr" to GoColors.EXPORTED_STRUCT_REFERENCE, "lsr" to GoColors.LOCAL_STRUCT_REFERENCE, "eir" to GoColors.EXPORTED_INTERFACE_REFERENCE,
            "lir" to GoColors.LOCAL_INTERFACE_REFERENCE, "tsr" to GoColors.TYPE_REFERENCE,
            "ef_call" to GoColors.EXPORTED_FUNCTION_CALL, "lf_call" to GoColors.LOCAL_FUNCTION_CALL, "bf_call" to GoColors.BUILTIN_FUNCTION_CALL,
            "lv_call" to GoColors.LOCAL_VARIABLE_CALL, "pev_call" to GoColors.PACKAGE_EXPORTED_VARIABLE_CALL, "plv_call" to GoColors.PACKAGE_LOCAL_VARIABLE_CALL,
            "sem_call" to GoColors.STRUCT_EXPORTED_MEMBER_CALL, "slm_call" to GoColors.STRUCT_LOCAL_MEMBER_CALL,
            "se_valid" to GoColors.VALID_STRING_ESCAPE, "se_invalid" to GoColors.INVALID_STRING_ESCAPE, "fv" to GoColors.FORMAT_VERB,
        )

        /** GoLand's demo (`color-keys-go.txt`) with its tags, without the rainbow block, plus a directive's arguments and printf verbs. */
        val DEMO = """
            /*
             * Go highlight sample
             */
            //<goGenerate>go:build</goGenerate> <goBuildParens>(</goBuildParens><goBuildTag>linux</goBuildTag> <goBuildOperator>||</goBuildOperator> <goBuildTag>windows</goBuildTag><goBuildParens>)</goBuildParens> <goBuildOperator>&&</goBuildOperator> <goBuildTag>arm</goBuildTag>
            // +build <goBuildTag>linux</goBuildTag><goBuildOperator>,</goBuildOperator><goBuildTag>arm</goBuildTag> <goBuildTag>windows</goBuildTag><goBuildOperator>,</goBuildOperator><goBuildTag>arm</goBuildTag>

            // Package main
            package <package>main</package>

            import "fmt"
            import <package>alias</package> "fmt"

            //<goGenerate>go:generate</goGenerate> <directive>go tool yacc -o gopher.go -p parser gopher.y</directive>

            type (
            	<pei>PublicInterface</pei> interface {
            		<ef>PublicFunc</ef>() <bt>int</bt>
            		<lf>privateFunc</lf>() <bt>int</bt>
            	}

            	<pli>privateInterface</pli> interface {
            		<ef>PublicFunc</ef>() <bt>int</bt>
            		<lf>privateFunc</lf>() <bt>int</bt>
            	}

            	<pes>PublicStruct</pes> struct {
            		<sem>PublicField</sem>  <bt>int</bt>
            		<slm>privateField</slm> <bt>int</bt>
            	}

            	<pls>privateStruct</pls> struct {
            		<sem>PublicField</sem>  <bt>int</bt>
            		<slm>privateField</slm> <bt>int</bt>
            	}

            	<ts>demoInt</ts> <bt>int</bt>

            	<pes>T</pes> struct {
            		<sem>FirstName</sem> <bt>string</bt> `<goTagKey>json</goTagKey><goTagColon>:</goTagColon><goTagValue>"first_name"</goTagValue><goTagText> arbitrary text</goTagText>`
            	}
            )

            const (
            	<pec>PublicConst</pec>  = 1
            	<plc>privateConst</plc> = 2
            )

            var (
            	<pev>PublicVar</pev>  = 1
            	<plv>privateVar</plv> = 2
            )

            // <goCommentRef>PublicFunc</goCommentRef> does the thing
            func <ef>PublicFunc</ef>() <bt>int</bt> {
            	<lv>localVar</lv> := <pev>PublicVar</pev>
            	return <lv>localVar</lv>
            }

            // <goCommentRef>privateFunc</goCommentRef> does the thing
            func <lf>privateFunc</lf>() (<bt>int</bt>, <bt>int</bt>) {
            	<lv>LocalVar</lv> := <plv>privateVar</plv>
            	return <lv>LocalVar</lv>, <pev>PublicVar</pev>
            }

            func (<mr>ps</mr> <esr>PublicStruct</esr>) <ef>PublicFunc</ef>() <bt>int</bt> {
            	return <mr>ps</mr>.<slm>privateField</slm>
            }

            func (<mr>ps</mr> <lsr>privateStruct</lsr>) <lf>privateFunc</lf>() <bt>int</bt> {
            	return <mr>ps</mr>.<sem>PublicField</sem>
            }

            func _(<fp>pi</fp> <eir>PublicInterface</eir>) {
            }

            func _(<fp>pi</fp> <lir>privateInterface</lir>) {
            }

            func <lf>variableFunc</lf>(<fp>demo1</fp> <bt>int</bt>, <fp>demo2</fp> <tsr>demoInt</tsr>) {
            	<fp>demo1</fp> = 3
            	<lv>a</lv> := <esr>PublicStruct</esr>{}
            	<lv>a</lv>.<ef_call>PublicFunc</ef_call>()
            	<lv>b</lv> := <lsr>privateStruct</lsr>{}
            	<lv>b</lv>.<lf_call>privateFunc</lf_call>()
            	<fp>demo2</fp> = 4
            	if <sv>demo1</sv>, <sv>demo2</sv> := <lf_call>privateFunc</lf_call>(); <sv>demo1</sv> != 3 {
            		_ = <sv>demo1</sv>
            		_ = <sv>demo2</sv>
            		return
            	}
            <ll>demoLabel</ll>:
            	for <sv>demo1</sv> := range []<bt>int</bt>{1, 2, 3, 4} {
            		_ = <sv>demo1</sv>
            		continue <ll>demoLabel</ll>
            	}

            	switch {
            	case 1 == 2:
            		<sv>demo1</sv>, <sv>demo2</sv> := <lf_call>privateFunc</lf_call>()
            		_ = <sv>demo1</sv>
            		_ = <sv>demo2</sv>
            	default:
            		_ = <fp>demo1</fp>
            	}

            	<lv>f</lv> := func() <bt>int</bt> {
            		return 1
            	}
            	<lv_call>f</lv_call>()
            	<ef_call>PublicFunc</ef_call>()
            	<lf_call>variableFunc</lf_call>(1, 2)
            	_ = <fp>demo1</fp>
            	_ = <fp>demo2</fp>
            	<bf_call>println</bf_call>("builtin function")
            }

            func <lf>main</lf>() {
            	const <lc>LocalConst</lc> = 1
            	const <lc>localConst</lc> = 2
            	<package>fmt</package>.<ef_call>Println</ef_call>("demo<se_valid>\n</se_valid><se_invalid>\xA</se_invalid>")
            	<package>fmt</package>.<ef_call>Printf</ef_call>("<fv>%-10s</fv> <fv>%[1]d</fv> <fv>%%</fv><se_valid>\n</se_valid>", "demo")
            	<package>alias</package>.<ef_call>Println</ef_call>("demo")
            	<lf_call>variableFunc</lf_call>(1, 2)
            	var <lv>d</lv>, <lv>c</lv> *<bt>int</bt> = <bv>nil</bv>, <bv>nil</bv>
            	_, _ = <lv>c</lv>, <lv>d</lv>
            	_, _ = <bc>true</bc>, <bc>false</bc>
            }

            var <pev>ExportedVariableFunction</pev> = func() {}
            var <plv>packageLocalVariableFunction</plv> = func() {}

            type <pls>typeWithCall</pls> struct {
            	<sem>PublicFieldCall</sem>  func()
            	<slm>privateFieldCall</slm> func()
            }

            func <lf>calls</lf>(<fp>t</fp> <lsr>typeWithCall</lsr>) {
            	var <lv>localVariableFunction</lv> = func() {}

            	<pev_call>ExportedVariableFunction</pev_call>()
            	<plv_call>packageLocalVariableFunction</plv_call>()
            	<lv_call>localVariableFunction</lv_call>()
            	<fp>t</fp>.<sem_call>PublicFieldCall</sem_call>()
            	<fp>t</fp>.<slm_call>privateFieldCall</slm_call>()
            }

            func _() {
            	var <lv>err</lv> <bt>error</bt>
            	<lv>a</lv>, <re_in_svd>err</re_in_svd> := 1, <bv>nil</bv>
            	<bf_call>println</bf_call>(<lv>a</lv>, <lv>err</lv>)

            	for <shv>a</shv> := 0; <shv>a</shv> < 10; <shv>a</shv>++ {
            		<bf_call>println</bf_call>(<shv>a</shv>)
            	}
            }
        """.trimIndent()
    }
}
