package io.github.golangsupport.mod

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.Commenter
import com.intellij.lang.Language
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.lexer.LexerBase
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Default
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import io.github.golangsupport.GoIcons
import javax.swing.Icon

/** go.mod and go.work: one syntax, a directive per line or a block of lines in parentheses. */
object GoModLanguage : Language("GoModule")

object GoModFileType : LanguageFileType(GoModLanguage) {
    override fun getName(): String = "Go Module"
    override fun getDisplayName(): String = "Go module"
    override fun getDescription(): String = "Go module (go.mod) or workspace (go.work) file"
    override fun getDefaultExtension(): String = "mod"
    override fun getIcon(): Icon = GoIcons.Module

    const val GO_MOD = "go.mod"
    const val GO_WORK = "go.work"
}

class GoModPsiFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, GoModLanguage) {
    override fun getFileType(): FileType = GoModFileType
    override fun toString(): String = "Go Module File"
}

class GoModTokenType(debugName: String) : IElementType(debugName, GoModLanguage)

object GoModTokenTypes {
    @JvmField val COMMENT = GoModTokenType("COMMENT")
    @JvmField val DIRECTIVE = GoModTokenType("DIRECTIVE")
    @JvmField val VERSION = GoModTokenType("VERSION")
    @JvmField val STRING = GoModTokenType("STRING")
    @JvmField val ARROW = GoModTokenType("ARROW")
    @JvmField val LPAREN = GoModTokenType("LPAREN")
    @JvmField val RPAREN = GoModTokenType("RPAREN")

    /** A module path, a directory, a Go version. */
    @JvmField val WORD = GoModTokenType("WORD")
}

/** A word is a directive when it is the first one on its line; inside a block the first word is an argument, which no directive is named like. */
class GoModLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var bufferEnd = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        bufferEnd = endOffset
        tokenStart = startOffset
        tokenEnd = startOffset
        advance()
    }

    override fun getState(): Int = 0
    override fun getTokenType(): IElementType? = tokenType
    override fun getTokenStart(): Int = tokenStart
    override fun getTokenEnd(): Int = tokenEnd
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = bufferEnd

    override fun advance() {
        tokenStart = tokenEnd
        if (tokenStart >= bufferEnd) {
            tokenType = null
            return
        }
        val start = tokenStart
        val c = buffer[start]
        when {
            c.isWhitespace() -> token(TokenType.WHITE_SPACE, skipWhile(start) { it.isWhitespace() })
            c == '/' && charAt(start + 1) == '/' -> token(GoModTokenTypes.COMMENT, skipWhile(start) { it != '\n' && it != '\r' })
            c == '(' -> token(GoModTokenTypes.LPAREN, start + 1)
            c == ')' -> token(GoModTokenTypes.RPAREN, start + 1)
            c == '=' && charAt(start + 1) == '>' -> token(GoModTokenTypes.ARROW, start + 2)
            c == '"' || c == '`' -> {
                val close = skipWhile(start + 1) { it != c && it != '\n' }
                token(GoModTokenTypes.STRING, if (charAt(close) == c) close + 1 else close)
            }
            else -> {
                val end = skipWhile(start) { !it.isWhitespace() && it != '(' && it != ')' }
                val word = buffer.subSequence(start, end).toString()
                token(
                    when {
                        word in GoModFile.DIRECTIVES && isFirstOnLine(start) -> GoModTokenTypes.DIRECTIVE
                        VERSION.matches(word) -> GoModTokenTypes.VERSION
                        else -> GoModTokenTypes.WORD
                    },
                    end,
                )
            }
        }
    }

    private fun token(type: IElementType, end: Int) {
        tokenType = type
        tokenEnd = end.coerceIn(tokenStart + 1, bufferEnd)
    }

    private fun charAt(offset: Int): Char = if (offset < bufferEnd) buffer[offset] else '\u0000'

    private inline fun skipWhile(from: Int, predicate: (Char) -> Boolean): Int {
        var i = from
        while (i < bufferEnd && predicate(buffer[i])) i++
        return i
    }

    private fun isFirstOnLine(offset: Int): Boolean {
        var i = offset - 1
        while (i >= 0 && buffer[i] != '\n') if (!buffer[i--].isWhitespace()) return false
        return true
    }

    private companion object {
        /** `v1.2.3`, `v0.0.0-20240101000000-abcdef123456`, `v2.0.0+incompatible`, and the `1.24` / `go1.24.7` of `go` and `toolchain`. */
        val VERSION = Regex("""v\d+\.\d+\.\d+[-+.\w]*|\d+\.\d+(\.\d+)?(rc\d+|beta\d+)?|go\d+\.\d+[.\w]*""")
    }
}

class GoModParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = GoModLexer()

    /** Flat: the structure is read by [GoModFile], by lines. */
    override fun createParser(project: Project?): PsiParser = PsiParser { root, builder ->
        val file = builder.mark()
        while (!builder.eof()) builder.advanceLexer()
        file.done(root)
        builder.treeBuilt
    }

    override fun getFileNodeType(): IFileElementType = FILE
    override fun getCommentTokens(): TokenSet = COMMENTS
    override fun getStringLiteralElements(): TokenSet = STRINGS
    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = GoModPsiFile(viewProvider)

    private companion object {
        val FILE = IFileElementType(GoModLanguage)
        val COMMENTS = TokenSet.create(GoModTokenTypes.COMMENT)
        val STRINGS = TokenSet.create(GoModTokenTypes.STRING)
    }
}

class GoModSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = GoModLexer()
    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = pack(KEYS[tokenType])

    companion object {
        val DIRECTIVE = createTextAttributesKey("GO_MOD_DIRECTIVE", Default.KEYWORD)
        val VERSION = createTextAttributesKey("GO_MOD_VERSION", Default.NUMBER)
        val STRING = createTextAttributesKey("GO_MOD_STRING", Default.STRING)
        val COMMENT = createTextAttributesKey("GO_MOD_COMMENT", Default.LINE_COMMENT)
        val ARROW = createTextAttributesKey("GO_MOD_ARROW", Default.OPERATION_SIGN)
        val PARENTHESES = createTextAttributesKey("GO_MOD_PARENTHESES", Default.PARENTHESES)

        private val KEYS: Map<IElementType, TextAttributesKey> = mapOf(
            GoModTokenTypes.DIRECTIVE to DIRECTIVE,
            GoModTokenTypes.VERSION to VERSION,
            GoModTokenTypes.STRING to STRING,
            GoModTokenTypes.COMMENT to COMMENT,
            GoModTokenTypes.ARROW to ARROW,
            GoModTokenTypes.LPAREN to PARENTHESES,
            GoModTokenTypes.RPAREN to PARENTHESES,
        )
    }
}

class GoModSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = GoModSyntaxHighlighter()
}

class GoModCommenter : Commenter {
    override fun getLineCommentPrefix(): String = "//"
    override fun getBlockCommentPrefix(): String? = null
    override fun getBlockCommentSuffix(): String? = null
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}
