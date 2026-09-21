package io.github.golangsupport.lang

import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler
import com.intellij.codeInsight.template.Expression
import com.intellij.codeInsight.template.ExpressionContext
import com.intellij.codeInsight.template.Result
import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.codeInsight.template.TextResult
import com.intellij.codeInsight.template.macro.MacroBase
import com.intellij.lang.ASTNode
import com.intellij.lang.BracePair
import com.intellij.lang.Commenter
import com.intellij.lang.PairedBraceMatcher
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType

class GoCommenter : Commenter {
    override fun getLineCommentPrefix(): String = "//"
    override fun getBlockCommentPrefix(): String = "/*"
    override fun getBlockCommentSuffix(): String = "*/"
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}

class GoBraceMatcher : PairedBraceMatcher {
    override fun getPairs(): Array<BracePair> = PAIRS
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true
    override fun getCodeConstructStart(file: PsiFile, openingBraceOffset: Int): Int = openingBraceOffset

    private companion object {
        val PAIRS = arrayOf(
            BracePair(GoTokenTypes.LBRACE, GoTokenTypes.RBRACE, true),
            BracePair(GoTokenTypes.LPAREN, GoTokenTypes.RPAREN, false),
            BracePair(GoTokenTypes.LBRACKET, GoTokenTypes.RBRACKET, false),
        )
    }
}

class GoQuoteHandler : SimpleTokenSetQuoteHandler(GoTokenTypes.STRING, GoTokenTypes.RAW_STRING, GoTokenTypes.CHAR)

/** Function bodies, struct and interface bodies, the groups of `import (...)` / `const (...)`, block comments and runs of line comments. */
class GoFoldingBuilder : FoldingBuilderEx(), DumbAware {
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val file = root.containingFile ?: return FoldingDescriptor.EMPTY_ARRAY
        val structure = GoStructure.of(file)
        val result = ArrayList<FoldingDescriptor>()
        fun add(range: TextRange, placeholder: String) {
            val valid = range.endOffset <= document.textLength && range.length > 1 && document.getLineNumber(range.startOffset) != document.getLineNumber(range.endOffset)
            if (valid) result += FoldingDescriptor(root.node, range, null, placeholder)
        }
        structure.all().mapNotNull { it.body }.forEach { add(it, "{...}") }
        structure.groups.forEach { add(it, "(...)") }
        GoCommentRuns.find(document.immutableCharSequence).forEach { (range, placeholder) -> add(range, placeholder) }
        return result.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String = "..."
    override fun isCollapsedByDefault(node: ASTNode): Boolean = false
}

/** What of the comments folds: every block comment, and two or more line comments in a row that have their lines to themselves. */
object GoCommentRuns {
    fun find(text: CharSequence): List<Pair<TextRange, String>> {
        val result = ArrayList<Pair<TextRange, String>>()
        val lexer = GoLexer()
        lexer.start(text, 0, text.length, 0)
        var runStart = -1
        var runEnd = -1
        var runLines = 0
        fun flush() {
            if (runLines > 1) result += TextRange(runStart, runEnd) to "//..."
            runLines = 0
        }
        while (true) {
            val type = lexer.tokenType ?: break
            when {
                type == GoTokenTypes.LINE_COMMENT && ownsLine(text, lexer.tokenStart) -> {
                    if (runLines == 0) runStart = lexer.tokenStart
                    runEnd = lexer.tokenEnd
                    runLines++
                }
                // a blank line between two comments ends the run, a line break does not
                type == com.intellij.psi.TokenType.WHITE_SPACE -> if (lineBreaks(text, lexer.tokenStart, lexer.tokenEnd) > 1) flush()
                else -> {
                    flush()
                    if (type == GoTokenTypes.BLOCK_COMMENT) result += TextRange(lexer.tokenStart, lexer.tokenEnd) to "/*...*/"
                }
            }
            lexer.advance()
        }
        flush()
        return result
    }

    private fun ownsLine(text: CharSequence, offset: Int): Boolean {
        var i = offset - 1
        while (i >= 0 && text[i] != '\n') if (!text[i--].isWhitespace()) return false
        return true
    }

    private fun lineBreaks(text: CharSequence, from: Int, to: Int): Int = (from until to).count { text[it] == '\n' }
}

/** Where the live templates of `liveTemplates/Go.xml` apply: any place of a Go file. */
class GoTemplateContext : TemplateContextType("Go") {
    override fun isInContext(templateActionContext: TemplateActionContext): Boolean = templateActionContext.file is GoFile
}

/** `goTypeName()`: a pointer to the type declared last above the caret, the receiver the `meth` template most likely wants. */
class GoTypeNameMacro : MacroBase("goTypeName", "goTypeName()") {
    override fun calculateResult(params: Array<Expression>, context: ExpressionContext, quick: Boolean): Result? {
        val file = context.psiElementAtStartOffset?.containingFile ?: return null
        val structure = GoDeclarations.scan(context.editor?.document?.immutableCharSequence ?: file.viewProvider.contents)
        return receiverFor(structure, context.startOffset)?.let(::TextResult)
    }

    override fun isAcceptableInContext(context: TemplateContextType?): Boolean = context is GoTemplateContext

    companion object {
        fun receiverFor(structure: GoFileStructure, offset: Int): String? {
            val above = structure.declarations.lastOrNull { it.range.endOffset <= offset && (it.kind.isType || it.receiver != null) } ?: return null
            return "*" + (above.receiver ?: above.name)
        }
    }
}
