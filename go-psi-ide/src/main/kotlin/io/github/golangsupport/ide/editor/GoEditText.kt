package io.github.golangsupport.ide.editor

import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCommClause
import io.github.golangsupport.lang.psi.GoExprCaseClause
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoTypeCaseClause
import io.github.golangsupport.lang.psi.GoTypes

/**
 * Text and PSI helpers of the editing actions (surround, unwrap, move, join). They edit the document as text, with tabs like gofmt,
 * instead of PSI mutations + reformat: the host may give formatting to an external gofmt, and a text edit is what the user sees.
 */
internal object GoEditText {

    /** A block or a case/comm clause: an element whose [GoStatement] children are a statement list. */
    fun isStatementList(element: PsiElement?): Boolean =
        element is GoBlock || element is GoExprCaseClause || element is GoTypeCaseClause || element is GoCommClause

    fun statements(list: PsiElement): List<GoStatement> = list.children.filterIsInstance<GoStatement>()

    /** The innermost statement of a statement list that contains [element] (itself included). */
    fun listStatement(element: PsiElement): GoStatement? {
        var e: PsiElement? = element
        while (e != null && e !is PsiFile) {
            if (e is GoStatement && isStatementList(e.parent)) return e
            e = e.parent
        }
        return null
    }

    /** Statement-list statements above [element], innermost first. */
    fun listStatements(element: PsiElement): List<GoStatement> = generateSequence(listStatement(element)) { s -> s.parent?.let(::listStatement) }.toList()

    /** `f()` standing alone as a statement: the statement when its only content is [expression]. */
    fun expressionStatement(expression: GoExpression): GoSimpleStatement? {
        val statement = expression.parent?.parent as? GoSimpleStatement ?: return null
        if (statement.statement != null || statement.leftHandExprList?.expressionList?.singleOrNull() != expression) return null
        return statement.takeIf { isStatementList(it.parent) }
    }

    fun isBlank(element: PsiElement?): Boolean = element is PsiWhiteSpace || element?.node?.elementType.let { it == GoTypes.SEMICOLON_SYNTHETIC }

    /** The end of the last child of [element] that is neither whitespace nor an inserted semicolon (a clause ends with one). */
    fun contentEnd(element: PsiElement): Int {
        var child = element.lastChild
        while (child != null && (isBlank(child) || child.node.elementType == GoTypes.SEMICOLON) && child.prevSibling != null) child = child.prevSibling
        return child?.textRange?.endOffset ?: element.textRange.endOffset
    }

    fun prevNonBlank(element: PsiElement): PsiElement? = PsiTreeUtil.skipSiblingsBackward(element, PsiWhiteSpace::class.java)

    fun lineStart(text: CharSequence, offset: Int): Int {
        var i = minOf(offset, text.length) - 1
        while (i >= 0 && text[i] != '\n') i--
        return i + 1
    }

    /** The offset of the `\n` that ends the line of [offset], or the text length. */
    fun lineEnd(text: CharSequence, offset: Int): Int {
        var i = offset
        while (i < text.length && text[i] != '\n') i++
        return i
    }

    fun indentOf(text: CharSequence, offset: Int): String {
        val start = lineStart(text, offset)
        var end = start
        while (end < text.length && (text[end] == ' ' || text[end] == '\t')) end++
        return text.subSequence(start, end).toString()
    }

    /** Only spaces and tabs stand between the start of the line and [offset]. */
    fun startsLine(text: CharSequence, offset: Int): Boolean = text.subSequence(lineStart(text, offset), offset).isBlank()

    /** Only whitespace or a line comment follows [offset] on its line. */
    fun endsLine(text: CharSequence, offset: Int): Boolean = text.subSequence(offset, lineEnd(text, offset)).trim().let { it.isEmpty() || it.startsWith("//") }

