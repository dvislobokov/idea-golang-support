package io.github.golangsupport.ide.editor

import com.intellij.codeInsight.editorActions.moveUpDown.LineRange
import com.intellij.codeInsight.editorActions.moveUpDown.StatementUpDownMover
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoCommClause
import io.github.golangsupport.lang.psi.GoConstDeclaration
import io.github.golangsupport.lang.psi.GoConstSpec
import io.github.golangsupport.lang.psi.GoConstraintElem
import io.github.golangsupport.lang.psi.GoExprCaseClause
import io.github.golangsupport.lang.psi.GoExprSwitchStatement
import io.github.golangsupport.lang.psi.GoFieldDeclaration
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoImportDeclaration
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoInterfaceType
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoSelectStatement
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoStructType
import io.github.golangsupport.lang.psi.GoTypeCaseClause
import io.github.golangsupport.lang.psi.GoTypeDeclaration
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoTypeSwitchStatement
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.lang.psi.GoVarDeclaration
import io.github.golangsupport.lang.psi.GoVarSpec

/**
 * Move Statement Up/Down (Ctrl+Shift+Up/Down) by whole Go elements: a statement of a block or a case body (with all its lines), a
 * `case` clause, a struct field, an interface method, a spec of a `var (…)` / `const (…)` / `type (…)` / `import (…)` group, a top-level
 * declaration; comment lines right above an element move with it. An element swaps with its neighbour in the same list and stops at
 * the edge of the list (no moving into or out of a nested block). The blank lines between the two stay where they were, so the
 * layout gofmt keeps between declarations does not change; the indentation is the same along one list, so nothing is reindented.
 */
class GoStatementMover : StatementUpDownMover() {

    override fun checkAvailable(editor: Editor, file: PsiFile, info: MoveInfo, down: Boolean): Boolean {
        if (file !is GoFile) return false
        val document = editor.document
        val text = file.node.chars
        if (text.length != document.textLength) return false
        val (startLine, endLine) = selectedLines(editor)
        val startLeaf = firstCode(file, document, startLine, forward = true) ?: return false
        val endLeaf = firstCode(file, document, endLine, forward = false) ?: startLeaf
        val (first, last) = commonUnits(startLeaf, endLeaf) ?: return false
        val siblings = siblings(first.parent)
        val firstIndex = siblings.indexOf(first)
        val lastIndex = siblings.indexOf(last)
        if (firstIndex < 0 || lastIndex < firstIndex || !ownsLines(text, first, last)) return false
        val neighbour = siblings.getOrNull(if (down) lastIndex + 1 else firstIndex - 1)
        if (neighbour == null || !ownsLines(text, neighbour, neighbour)) return info.prohibitMove()
        val moving = LineRange(lineOf(document, start(first)), lineOf(document, GoEditText.contentEnd(last)) + 1)
        val other = LineRange(lineOf(document, start(neighbour)), lineOf(document, GoEditText.contentEnd(neighbour)) + 1)
        // the lines between the two (blank lines, detached comments) stay in the middle: afterMove puts them back
        val gap = if (down) other.startLine - moving.endLine else moving.startLine - other.endLine
        info.toMove = moving
        info.toMove2 = if (down) LineRange(moving.endLine, other.endLine) else LineRange(other.startLine, moving.startLine)
        info.indentSource = false
        info.indentTarget = false
        val neighbourLines = other.endLine - other.startLine
        // where the two blocks to swap back stand after the move: up — moved, neighbour, gap; down — gap, neighbour, moved
        info.putUserData(LAYOUT, if (down) Layout(moving.startLine, gap, neighbourLines) else Layout(other.startLine + (moving.endLine - moving.startLine), neighbourLines, gap))
        return true
    }

    override fun afterMove(editor: Editor, file: PsiFile, info: MoveInfo, down: Boolean) {
        val layout = info.getUserData(LAYOUT) ?: return
        info.putUserData(LAYOUT, null)
        if (layout.first > 0 && layout.second > 0) reorder(editor.document, layout.line, layout.first, layout.second)
    }

    /** After the move, [first] lines from [line] followed by [second] lines are to be swapped back. */
    private class Layout(val line: Int, val first: Int, val second: Int)

