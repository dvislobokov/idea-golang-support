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
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoBreakStatement
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoCommClause
import io.github.golangsupport.lang.psi.GoConditionalExpr
import io.github.golangsupport.lang.psi.GoContinueStatement
import io.github.golangsupport.lang.psi.GoExprCaseClause
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoGotoStatement
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoReturnStatement
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoTypeCaseClause
import io.github.golangsupport.lang.psi.GoConstDeclaration
import io.github.golangsupport.lang.psi.GoExprSwitchStatement
import io.github.golangsupport.lang.psi.GoImportDeclaration
import io.github.golangsupport.lang.psi.GoInterfaceType
import io.github.golangsupport.lang.psi.GoLiteralValue
import io.github.golangsupport.lang.psi.GoSelectStatement
import io.github.golangsupport.lang.psi.GoStructType
import io.github.golangsupport.lang.psi.GoTypeDeclaration
import io.github.golangsupport.lang.psi.GoTypeSwitchStatement
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.lang.psi.GoVarDeclaration

/**
 * Syntax-only folding: every `{...}` block (function and function literal bodies, `if`/`else`/`for`
 * blocks, `switch`/`select` bodies), composite literal values, struct/interface bodies,
 * parenthesised import/const/var/type groups, multi-line block comments and runs of at least
 * [MIN_LINE_COMMENT_RUN] whole-line `//` comments (a doc comment above a declaration too). Every
 * region spans more than one line. Import groups are collapsed by default when "Fold imports" is on.
 *
 * Like GoLand, some regions show on one line with their own text as the placeholder (`{ return "", err }`, `case 1: x--`, `{}`); such a
 * region replaces the `{...}` one of the same range and is collapsed by default per [GoFoldingSettings].
 */
class GoFoldingBuilder : FoldingBuilderEx(), DumbAware {

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val text = document.charsSequence
        val result = mutableListOf<FoldingDescriptor>()
        val comments = mutableListOf<PsiComment>()

