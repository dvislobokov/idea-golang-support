package io.github.golangsupport.lang

import com.intellij.codeInsight.template.Expression
import com.intellij.codeInsight.template.ExpressionContext
import com.intellij.codeInsight.template.Result
import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.codeInsight.template.TextResult
import com.intellij.codeInsight.template.macro.MacroBase
import com.intellij.openapi.util.TextRange
import io.github.golangsupport.lang.psi.GoFile

/**
 * The braces inside the body of a function: `if`, `for`, `switch`, `select`, a function literal, a composite literal. Every pair but
 * the body itself; which of them fold is for the caller to decide by their lines. The folding of the editor is the PSI builder of
 * go-psi-ide since step 4 of MIGRATION.md; this text scan stays with its test until step 6 retires the scanner.
 */
object GoBlockFolds {
    fun find(text: CharSequence, body: TextRange): List<TextRange> {
        val lexer = GoTextLexer()
        lexer.start(text, body.startOffset, body.endOffset, 0)
        val open = ArrayList<Int>()
        val result = ArrayList<TextRange>()
        while (lexer.tokenType != null) {
            when (lexer.tokenType) {
                GoTextTokens.LBRACE -> open += lexer.tokenStart
                GoTextTokens.RBRACE -> open.removeLastOrNull()?.let { start -> if (start != body.startOffset) result += TextRange(start, lexer.tokenEnd) }
            }
            lexer.advance()
        }
        return result.sortedBy { it.startOffset }
    }
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
        val lexer = GoTextLexer()
        lexer.start(text, 0, text.length, 0)
        while (true) {
            val type = lexer.tokenType ?: return false
            if (lexer.tokenStart >= offset) return false
            val inside = offset > lexer.tokenStart && (offset < lexer.tokenEnd || offset == lexer.tokenEnd && !isClosed(text, lexer.tokenStart, lexer.tokenEnd, type))
            if (inside && (type in GoTextTokens.STRINGS || type in GoTextTokens.COMMENTS)) return true
            lexer.advance()
        }
    }

    /** A string or a block comment that has its closing quote or `*` + `/`; a line comment never closes before the line ends. */
    private fun isClosed(text: CharSequence, start: Int, end: Int, type: com.intellij.psi.tree.IElementType): Boolean = when {
        type == GoTextTokens.LINE_COMMENT || type == GoTextTokens.DIRECTIVE -> false
        type == GoTextTokens.BLOCK_COMMENT -> end - start >= 4 && text[end - 1] == '/' && text[end - 2] == '*'
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
