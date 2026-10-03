package io.github.golangsupport.lang

import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionTextElement
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoReceiver
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.scope.GoScopes
import java.awt.Color
import java.awt.Font

/**
 * Grey text in the colours of the code, muted: the suggestion is lexed by the lexer of go-psi, each token gets the key the editor
 * colours it by ([GoSyntaxHighlighter], and for identifiers what [GoIdentifierAnnotator] and the semantic colours would say, guessed
 * from the neighbours and the names in scope), and the colour of that key in the scheme is blended with the background, so that the
 * suggestion reads as code and still as something not written yet. What has no colour of its own keeps the grey of the platform.
 */
object GoInlineColors {
    /** How far the colour of a token goes towards the background: 0 is the colour of the code, 1 is invisible. */
    const val FADE = 0.5

    /** A piece of the suggestion and the key of its colour; null is the grey of the platform. */
    class Run(val text: String, val key: TextAttributesKey?) {
        override fun toString(): String = "${key?.externalName}:$text"
    }

    /** What the names of the suggestion are, where something in scope tells it: imported packages, types, parameters, locals. */
    class Names(
        val packages: Set<String> = emptySet(), val types: Set<String> = emptySet(),
        val parameters: Set<String> = emptySet(), val locals: Set<String> = emptySet(),
    ) {
        fun plus(packages: Collection<String>): Names = Names(this.packages + packages, types, parameters, locals)

        companion object {
            val NONE = Names()
        }
    }

    private val highlighter = GoSyntaxHighlighter()

    /**
     * [text] split into runs of one colour. [before] is what precedes it on its line (the start of the identifier the suggestion
     * completes: `mak` of `make(`), lexed with it and not returned. White space goes with the run before it.
     */
    fun runs(text: String, before: String = "", names: Names = Names.NONE): List<Run> {
        if (text.isEmpty()) return emptyList()
        val full = before + text
        // a suggestion that begins inside a token of [before] (a string being typed) is not lexed with it
        if (before.isNotEmpty() && GoTokens.all(before).lastOrNull()?.let { it.type == GoTypes.STRING || it.type == GoTypes.RAW_STRING || it.type == TokenType.BAD_CHARACTER } == true) {
            return runs(text, "", names)
        }
        val tokens = GoTokens.all(full).toList()
        val code = tokens.filter { isCode(it.type) }
        val keys = HashMap<GoTokens.Token, TextAttributesKey?>()
        code.forEachIndexed { i, token -> keys[token] = keyOf(full, code, i, names) }
        val pieces = ArrayList<Pair<StringBuilder, TextAttributesKey?>>()
        // white space and the semicolons the lexer inserts at line ends have no colour: they go with the token before them, and
        // the white space the suggestion starts with goes with its first token
        val lead = StringBuilder()
        for (token in tokens) {
            val start = maxOf(token.start, before.length)
            if (token.end <= start) continue
            val piece = full.substring(start, token.end)
            if (!isCode(token.type) && token.type !in COMMENTS) {
                if (pieces.isEmpty()) lead.append(piece) else pieces.last().first.append(piece)
                continue
            }
            val key = if (token.type in COMMENTS) commentKey(piece) else keys[token]
            if (pieces.isNotEmpty() && pieces.last().second == key) pieces.last().first.append(piece)
            else pieces += StringBuilder(if (pieces.isEmpty()) lead else "").append(piece) to key
        }
        if (pieces.isEmpty()) return listOf(Run(text, null))
        return pieces.map { Run(it.first.toString(), it.second) }
    }

    private val COMMENTS = setOf(GoTypes.LINE_COMMENT, GoTypes.BLOCK_COMMENT)

    private fun isCode(type: IElementType): Boolean = type != TokenType.WHITE_SPACE && type != GoTypes.SEMICOLON_SYNTHETIC && type !in COMMENTS

    private fun commentKey(text: String): TextAttributesKey =
        if (GoIdentifierAnnotator.isDirective(text)) GoSyntaxHighlighter.DIRECTIVE else if (text.startsWith("/*")) GoSyntaxHighlighter.BLOCK_COMMENT else GoSyntaxHighlighter.LINE_COMMENT

    private fun keyOf(text: String, code: List<GoTokens.Token>, i: Int, names: Names): TextAttributesKey? {
        val token = code[i]
        if (token.type != GoTypes.IDENTIFIER) return highlighter.getTokenHighlights(token.type).lastOrNull()
        return identifierKey(text, code, i, names)
    }