        for (element in SyntaxTraverser.psiTraverser(root)) {
            when (element) {
                // Nested blocks too: the pre-PSI plugin folded every brace pair inside a function body.
                is GoBlock -> if (!addOneLiner(result, text, element)) addBetween(result, text, element, element.lbrace, element.rbrace, BRACES)
                is GoExprCaseClause, is GoTypeCaseClause, is GoCommClause -> addOneLiner(result, text, element)
                is GoExprSwitchStatement, is GoTypeSwitchStatement, is GoSelectStatement -> addBetween(
                    result, text, element, element.node.findChildByType(GoTypes.LBRACE)?.psi, element.node.findChildByType(GoTypes.RBRACE)?.psi, BRACES,
                )
                is GoLiteralValue -> addBetween(result, text, element, element.lbrace, element.rbrace, BRACES)
                is GoStructType -> if (!addOneLiner(result, text, element)) addBetween(result, text, element, element.lbrace, element.rbrace, BRACES)
                is GoInterfaceType -> if (!addOneLiner(result, text, element)) addBetween(result, text, element, element.lbrace, element.rbrace, BRACES)
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

    override fun isCollapsedByDefault(node: ASTNode): Boolean {
        if (node.elementType == GoTypes.IMPORT_DECLARATION) return CodeFoldingSettings.getInstance().COLLAPSE_IMPORTS
        val settings = GoFoldingSettings.getInstance()
        return when (oneLiner(node.psi ?: return false)?.kind) {
            OneLineKind.ERROR_IF -> settings.collapseErrorHandlingIf
            OneLineKind.SINGLE_RETURN -> settings.collapseSingleReturnFunctions
            OneLineKind.CASE_CLAUSE -> settings.collapseCaseClauses
            OneLineKind.EMPTY_FUNCTION -> settings.collapseEmptyFunctions
            OneLineKind.EMPTY_TYPE -> settings.collapseEmptyTypes
            null -> false
        }
    }

    /** Adds the one-line region of [element] if it has one spanning lines; true if added (the block region of the same range is then skipped). */
    private fun addOneLiner(result: MutableList<FoldingDescriptor>, text: CharSequence, element: PsiElement): Boolean {
        val oneLiner = oneLiner(element) ?: return false
        if (!spansLines(text, oneLiner.range)) return false
        result += FoldingDescriptor(element.node, oneLiner.range, null, oneLiner.placeholder)
        return true
    }

    /**
     * The region GoLand shows on one line, by syntax only: `if x != nil { return ... }` (one return / panic / break / continue / goto), a
     * function body of one `return`, a `case` with one statement, an empty function body, an empty struct / interface. The kept statement
     * must fit on one line and no comment may get hidden. [isCollapsedByDefault] recomputes it from the node, so both stay in step.
     */
    private fun oneLiner(element: PsiElement): OneLiner? = when (element) {
        is GoBlock -> blockOneLiner(element)
        is GoExprCaseClause -> caseOneLiner(element, element.colon, element.statementList)
        is GoTypeCaseClause -> caseOneLiner(element, element.colon, element.statementList)
        is GoCommClause -> caseOneLiner(element, element.colon, element.statementList)
        is GoStructType -> emptyType(element, element.lbrace, element.rbrace, element.fieldDeclarationList.isEmpty())
        is GoInterfaceType -> emptyType(element, element.lbrace, element.rbrace, element.methodSpecList.isEmpty() && element.constraintElemList.isEmpty())
        else -> null
    }

    private fun blockOneLiner(block: GoBlock): OneLiner? {
        val close = block.rbrace ?: return null
        val range = TextRange(block.lbrace.textRange.startOffset, close.textRange.endOffset)
        val parent = block.parent
        val functionBody = parent is GoFunctionOrMethodDeclaration || parent is GoFunctionLit
        val statements = block.statementList
        // the comment walk last: only candidates (at most one short statement) pay for it
        if (statements.isEmpty()) return if (functionBody && !hasComment(block)) OneLiner(OneLineKind.EMPTY_FUNCTION, range, "{}") else null
        val statement = statements.singleOrNull()?.takeIf { '\n' !in it.text } ?: return null
        val kind = when {
            functionBody && statement is GoReturnStatement -> OneLineKind.SINGLE_RETURN
            parent is GoIfStatement && isNilCheck(parent) && isExit(statement) -> OneLineKind.ERROR_IF
            else -> return null
        }
        return if (hasComment(block)) null else OneLiner(kind, range, "{ ${shorten(statement.text)} }")
    }

    private fun caseOneLiner(clause: PsiElement, colon: PsiElement?, statements: List<GoStatement>): OneLiner? {
        val statement = statements.singleOrNull()?.takeIf { '\n' !in it.text } ?: return null
        if (colon == null || hasComment(clause)) return null
        return OneLiner(OneLineKind.CASE_CLAUSE, TextRange(colon.textRange.endOffset, statement.textRange.endOffset), " " + shorten(statement.text))
    }

    private fun emptyType(type: PsiElement, open: PsiElement?, close: PsiElement?, empty: Boolean): OneLiner? {
        if (open == null || close == null || !empty || hasComment(type)) return null
        return OneLiner(OneLineKind.EMPTY_TYPE, TextRange(open.textRange.startOffset, close.textRange.endOffset), "{}")
    }

    /** `x != nil` / `x == nil` (nil on either side) as the condition; an init statement before it is fine. */
    private fun isNilCheck(statement: GoIfStatement): Boolean {
        val condition = statement.children.firstOrNull { it is GoExpression } as? GoConditionalExpr ?: return false
        if (condition.neq == null && condition.eql == null) return false
        return condition.left.text == "nil" || condition.right?.text == "nil"
    }

    private fun isExit(statement: GoStatement): Boolean = when (statement) {
        is GoReturnStatement, is GoBreakStatement, is GoContinueStatement, is GoGotoStatement -> true
        is GoSimpleStatement -> statement.statement == null &&
            (statement.leftHandExprList?.expressionList?.singleOrNull() as? GoCallExpr)?.expression?.text == "panic"
        else -> false
    }

    private fun hasComment(element: PsiElement): Boolean = PsiTreeUtil.findChildOfType(element, PsiComment::class.java) != null

    private fun shorten(text: String): String {
        val flat = text.trim().replace(WHITESPACE, " ")
        return if (flat.length <= MAX_PLACEHOLDER) flat else flat.take(MAX_PLACEHOLDER).trimEnd() + "..."
    }

    private enum class OneLineKind { ERROR_IF, SINGLE_RETURN, CASE_CLAUSE, EMPTY_FUNCTION, EMPTY_TYPE }

    private class OneLiner(val kind: OneLineKind, val range: TextRange, val placeholder: String)

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
        const val MIN_LINE_COMMENT_RUN = 2
        const val BRACES = "{...}"
        const val PARENS = "(...)"
        const val BLOCK_COMMENT = "/*...*/"
        const val LINE_COMMENTS = "//..."
        const val MAX_PLACEHOLDER = 60
        val WHITESPACE = Regex("\\s+")
    }
}
