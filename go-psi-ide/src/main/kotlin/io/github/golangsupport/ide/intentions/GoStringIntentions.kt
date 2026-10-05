package io.github.golangsupport.ide.intentions

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoAddExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoStringLiteral

/**
 * Join concatenated string literals: `"a" + "b" + s + "c" + "d"` → `"ab" + s + "cd"`. Every run of adjacent literals in the `+` chain
 * around the caret becomes one literal: interpreted bodies are glued as written (Go escapes have a fixed length, so gluing never
 * changes one), raw parts of a mixed run are quoted first (`strconv.Quote`), a run of raw literals stays raw. Parenthesized operands
 * are not entered; a chain with a comment is left alone.
 */
class GoJoinStringLiteralsIntention : GoCodeActionIntention() {
    override val defaultText: String = "Join concatenated string literals"

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        val leaf = GoIntentionText.leafAt(file, offset) ?: return null
        var top: PsiElement = (leaf.parent as? GoStringLiteral) ?: (leaf.parent as? GoAddExpr)?.takeIf { leaf == it.add } ?: return null
        while (true) {
            val parent = top.parent as? GoAddExpr ?: break
            if (parent.add == null) break
            top = parent
        }
        if (top !is GoAddExpr || PsiTreeUtil.findChildOfType(top, PsiComment::class.java) != null) return null
        val operands = operands(top) ?: return null
        val parts = ArrayList<String>()
        var joinedAny = false
        var i = 0
        while (i < operands.size) {
            var j = i
            while (j < operands.size && operands[j] is GoStringLiteral) j++
            if (j - i >= 2) {
                parts += join(operands.subList(i, j).map { it as GoStringLiteral }) ?: return null
                joinedAny = true
                i = j
            } else {
                parts += operands[i].text
                i++
            }
        }
        if (!joinedAny) return null
        return GoEditPlan(listOf(GoEditPlan.Edit(top.textRange.startOffset, top.textRange.endOffset, parts.joinToString(" + "))))
    }

    /** The operands of a `+` chain, left to right; null when one is missing (an unfinished expression). */
    private fun operands(e: GoExpression): List<GoExpression>? {
        if (e !is GoAddExpr || e.add == null) return listOf(e)
        val right = e.right ?: return null
        return (operands(e.left) ?: return null) + (operands(right) ?: return null)
    }

    private fun join(literals: List<GoStringLiteral>): String? {
        val bodies = literals.map { l -> l.text.takeIf { it.length >= 2 && it.last() == it.first() }?.let { it.substring(1, it.length - 1) } ?: return null }
        if (literals.all { it.rawString != null }) return "`" + bodies.joinToString("") { it.replace("\r", "") } + "`"
        val out = StringBuilder("\"")
        literals.forEachIndexed { i, l -> out.append(if (l.rawString != null) GoStringQuotes.quote(bodies[i].replace("\r", "")).let { it.substring(1, it.length - 1) } else bodies[i]) }
        return out.append('"').toString()
    }
}
