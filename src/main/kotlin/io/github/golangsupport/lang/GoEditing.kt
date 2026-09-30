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
        val text = document.immutableCharSequence
        structure.declarations.filter { it.kind == GoDeclarationKind.FUNCTION || it.kind == GoDeclarationKind.METHOD }.mapNotNull { it.body }
            .forEach { body -> GoBlockFolds.find(text, body).forEach { add(it, "{...}") } }
        structure.groups.forEach { add(it, "(...)") }
        GoCommentRuns.find(document.immutableCharSequence).forEach { (range, placeholder) -> add(range, placeholder) }
        return result.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String = "..."
    override fun isCollapsedByDefault(node: ASTNode): Boolean = false
}

/**
 * The braces inside the body of a function: `if`, `for`, `switch`, `select`, a function literal, a composite literal. Every pair but
 * the body itself; which of them fold is for the caller to decide by their lines.
 */
object GoBlockFolds {
    fun find(text: CharSequence, body: TextRange): List<TextRange> {
        val lexer = GoLexer()
        lexer.start(text, body.startOffset, body.endOffset, 0)
        val open = ArrayList<Int>()
        val result = ArrayList<TextRange>()
        while (lexer.tokenType != null) {
            when (lexer.tokenType) {
                GoTokenTypes.LBRACE -> open += lexer.tokenStart
                GoTokenTypes.RBRACE -> open.removeLastOrNull()?.let { start -> if (start != body.startOffset) result += TextRange(start, lexer.tokenEnd) }
            }
            lexer.advance()
        }
        return result.sortedBy { it.startOffset }
    }
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

/**
 * Where the live templates of `liveTemplates/Go.xml` apply: the code of a Go file, not its strings and comments. Inside a struct tag
 * `json` + Tab expanded the `json` template into the backquotes that were there already (seen live: ``json:""``).
 */
class GoTemplateContext : TemplateContextType("Go") {
    override fun isInContext(templateActionContext: TemplateActionContext): Boolean {
        val file = templateActionContext.file as? GoFile ?: return false
        return !GoTemplateContexts.isInLiteralOrComment(file.viewProvider.contents, templateActionContext.startOffset)
    }
}

object GoTemplateContexts {
    /** Whether [offset] is inside a string, a rune or a comment; the token that ends at [offset] counts, the caret stands at its end while typing. */
    fun isInLiteralOrComment(text: CharSequence, offset: Int): Boolean {
        val lexer = GoLexer()
        lexer.start(text, 0, text.length, 0)
        while (true) {
            val type = lexer.tokenType ?: return false
            if (lexer.tokenStart >= offset) return false
            val inside = offset > lexer.tokenStart && (offset < lexer.tokenEnd || offset == lexer.tokenEnd && !isClosed(text, lexer.tokenStart, lexer.tokenEnd, type))
            if (inside && (type in GoTokenTypes.STRINGS || type in GoTokenTypes.COMMENTS)) return true
            lexer.advance()
        }
    }

    /** A string or a block comment that has its closing quote or `*` + `/`; a line comment never closes before the line ends. */
    private fun isClosed(text: CharSequence, start: Int, end: Int, type: com.intellij.psi.tree.IElementType): Boolean = when {
        type == GoTokenTypes.LINE_COMMENT || type == GoTokenTypes.DIRECTIVE -> false
        type == GoTokenTypes.BLOCK_COMMENT -> end - start >= 4 && text[end - 1] == '/' && text[end - 2] == '*'
        else -> end - start >= 2 && text[end - 1] == text[start]
    }
}

/**
 * `goErrorReturn()`: what leaves the function at the caret with `err`, for the `err` template: `return nil, err`, `return total, err`
 * (named results), `t.Fatal(err)` in a test, `log.Fatal(err)` in `main`; `return err` when the function is not found.
 */
class GoErrorReturnMacro : MacroBase("goErrorReturn", "goErrorReturn()") {
    override fun calculateResult(params: Array<Expression>, context: ExpressionContext, quick: Boolean): Result? {
        val text = context.editor?.document?.immutableCharSequence ?: context.psiElementAtStartOffset?.containingFile?.viewProvider?.contents ?: return null
        return TextResult(GoIdioms.returnStatement(text, context.startOffset, "err"))
    }

    override fun isAcceptableInContext(context: TemplateContextType?): Boolean = context is GoTemplateContext
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
