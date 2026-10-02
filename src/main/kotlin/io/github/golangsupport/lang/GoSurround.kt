package io.github.golangsupport.lang

import com.intellij.lang.surroundWith.SurroundDescriptor
import com.intellij.lang.surroundWith.Surrounder
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.golangsupport.lang.psi.GoFile

/**
 * Surround With (Ctrl+Alt+T), by the text: whole lines go into a block with one more level of indentation, a piece of a line into
 * parentheses. No statement boundaries are known without a parser, so what is selected is taken as it is: the lines the selection
 * touches, or the exact characters when it stays inside one line.
 */
object GoSurrounders {
    /** [header] opens the block; [tail] follows the closing brace; the caret goes to [caretInHeader] characters into the header, or after the block when null. */
    class Kind(val title: String, val header: String, val tail: String = "", val caretInHeader: Int? = null, val inline: Boolean = false)

    val STATEMENTS: List<Kind> = listOf(
        Kind("if", "if  {", caretInHeader = 3),
        Kind("if ... else", "if  {", " else {\n\t\n}", caretInHeader = 3),
        Kind("for", "for  {", caretInHeader = 4),
        Kind("for { ... }", "for {"),
        Kind("func() { ... }()", "func() {", "()"),
        Kind("go func() { ... }()", "go func() {", "()"),
        Kind("defer func() { ... }()", "defer func() {", "()"),
        Kind("{ ... }", "{"),
    )

    val EXPRESSIONS: List<Kind> = listOf(
        Kind("(expr)", "(", ")", inline = true),
        Kind("!(expr)", "!(", ")", inline = true),
    )

    class Result(val text: String, val range: TextRange, val caret: Int)

    /** Whether [start]..[end] stays inside one line, so that the expression kinds apply. */
    fun isInline(text: CharSequence, start: Int, end: Int): Boolean = end > start && text.subSequence(start, end).none { it == '\n' }

    /** The text that replaces [Result.range] of [text], and where the caret goes; null when nothing is selected for the kind. */
    fun surround(text: CharSequence, start: Int, end: Int, kind: Kind): Result? {
        if (kind.inline) {
            if (!isInline(text, start, end)) return null
            val piece = text.subSequence(start, end).toString()
            val result = kind.header + piece + kind.tail
            return Result(result, TextRange(start, end), start + result.length)
        }
        var from = start.coerceIn(0, text.length)
        while (from > 0 && text[from - 1] != '\n') from--
        var to = end.coerceIn(from, text.length)
        // a selection that ends at the start of a line does not take that line
        if (to > from && text[to - 1] == '\n') to--
        while (to < text.length && text[to] != '\n') to++
        val lines = text.subSequence(from, to).toString().split('\n')
        if (lines.all { it.isBlank() }) return null
        val indent = lines.first { it.isNotBlank() }.takeWhile { it == ' ' || it == '\t' }
        val body = lines.joinToString("\n") { if (it.isBlank()) "" else "\t$it" }
        val tail = kind.tail.replace("\n", "\n$indent")
        val result = "$indent${kind.header}\n$body\n$indent}$tail"
        val caret = when {
            kind.caretInHeader != null -> from + indent.length + kind.caretInHeader
            kind.tail.contains("\n\t\n") -> from + result.length - tail.length + tail.indexOf("\n\t\n") + 2 + indent.length
            else -> from + result.length
        }
        return Result(result, TextRange(from, to), caret)
    }
}

class GoSurroundDescriptor : SurroundDescriptor {
    /** Something, so that the popup opens: the surrounders read the selection of the editor, not these elements. */
    override fun getElementsToSurround(file: PsiFile, startOffset: Int, endOffset: Int): Array<PsiElement> =
        if (file is GoFile && endOffset > startOffset) arrayOf(file.findElementAt(startOffset) ?: file) else PsiElement.EMPTY_ARRAY

    override fun getSurrounders(): Array<Surrounder> = (GoSurrounders.STATEMENTS + GoSurrounders.EXPRESSIONS).map(::GoSurrounder).toTypedArray()
    override fun isExclusive(): Boolean = true
}

class GoSurrounder(private val kind: GoSurrounders.Kind) : Surrounder {
    override fun getTemplateDescription(): String = kind.title

    override fun isApplicable(elements: Array<out PsiElement>): Boolean {
        val file = elements.firstOrNull()?.containingFile ?: return false
        val text = file.viewProvider.contents
        // the elements say where the selection is only roughly; the kinds of a line apply to a selection inside one
        val start = elements.first().textRange.startOffset
        return !kind.inline || GoSurrounders.isInline(text, start, minOf(text.length, elements.last().textRange.endOffset))
    }

    override fun surroundElements(project: Project, editor: Editor, elements: Array<out PsiElement>): TextRange? {
        val document = editor.document
        val selection = editor.selectionModel
        val start = if (selection.hasSelection()) selection.selectionStart else editor.caretModel.offset
        val end = if (selection.hasSelection()) selection.selectionEnd else editor.caretModel.offset
        val result = GoSurrounders.surround(document.immutableCharSequence, start, end, kind) ?: return null
        document.replaceString(result.range.startOffset, result.range.endOffset, result.text)
        selection.removeSelection()
        return TextRange(result.caret, result.caret)
    }
}