    /** The colour of an identifier the way the editor would have it, from its neighbours and the names in scope. */
    private fun identifierKey(text: String, code: List<GoTokens.Token>, i: Int, names: Names): TextAttributesKey {
        val name = code[i].let { text.substring(it.start, it.end) }
        val previous = code.getOrNull(i - 1)?.type
        val next = code.getOrNull(i + 1)?.type
        val call = next == GoTypes.LPAREN
        if (previous == GoTypes.PERIOD) {
            val qualifier = code.getOrNull(i - 2)?.takeIf { it.type == GoTypes.IDENTIFIER }?.let { text.substring(it.start, it.end) }
            val qualified = qualifier != null && qualifier in names.packages && code.getOrNull(i - 3)?.type != GoTypes.PERIOD
            return when {
                call -> GoSyntaxHighlighter.FUNCTION_CALL
                qualified && (next == GoTypes.LBRACE || isTypePosition(code, i - 2)) -> GoSyntaxHighlighter.TYPE_REFERENCE
                qualified -> DefaultLanguageHighlighterColors.IDENTIFIER
                else -> GoSyntaxHighlighter.FIELD
            }
        }
        return when {
            next == GoTypes.PERIOD && name in names.packages && name !in names.locals && name !in names.parameters -> GoSyntaxHighlighter.PACKAGE
            name in GoNames.BUILTIN_TYPES -> GoSyntaxHighlighter.BUILTIN_TYPE
            name in GoNames.BUILTIN_CONSTANTS -> GoSyntaxHighlighter.BUILTIN_CONSTANT
            call && name in GoNames.BUILTIN_FUNCTIONS -> GoSyntaxHighlighter.BUILTIN_FUNCTION
            name in names.types -> GoSyntaxHighlighter.TYPE_REFERENCE
            call -> GoSyntaxHighlighter.FUNCTION_CALL
            name in names.parameters -> GoSyntaxHighlighter.PARAMETER
            name in names.locals -> GoSyntaxHighlighter.LOCAL_VARIABLE
            next == GoTypes.COLON && (previous == GoTypes.LBRACE || previous == GoTypes.COMMA) && !inCase(code, i) -> GoSyntaxHighlighter.FIELD
            name[0].isUpperCase() && (next == GoTypes.LBRACE || isTypePosition(code, i)) -> GoSyntaxHighlighter.TYPE_REFERENCE
            else -> DefaultLanguageHighlighterColors.IDENTIFIER
        }
    }

    /** Whether the name at [i] stands where a type does: after `*`, `[]`, `map[K]`, `chan`, `.(`, or a name it declares (`t *T`, `x T`). */
    private fun isTypePosition(code: List<GoTokens.Token>, i: Int): Boolean {
        val previous = code.getOrNull(i - 1)?.type ?: return false
        return previous == GoTypes.MUL || previous == GoTypes.RBRACK || previous == GoTypes.CHAN || previous == GoTypes.IDENTIFIER ||
            previous == GoTypes.LPAREN && code.getOrNull(i - 2)?.type == GoTypes.PERIOD
    }

    /** Whether the token at [i] is in the list of a `case` (`case A, B:`), where a name before a colon is not a key of a literal. */
    private fun inCase(code: List<GoTokens.Token>, i: Int): Boolean {
        for (j in i - 1 downTo 0) {
            when (code[j].type) {
                GoTypes.CASE -> return true
                GoTypes.COLON, GoTypes.LBRACE, GoTypes.SEMICOLON -> return false
            }
        }
        return false
    }

    // --- colours ---

    /** [color] moved [fade] of the way towards [background]. */
    fun blend(color: Color, background: Color, fade: Double = FADE): Color {
        fun mix(a: Int, b: Int): Int = (a + (b - a) * fade).toInt().coerceIn(0, 255)
        return Color(mix(color.red, background.red), mix(color.green, background.green), mix(color.blue, background.blue))
    }

    /** The attributes of a run in [scheme]: the colour of [key] muted, italic only when the scheme has it so; the grey of the platform for null. */
    fun attributes(scheme: EditorColorsScheme, key: TextAttributesKey?): TextAttributes {
        val grey = scheme.getAttributes(DefaultLanguageHighlighterColors.INLINE_SUGGESTION)?.clone() ?: TextAttributes()
        if (key == null) return grey
        val own = scheme.getAttributes(key)
        val foreground = own?.foregroundColor ?: scheme.defaultForeground ?: return grey
        val background = scheme.defaultBackground ?: return grey
        return TextAttributes(blend(foreground, background), null, null, null, (own?.fontType ?: Font.PLAIN) and Font.ITALIC)
    }

    /** The elements the provider shows for [runs]. */
    fun elements(runs: List<Run>): List<InlineCompletionElement> =
        runs.map { run -> InlineCompletionTextElement(run.text) { editor -> attributes(editor.colorsScheme, run.key) } }

    // --- the names in scope ---

    /**
     * What is in scope at [offset] of [document] of [file], for the colours of the identifiers. The suggestion is asked before the
     * document is committed: the PSI is read only while the text up to the offset is the same. Call under a read action.
     */
    fun namesAt(file: GoFile, document: Document, offset: Int): Names = runCatching {
        val packages = file.imports.filter { !it.isBlank && !it.isDot }.mapTo(HashSet()) { GoScopes.importName(it) }
        val types = file.types.mapNotNullTo(HashSet()) { it.name }
        val documents = PsiDocumentManager.getInstance(file.project)
        val committed = documents.getLastCommittedText(document)
        if (file.textLength != committed.length) return@runCatching Names(packages, types)
        val same = if (documents.isCommitted(document)) document.textLength else StringUtil.commonPrefixLength(committed, document.immutableCharSequence)
        val at = minOf(offset, same) - 1
        val leaf = if (at < 0) null else file.findElementAt(at)
        if (leaf == null || GoPsiUtil.functionOwner(leaf) == null) return@runCatching Names(packages, types)
        val variables = GoReturnValues.localVariables(leaf)
        val parameters = variables.filter { it is GoParamDefinition || it is GoReceiver }.mapNotNullTo(HashSet()) { it.name }
        val locals = variables.filter { it !is GoParamDefinition && it !is GoReceiver }.mapNotNullTo(HashSet()) { it.name }
        Names(packages, types, parameters, locals)
    }.getOrElse { if (it is com.intellij.openapi.progress.ProcessCanceledException) throw it else Names.NONE }
}