    private companion object {
        val LAYOUT: Key<Layout> = Key.create("go.statement.mover.layout")

        /** Rewrites [a] lines followed by [b] lines starting at [line] as the [b] lines followed by the [a] lines. */
        fun reorder(document: Document, line: Int, a: Int, b: Int) {
            if (line < 0 || line + a + b > document.lineCount) return
            val lines = (line until line + a + b).map { document.getText(TextRange(document.getLineStartOffset(it), document.getLineEndOffset(it))) }
            val swapped = lines.drop(a) + lines.take(a)
            document.replaceString(document.getLineStartOffset(line), document.getLineEndOffset(line + a + b - 1), swapped.joinToString("\n"))
        }

        fun selectedLines(editor: Editor): Pair<Int, Int> {
            val document = editor.document
            val selection = editor.selectionModel
            if (!selection.hasSelection()) return document.getLineNumber(editor.caretModel.offset).let { it to it }
            val start = document.getLineNumber(selection.selectionStart)
            var end = document.getLineNumber(selection.selectionEnd)
            if (end > start && document.getLineStartOffset(end) == selection.selectionEnd) end--
            return start to end
        }

        fun lineOf(document: Document, offset: Int): Int = document.getLineNumber(minOf(offset, document.textLength))

        fun isCode(leaf: PsiElement): Boolean = leaf !is PsiWhiteSpace && leaf !is PsiComment && !GoEditText.isBlank(leaf) && leaf.textLength > 0

        /** The first (last) code token of [line]; on a comment-only line, the first code token after it (an element the comment documents). */
        fun firstCode(file: PsiFile, document: Document, line: Int, forward: Boolean): PsiElement? {
            if (line >= document.lineCount) return null
            val start = document.getLineStartOffset(line)
            val end = document.getLineEndOffset(line)
            var offset = if (forward) start else end - 1
            var comment = false
            while (offset in start until end) {
                val leaf = file.findElementAt(offset) ?: return null
                if (isCode(leaf)) return leaf
                comment = comment || leaf is PsiComment
                offset = if (forward) leaf.textRange.endOffset else leaf.textRange.startOffset - 1
            }
            // a blank line is left to the line mover
            if (!forward || !comment) return null
            var leaf = file.findElementAt(start)
            while (leaf != null && !isCode(leaf)) leaf = PsiTreeUtil.nextLeaf(leaf)
            return leaf
        }

        /** Whether [element] is one of the elements this mover moves: its parent holds a list of them. */
        fun isUnit(element: PsiElement): Boolean {
            val parent = element.parent ?: return false
            return when (element) {
                is GoExprCaseClause, is GoTypeCaseClause, is GoCommClause -> true
                is GoFieldDeclaration -> parent is GoStructType
                is GoMethodSpec, is GoConstraintElem -> parent is GoInterfaceType
                is GoVarSpec, is GoConstSpec, is GoTypeSpec, is GoImportSpec -> parent.node.findChildByType(GoTypes.LPAREN) != null
                is GoFunctionDeclaration, is GoMethodDeclaration, is GoTypeDeclaration, is GoVarDeclaration, is GoConstDeclaration, is GoImportDeclaration ->
                    parent is GoFile || GoEditText.isStatementList(parent)
                is GoStatement -> GoEditText.isStatementList(parent)
                else -> false
            }
        }

        fun siblings(parent: PsiElement): List<PsiElement> = when (parent) {
            is GoExprSwitchStatement, is GoTypeSwitchStatement, is GoSelectStatement ->
                parent.children.filter { it is GoExprCaseClause || it is GoTypeCaseClause || it is GoCommClause }
            else -> parent.children.filter { isUnit(it) }
        }

        fun units(leaf: PsiElement): List<PsiElement> = generateSequence(leaf) { it.parent }.takeWhile { it !is PsiFile }.filter(::isUnit).toList()

        /** The innermost units above [startLeaf] and [endLeaf] that are siblings. */
        fun commonUnits(startLeaf: PsiElement, endLeaf: PsiElement): Pair<PsiElement, PsiElement>? {
            val ends = units(endLeaf)
            for (first in units(startLeaf)) {
                val last = ends.firstOrNull { it.parent == first.parent } ?: continue
                return if (first.textRange.startOffset <= last.textRange.startOffset) first to last else last to first
            }
            return null
        }

        /** The start of [element] with the comment lines right above it (no blank line in between). */
        fun start(element: PsiElement): Int {
            var start = element
            var p = element.prevSibling
            while (p != null) {
                if (p is PsiWhiteSpace && p.text.count { it == '\n' } <= 1) p = p.prevSibling
                else if (p is PsiComment && GoEditText.startsLine(p.containingFile.node.chars, p.textRange.startOffset)) { start = p; p = p.prevSibling }
                else if (GoEditText.isBlank(p) && p.text == "\n" && p.prevSibling is PsiComment) p = p.prevSibling
                else break
            }
            return start.textRange.startOffset
        }

        /** Whether [first]..[last] take whole lines: nothing but indentation before, nothing but a line comment after. */
        fun ownsLines(text: CharSequence, first: PsiElement, last: PsiElement): Boolean =
            GoEditText.startsLine(text, start(first)) && GoEditText.endsLine(text, GoEditText.contentEnd(last))
    }
}
