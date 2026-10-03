package io.github.golangsupport.ide.asm

import com.intellij.lang.Commenter
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Default
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import io.github.golangsupport.ide.asm.GoAsmTokenTypes as T
import javax.swing.Icon

/** Colours of Go assembly: lexer level only, every key falls back to a default of the scheme. */
object GoAsmColors {
    @JvmField val LINE_COMMENT = createTextAttributesKey("GO_ASM_LINE_COMMENT", Default.LINE_COMMENT)
    @JvmField val BLOCK_COMMENT = createTextAttributesKey("GO_ASM_BLOCK_COMMENT", Default.BLOCK_COMMENT)
    @JvmField val PREPROCESSOR = createTextAttributesKey("GO_ASM_PREPROCESSOR", Default.METADATA)
    @JvmField val STRING = createTextAttributesKey("GO_ASM_STRING", Default.STRING)
    @JvmField val NUMBER = createTextAttributesKey("GO_ASM_NUMBER", Default.NUMBER)
    @JvmField val DIRECTIVE = createTextAttributesKey("GO_ASM_DIRECTIVE", Default.KEYWORD)
    @JvmField val INSTRUCTION = createTextAttributesKey("GO_ASM_INSTRUCTION", Default.FUNCTION_CALL)
    @JvmField val LABEL = createTextAttributesKey("GO_ASM_LABEL", Default.LABEL)
    @JvmField val SYMBOL = createTextAttributesKey("GO_ASM_SYMBOL", Default.FUNCTION_DECLARATION)
    @JvmField val IDENTIFIER = createTextAttributesKey("GO_ASM_IDENTIFIER", Default.IDENTIFIER)
    @JvmField val REGISTER = createTextAttributesKey("GO_ASM_REGISTER", Default.LOCAL_VARIABLE)
    @JvmField val PSEUDO_REGISTER = createTextAttributesKey("GO_ASM_PSEUDO_REGISTER", Default.PREDEFINED_SYMBOL)
    @JvmField val ABI_SUFFIX = createTextAttributesKey("GO_ASM_ABI_SUFFIX", Default.METADATA)
    @JvmField val PARENTHESES = createTextAttributesKey("GO_ASM_PARENTHESES", Default.PARENTHESES)
    @JvmField val BRACKETS = createTextAttributesKey("GO_ASM_BRACKETS", Default.BRACKETS)
    @JvmField val COMMA = createTextAttributesKey("GO_ASM_COMMA", Default.COMMA)
    @JvmField val SEMICOLON = createTextAttributesKey("GO_ASM_SEMICOLON", Default.SEMICOLON)
    @JvmField val OPERATOR = createTextAttributesKey("GO_ASM_OPERATOR", Default.OPERATION_SIGN)
    @JvmField val BAD_CHARACTER = createTextAttributesKey("GO_ASM_BAD_CHARACTER", com.intellij.openapi.editor.HighlighterColors.BAD_CHARACTER)

    val byToken: Map<IElementType, TextAttributesKey> = mapOf(
        T.LINE_COMMENT to LINE_COMMENT, T.BLOCK_COMMENT to BLOCK_COMMENT, T.PREPROCESSOR to PREPROCESSOR, T.STRING to STRING,
        T.NUMBER to NUMBER, T.DIRECTIVE to DIRECTIVE, T.INSTRUCTION to INSTRUCTION, T.LABEL to LABEL, T.SYMBOL to SYMBOL,
        T.IDENTIFIER to IDENTIFIER, T.REGISTER to REGISTER, T.PSEUDO_REGISTER to PSEUDO_REGISTER, T.ABI_SUFFIX to ABI_SUFFIX,
        T.LPAREN to PARENTHESES, T.RPAREN to PARENTHESES, T.LBRACKET to BRACKETS, T.RBRACKET to BRACKETS, T.COMMA to COMMA,
        T.SEMICOLON to SEMICOLON, T.COLON to OPERATOR, T.OPERATOR to OPERATOR, TokenType.BAD_CHARACTER to BAD_CHARACTER,
    )
}

class GoAsmSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = GoAsmLexer()
    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> = pack(GoAsmColors.byToken[tokenType])
}

class GoAsmSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = GoAsmSyntaxHighlighter()
}

/** Settings | Editor | Color Scheme | Go Assembly. */
class GoAsmColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName(): String = "Go Assembly"
    override fun getIcon(): Icon = GoAsmFileType.icon
    override fun getHighlighter(): SyntaxHighlighter = GoAsmSyntaxHighlighter()
    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null
    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getDemoText(): String = """
        #include "textflag.h"

        #define Big 0x4330000000000000 // 2**52

        /* func add(a, b int) int */
        TEXT ·add<ABIInternal>(SB), NOSPLIT, ${'$'}0-24
        	MOVQ	a+0(FP), AX
        	ADDQ	b+8(FP), AX
        	CMPQ	AX, ${'$'}0x10
        	JLT	done
        	CALL	runtime·morestack(SB)
        done:
        	MOVQ	AX, ret+16(FP)
        	RET

        DATA ·msg+0(SB)/8, ${'$'}"hello\n"
        GLOBL ·msg(SB), RODATA, ${'$'}8
    """.trimIndent()

    private companion object {
        val DESCRIPTORS = arrayOf(
            AttributesDescriptor("Comments//Line comment", GoAsmColors.LINE_COMMENT),
            AttributesDescriptor("Comments//Block comment", GoAsmColors.BLOCK_COMMENT),
            AttributesDescriptor("Preprocessor directive", GoAsmColors.PREPROCESSOR),
            AttributesDescriptor("String", GoAsmColors.STRING),
            AttributesDescriptor("Number", GoAsmColors.NUMBER),
            AttributesDescriptor("Assembler directive (TEXT, DATA, GLOBL…)", GoAsmColors.DIRECTIVE),
            AttributesDescriptor("Instruction", GoAsmColors.INSTRUCTION),
            AttributesDescriptor("Label", GoAsmColors.LABEL),
            AttributesDescriptor("Symbol (·name)", GoAsmColors.SYMBOL),
            AttributesDescriptor("ABI suffix (<ABIInternal>, <>)", GoAsmColors.ABI_SUFFIX),
            AttributesDescriptor("Identifier", GoAsmColors.IDENTIFIER),
            AttributesDescriptor("Register", GoAsmColors.REGISTER),
            AttributesDescriptor("Pseudo-register (SB, FP, SP, PC)", GoAsmColors.PSEUDO_REGISTER),
            AttributesDescriptor("Braces and Operators//Parentheses", GoAsmColors.PARENTHESES),
            AttributesDescriptor("Braces and Operators//Brackets", GoAsmColors.BRACKETS),
            AttributesDescriptor("Braces and Operators//Comma", GoAsmColors.COMMA),
            AttributesDescriptor("Braces and Operators//Semicolon", GoAsmColors.SEMICOLON),
            AttributesDescriptor("Braces and Operators//Operator", GoAsmColors.OPERATOR),
            AttributesDescriptor("Bad character", GoAsmColors.BAD_CHARACTER),
        )
    }
}

/** `//` line comments, `/* */` blocks: the comments `cmd/asm` accepts. */
class GoAsmCommenter : Commenter {
    override fun getLineCommentPrefix(): String = "//"
    override fun getBlockCommentPrefix(): String = "/*"
    override fun getBlockCommentSuffix(): String = "*/"
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}
