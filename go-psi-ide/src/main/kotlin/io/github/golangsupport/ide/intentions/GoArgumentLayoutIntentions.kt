package io.github.golangsupport.ide.intentions

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.TokenType
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.editor.GoEditText
import io.github.golangsupport.lang.psi.GoArgumentList
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoLiteralValue
import io.github.golangsupport.lang.psi.GoTypes

/** The arguments of a call or the elements of a composite literal, between their brackets. */
internal class GoListLayout private constructor(val list: PsiElement, val open: PsiElement, val close: PsiElement, val items: List<PsiElement>, val ellipsis: Boolean) {
    /** "arguments" or "elements", as GoLand names them in the intention text. */
    val noun: String get() = if (list is GoArgumentList) "arguments" else "elements"

    /** Each item starts a line of its own and the closing bracket too: the gofmt layout of a split list. */
    fun isSplit(text: CharSequence): Boolean =
        items.all { GoEditText.startsLine(text, it.textRange.startOffset) } && GoEditText.startsLine(text, close.textRange.startOffset)

    /** A line break somewhere between the brackets. */
    fun isMultiline(text: CharSequence): Boolean = text.subSequence(open.textRange.endOffset, close.textRange.startOffset).contains('\n')

    /** The text of item [i] with its `...` (the spread of the last argument). */
    fun itemText(i: Int, body: String): String = if (ellipsis && i == items.lastIndex) "$body..." else body

    companion object {
        private val PUNCTUATION = setOf(GoTypes.LPAREN, GoTypes.RPAREN, GoTypes.LBRACE, GoTypes.RBRACE, GoTypes.COMMA, GoTypes.ELLIPSIS)

        /** The list [element] is, or null when it is not one, is unfinished or holds a comment (it would move or vanish). */
        fun of(element: PsiElement): GoListLayout? {
            val (open, close) = when (element) {
                is GoArgumentList -> element.lparen to (element.rparen ?: return null)
                is GoLiteralValue -> element.lbrace to (element.rbrace ?: return null)
                else -> return null
            }
            if (PsiTreeUtil.findChildOfType(element, PsiComment::class.java) != null) return null
            val children = generateSequence(element.firstChild) { it.nextSibling }.toList()
            if (children.any { it.node.elementType == TokenType.ERROR_ELEMENT }) return null
            val items = children.filter { it !is PsiWhiteSpace && it.node.elementType !in PUNCTUATION && it.textLength > 0 }
            if (items.isEmpty()) return null
            return GoListLayout(element, open, close, items, children.any { it.node.elementType == GoTypes.ELLIPSIS })
        }

        /** The innermost list around [offset] that [accept]s, not beyond the block the caret is in (a function literal's body). */
        fun at(file: GoFile, offset: Int, accept: (GoListLayout) -> Boolean): GoListLayout? {
            var e: PsiElement? = GoIntentionText.leafAt(file, offset)
            while (e != null && e !is PsiFile && e !is GoBlock) {
                if (e is GoArgumentList || e is GoLiteralValue) of(e)?.takeIf(accept)?.let { return it }
                e = e.parent
            }
            return null
        }
    }
}

/**
 * Put arguments / elements on separate lines: each argument of the call (element of the composite literal) on a line of its own one
 * level deeper, a trailing comma after the last and the closing bracket on its own line (the gofmt layout). Lines inside an item (a
 * function literal's body) move with it.
 */
class GoSplitListIntention : GoCodeActionIntention() {
    override val defaultText: String = "Put arguments on separate lines"

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        val text = file.node.chars
        val layout = GoListLayout.at(file, offset) { it.items.size >= 2 && !it.isSplit(text) } ?: return null
        val indent = GoEditText.indentOf(text, layout.list.textRange.startOffset)
        val itemIndent = "$indent\t"
        val body = StringBuilder("\n")
        layout.items.forEachIndexed { i, item ->
            val delta = itemIndent.length - GoEditText.indentOf(text, item.textRange.startOffset).length
            body.append(itemIndent).append(layout.itemText(i, GoDeclarationView.reindented(item, delta))).append(",\n")
        }
        body.append(indent)
        val edit = GoEditPlan.Edit(layout.open.textRange.endOffset, layout.close.textRange.startOffset, body.toString())
        return GoEditPlan(listOf(edit), text = "Put ${layout.noun} on separate lines")
    }
}

/** Put arguments / elements on one line: `f(a, b)` from a call split over lines (no trailing comma); items spanning lines stay as they are. */
class GoJoinListIntention : GoCodeActionIntention() {
    override val defaultText: String = "Put arguments on one line"

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        val text = file.node.chars
        val layout = GoListLayout.at(file, offset) { l -> l.isMultiline(text) && l.items.none { '\n' in it.text } } ?: return null
        val joined = layout.items.mapIndexed { i, item -> layout.itemText(i, item.text) }.joinToString(", ")
        val edit = GoEditPlan.Edit(layout.open.textRange.endOffset, layout.close.textRange.startOffset, joined)
        return GoEditPlan(listOf(edit), text = "Put ${layout.noun} on one line")
    }
}
