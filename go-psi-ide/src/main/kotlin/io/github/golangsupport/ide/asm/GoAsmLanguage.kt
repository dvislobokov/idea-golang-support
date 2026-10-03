package io.github.golangsupport.ide.asm

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.icons.AllIcons
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.impl.FileTypeOverrider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import javax.swing.Icon

/** Go assembly (Plan 9 syntax of `cmd/asm`): the `.s` files next to the `.go` files of a package. */
object GoAsmLanguage : Language("GoAsm") {
    override fun getDisplayName(): String = "Go Assembly"
}

/**
 * The file type is registered without extensions: `*.s` is a generic name (GNU as, CLion's Assembly, Rider's native support), so
 * [GoAsmFileTypeOverrider] claims only the `.s` files of a directory that also holds `.go` files and leaves every other `.s` alone.
 */
object GoAsmFileType : LanguageFileType(GoAsmLanguage) {
    override fun getName(): String = "Go Assembly"
    override fun getDescription(): String = "Go assembly (Plan 9 syntax)"
    override fun getDefaultExtension(): String = "s"
    override fun getIcon(): Icon = AllIcons.FileTypes.Text
}

/** `x.s` in a directory with a `.go` file is Go assembly whatever else claims `*.s`; VFS only (no PSI, no indices), as overriders must be. */
class GoAsmFileTypeOverrider : FileTypeOverrider {
    override fun getOverriddenFileType(file: VirtualFile): FileType? {
        if (file.isDirectory || !file.nameSequence.endsWith(".s")) return null
        val dir = file.parent?.takeIf { it.isValid } ?: return null // a directory deleted under an indexing pass
        return if (dir.children.any { !it.isDirectory && it.nameSequence.endsWith(".go") }) GoAsmFileType else null
    }
}

class GoAsmTokenType(debugName: String) : IElementType(debugName, GoAsmLanguage)

object GoAsmTokenTypes {
    @JvmField val LINE_COMMENT = GoAsmTokenType("LINE_COMMENT")
    @JvmField val BLOCK_COMMENT = GoAsmTokenType("BLOCK_COMMENT")
    /** `#include`, `#define`, `#ifdef`…: the `#word` only; the rest of the line is lexed as code (macro bodies get colours too). */
    @JvmField val PREPROCESSOR = GoAsmTokenType("PREPROCESSOR")
    @JvmField val STRING = GoAsmTokenType("STRING")
    /** `$0x10`, `$-8`, `42`, `1.5e3`: the `$` of an immediate belongs to the number. */
    @JvmField val NUMBER = GoAsmTokenType("NUMBER")
    /** TEXT, DATA, GLOBL, FUNCDATA, PCDATA in the instruction position. */
    @JvmField val DIRECTIVE = GoAsmTokenType("DIRECTIVE")
    @JvmField val INSTRUCTION = GoAsmTokenType("INSTRUCTION")
    /** `name` of `name:` at the start of a statement. */
    @JvmField val LABEL = GoAsmTokenType("LABEL")
    /** A name with a middle dot or a division slash: `·add`, `runtime·morestack`, `internal∕bytealg·IndexString`. */
    @JvmField val SYMBOL = GoAsmTokenType("SYMBOL")
    @JvmField val IDENTIFIER = GoAsmTokenType("IDENTIFIER")
    @JvmField val REGISTER = GoAsmTokenType("REGISTER")
    /** SB, FP, SP, PC. */
    @JvmField val PSEUDO_REGISTER = GoAsmTokenType("PSEUDO_REGISTER")
    /** `<>` (file-local symbol) or `<ABIInternal>` right after a symbol. */
    @JvmField val ABI_SUFFIX = GoAsmTokenType("ABI_SUFFIX")
    @JvmField val LPAREN = GoAsmTokenType("(")
    @JvmField val RPAREN = GoAsmTokenType(")")
    @JvmField val LBRACKET = GoAsmTokenType("[")
    @JvmField val RBRACKET = GoAsmTokenType("]")
    @JvmField val COMMA = GoAsmTokenType(",")
    @JvmField val SEMICOLON = GoAsmTokenType(";")
    @JvmField val COLON = GoAsmTokenType(":")
    @JvmField val OPERATOR = GoAsmTokenType("OPERATOR")

    @JvmField val COMMENTS = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT)
    @JvmField val STRINGS = TokenSet.create(STRING)

    /** The composite wrapping a [SYMBOL] token: the only structure of the flat PSI. */
    @JvmField val SYMBOL_ELEMENT = IElementType("ASM_SYMBOL", GoAsmLanguage)
}

class GoAsmFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, GoAsmLanguage) {
    override fun getFileType(): FileType = GoAsmFileType
    override fun toString(): String = "Go assembly file"

    val symbols: Collection<GoAsmSymbol> get() = PsiTreeUtil.findChildrenOfType(this, GoAsmSymbol::class.java)

    /** The `TEXT` symbols of the file: the functions it implements. */
    val textSymbols: List<GoAsmSymbol> get() = symbols.filter { it.isTextDefinition }
}

/** Flat parser: every token stays a leaf of the file, a [GoAsmTokenTypes.SYMBOL] gets wrapped into a [GoAsmSymbol]. */
class GoAsmParser : PsiParser {
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val file = builder.mark()
        while (!builder.eof()) {
            if (builder.tokenType === GoAsmTokenTypes.SYMBOL) {
                val m = builder.mark()
                builder.advanceLexer()
                m.done(GoAsmTokenTypes.SYMBOL_ELEMENT)
            } else builder.advanceLexer()
        }
        file.done(root)
        return builder.treeBuilt
    }
}

class GoAsmParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = GoAsmLexer()
    override fun createParser(project: Project?): PsiParser = GoAsmParser()
    override fun getFileNodeType(): IFileElementType = FILE
    override fun getCommentTokens(): TokenSet = GoAsmTokenTypes.COMMENTS
    override fun getStringLiteralElements(): TokenSet = GoAsmTokenTypes.STRINGS
    override fun createElement(node: ASTNode): PsiElement = GoAsmSymbol(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = GoAsmFile(viewProvider)

    companion object {
        @JvmField val FILE = IFileElementType("GO_ASM_FILE", GoAsmLanguage)
    }
}