    /** Whether the line starting at [lineStart] begins inside a raw string or a block comment: its leading whitespace is content. */
    private fun insideToken(file: PsiFile, lineStart: Int): Boolean {
        val leaf = file.findElementAt(lineStart) ?: return false
        val type = leaf.node.elementType
        return (type == GoTypes.RAW_STRING || leaf is PsiComment) && leaf.textRange.startOffset < lineStart
    }

    /**
     * The lines of [from]..[to) of [file] ([from] a line start) with [delta] tabs added at the start of each line, or removed when negative.
     * Blank lines become empty, like gofmt leaves them; lines that start inside a raw string or a block comment keep their text.
     */
    fun shift(file: PsiFile, from: Int, to: Int, delta: Int): String {
        val text = file.node.chars
        val out = StringBuilder()
        var p = from
        while (p <= to) {
            val eol = minOf(lineEnd(text, p), to)
            val line = text.subSequence(p, eol)
            when {
                insideToken(file, p) -> out.append(line)
                line.isBlank() -> {}
                delta >= 0 -> out.append("\t".repeat(delta)).append(line)
                else -> out.append(dropIndent(line, -delta))
            }
            if (eol >= to) break
            out.append('\n')
            p = eol + 1
        }
        return out.toString()
    }

    /** [line] without [levels] leading tabs (four spaces count as one, for files not formatted yet). */
    private fun dropIndent(line: CharSequence, levels: Int): CharSequence {
        var i = 0
        repeat(levels) {
            when {
                i < line.length && line[i] == '\t' -> i++
                line.length >= i + 4 && line.subSequence(i, i + 4).all { it == ' ' } -> i += 4
            }
        }
        return line.subSequence(i, line.length)
    }

    /**
     * The statements between an opener ending at [openEnd] (`{`, `:`) and [end] (the closer's start or the clause's content end), shifted
     * by [delta] levels; text on the opener's line goes first, at [indent]. Leading and trailing empty lines are dropped; "" for no content.
     */
    fun body(file: PsiFile, openEnd: Int, end: Int, delta: Int, indent: String): String {
        val text = file.node.chars
        val firstEol = lineEnd(text, openEnd)
        if (firstEol >= end) return text.subSequence(openEnd, end).trim().let { if (it.isEmpty()) "" else "$indent$it" }
        val parts = ArrayList<String>()
        val inline = text.subSequence(openEnd, firstEol).trim()
        if (inline.isNotEmpty()) parts += "$indent$inline"
        val to = if (startsLine(text, end)) lineStart(text, end) - 1 else end
        if (to > firstEol + 1) parts += shift(file, firstEol + 1, to, delta)
        return parts.joinToString("\n").trim('\n')
    }

    /**
     * Replaces [statement] by [lines] (each with its indentation, the first one's dropped since the statement starts after it); with no
     * lines, the statement's lines go away when it stands alone on them.
     */
    fun replaceStatement(statement: PsiElement, lines: String): GoEditPlan.Edit {
        val text = statement.containingFile.node.chars
        val range = statement.textRange
        if (lines.isNotBlank()) return GoEditPlan.Edit(range.startOffset, range.endOffset, lines.trimStart(' ', '\t'))
        if (startsLine(text, range.startOffset) && text.subSequence(range.endOffset, lineEnd(text, range.endOffset)).isBlank()) {
            val start = lineStart(text, range.startOffset)
            return GoEditPlan.Edit(start, minOf(text.length, lineEnd(text, range.endOffset) + 1), "")
        }
        return GoEditPlan.Edit(range.startOffset, range.endOffset, "")
    }

    fun document(file: PsiFile): Document? = PsiDocumentManager.getInstance(file.project).getDocument(file)

    /** Applies [edits] (offsets of the committed text) to the document of [file] and commits it. */
    fun apply(file: PsiFile, edits: List<GoEditPlan.Edit>) {
        val document = document(file) ?: return
        val manager = PsiDocumentManager.getInstance(file.project)
        manager.doPostponedOperationsAndUnblockDocument(document)
        for (edit in edits.sortedByDescending { it.start }) document.replaceString(edit.start, edit.end, edit.text)
        manager.commitDocument(document)
    }
}
