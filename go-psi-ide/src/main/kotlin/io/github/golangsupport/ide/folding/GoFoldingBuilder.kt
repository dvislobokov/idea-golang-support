package io.github.golangsupport.ide.folding

import com.intellij.codeInsight.folding.CodeFoldingSettings
import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.SyntaxTraverser
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoConstDeclaration
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoImportDeclaration
import io.github.golangsupport.lang.psi.GoInterfaceType
import io.github.golangsupport.lang.psi.GoLiteralValue
import io.github.golangsupport.lang.psi.GoStructType
import io.github.golangsupport.lang.psi.GoTypeDeclaration
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.lang.psi.GoVarDeclaration

/**
 * Syntax-only folding: function and function literal bodies, composite literal values,
 * struct/interface bodies, parenthesised import/const/var/type groups, multi-line block comments
 * and runs of at least [MIN_LINE_COMMENT_RUN] whole-line `//` comments. Every region spans more
 * than one line. Import groups are collapsed by default when "Fold imports" is on.
 */
class GoFoldingBuilder : FoldingBuilderEx(), DumbAware {

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val text = document.charsSequence
        val result = mutableListOf<FoldingDescriptor>()
        val comments = mutableListOf<PsiComment>()

        for (element in SyntaxTraverser.psiTraverser(root)) {
            when (element) {
                is GoBlock -> if (element.parent is GoFunctionOrMethodDeclaration || element.parent is GoFunctionLit) {
                    addBetween(result, text, element, element.lbrace, element.rbrace, BRACES)
                }
                is GoLiteralValue -> addBetween(result, text, element, element.lbrace, element.rbrace, BRACES)
                is GoStructType -> addBetween(result, text, element, element.lbrace, element.rbrace, BRACES)
                is GoInterfaceType -> addBetween(result, text, element, element.lbrace, element.rbrace, BRACES)
                is GoImportDeclaration -> addBetween(result, text, element, element.lparen, element.rparen, PARENS)
                is GoConstDeclaration -> addBetween(result, text, element, element.lparen, element.rparen, PARENS)
                is GoVarDeclaration -> addBetween(result, text, element, element.lparen, element.rparen, PARENS)
                is GoTypeDeclaration -> addBetween(result, text, element, element.lparen, element.rparen, PARENS)
                is PsiComment -> comments += element
            }
        }
        addComments(result, text, comments)
        return result.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String = "..."

    override fun isCollapsedByDefault(node: ASTNode): Boolean =
        node.elementType == GoTypes.IMPORT_DECLARATION && CodeFoldingSettings.getInstance().COLLAPSE_IMPORTS

    private fun addBetween(
        result: MutableList<FoldingDescriptor>,
        text: CharSequence,
        owner: PsiElement,
        open: PsiElement?,
        close: PsiElement?,
        placeholder: String,
    ) {
        if (open == null || close == null) return
        val range = TextRange(open.textRange.startOffset, close.textRange.endOffset)
        if (spansLines(text, range)) result += FoldingDescriptor(owner.node, range, null, placeholder)
    }

    private fun addComments(result: MutableList<FoldingDescriptor>, text: CharSequence, comments: List<PsiComment>) {
        var run = mutableListOf<PsiComment>()
        fun flush() {
            if (run.size >= MIN_LINE_COMMENT_RUN) {
                val range = TextRange(run.first().textRange.startOffset, run.last().textRange.endOffset)
                result += FoldingDescriptor(run.first().node, range, null, LINE_COMMENTS)
            }
            run = mutableListOf()
        }
        for (comment in comments) {
            if (comment.tokenType == GoTypes.BLOCK_COMMENT) {
                flush()
                if (spansLines(text, comment.textRange)) {
                    result += FoldingDescriptor(comment.node, comment.textRange, null, BLOCK_COMMENT)
                }
                continue
            }
            if (!startsLine(text, comment.textRange.startOffset)) {
                flush()
                continue
            }
            val previous = run.lastOrNull()
            if (previous != null && !isSingleLineBreak(text, previous.textRange.endOffset, comment.textRange.startOffset)) flush()
            run += comment
        }
        flush()
    }

    private fun spansLines(text: CharSequence, range: TextRange): Boolean {
        for (i in range.startOffset until minOf(range.endOffset, text.length)) {
            if (text[i] == '\n') return true
        }
        return false
    }

    private fun startsLine(text: CharSequence, offset: Int): Boolean {
        var i = offset - 1
        while (i >= 0 && text[i] != '\n') {
            if (!text[i].isWhitespace()) return false
            i--
        }
        return true
    }

    /** True if [start, end) is whitespace with exactly one line break: two comments on adjacent lines. */
    private fun isSingleLineBreak(text: CharSequence, start: Int, end: Int): Boolean {
        var breaks = 0
        for (i in start until end) {
            val c = text[i]
            if (!c.isWhitespace()) return false
            if (c == '\n') breaks++
        }
        return breaks == 1
    }

    private companion object {
        const val MIN_LINE_COMMENT_RUN = 3
        const val BRACES = "{...}"
        const val PARENS = "(...)"
        const val BLOCK_COMMENT = "/*...*/"
        const val LINE_COMMENTS = "//..."
    }
}
