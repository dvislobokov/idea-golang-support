package io.github.golangsupport.ide.inspections.gofix

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.editor.GoEditText
import io.github.golangsupport.lang.psi.GoBreakStatement
import io.github.golangsupport.lang.psi.GoConditionalExpr
import io.github.golangsupport.lang.psi.GoContinueStatement
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoGotoStatement
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoLabeledStatement
import io.github.golangsupport.lang.psi.GoReturnStatement
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.semantic.psi.GoPsiUtil.block
import io.github.golangsupport.semantic.psi.GoPsiUtil.condition
import io.github.golangsupport.semantic.psi.GoPsiUtil.elseStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.initStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.rangeClause
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoTypePredicates

/**
 * A `for range` loop that searches a slice → `slices.Contains` / `slices.Index` (go1.21; modernize `slicescontains`):
 * - `for _, e := range s { if e == x { return true } }; return false` → `return slices.Contains(s, x)` (and the negated pair);
 * - `for i, e := range s { if e == x { return i } }; return -1` → `return slices.Index(s, x)`;
 * - `for _, e := range s { if e == x { …; break } }` → `if slices.Contains(s, x) { … }` when `…` does not use the loop variables and has no
 *   other branch statement.
 * `s` is a slice, `x` is pure, assignable to its element type and does not use the loop variables.
 */
class GoFixSlicesContainsInspection : GoFixInspectionBase() {

    override val minGoVersion: String = "1.21"

    override fun plan(element: PsiElement): GoFixPlan? {
        if (element !is GoForStatement || !GoFixPsi.inList(element)) return null
        val range = element.rangeClause ?: return null
        if (range.define == null) return null
        val defs = range.varDefinitionList
        val key = defs.getOrNull(0)?.takeIf { it.name != "_" }
        val value = defs.getOrNull(1)?.takeIf { it.name != "_" } ?: return null
        val s = range.expression ?: return null
        val elem = (GoFixPsi.typeOf(s).underlying() as? GoSliceType)?.elem ?: return null
        val test = GoFixPsi.statements(element.block).singleOrNull() as? GoIfStatement ?: return null
        if (test.initStatement != null || test.elseStatement != null) return null
        val needle = needle(test.condition, value, key) ?: return null
        val nt = GoFixPsi.typeOf(needle)
        if (!GoTypePredicates.isKnown(elem) || !GoTypePredicates.isKnown(nt) || !GoTypePredicates.assignable(nt, elem)) return null
        val q = GoFixPsi.qualifier(element, "slices") ?: return null
        val body = GoFixPsi.statements(test.block)
        val ret = body.singleOrNull() as? GoReturnStatement
        val after = GoFixPsi.next(element) as? GoReturnStatement
        if (ret != null && after != null) {
            if (GoFixPsi.hasComments(element)) return null
            val inside = ret.expressionList.singleOrNull()
            val outside = after.expressionList.singleOrNull()
            val call = when {
                key == null && GoFixPsi.isBuiltin(inside, "true") && GoFixPsi.isBuiltin(outside, "false") -> "$q.Contains(${s.text}, ${needle.text})"
                key == null && GoFixPsi.isBuiltin(inside, "false") && GoFixPsi.isBuiltin(outside, "true") -> "!$q.Contains(${s.text}, ${needle.text})"
                key != null && GoFixPsi.refersTo(inside, key) && GoFixPsi.isInt(outside, "-1") -> "$q.Index(${s.text}, ${needle.text})"
                else -> return null
            }
            val fn = if (call.contains(".Index(")) "slices.Index" else "slices.Contains"
            val edits = listOf(GoFixPsi.replaceStatement(element, "return $call"), GoFixPsi.replaceStatement(after, ""))
            return GoFixPlan("Loop can be simplified using $fn", "Replace loop by call to $fn", edits, listOf("slices"), range = GoFixPsi.keywordRange(element))
        }
        return breakForm(element, test, body, key, value, s, needle, q)
    }

    /** `if e == x { …; break }` → `if slices.Contains(s, x) { … }`. */
    private fun breakForm(loop: GoForStatement, test: GoIfStatement, body: List<PsiElement>, key: GoVarDefinition?, value: GoVarDefinition, s: GoExpression, needle: GoExpression, q: String): GoFixPlan? {
        if (key != null || body.size < 2) return null
        val brk = body.last() as? GoBreakStatement ?: return null
        if (brk.labelRef != null) return null
        val rest = body.dropLast(1)
        if (rest.any { s1 -> GoFixPsi.mentions(s1, value) || hasBranch(s1) }) return null
        val block = test.block ?: return null
        val lbrace = block.lbrace ?: return null
        val text = loop.containingFile.node.chars
        // Comments outside the kept statements would be lost.
        val keepStart = lbrace.textRange.endOffset
        val keepEnd = brk.textRange.startOffset
        if (PsiTreeUtil.findChildrenOfType(loop, com.intellij.psi.PsiComment::class.java).any { it.textRange.startOffset !in keepStart until keepEnd }) return null
        if (!GoEditText.startsLine(text, brk.textRange.startOffset)) return null
        val indent = GoFixPsi.indentOf(loop)
        val inner = GoEditText.body(loop.containingFile, keepStart, keepEnd, -1, "$indent\t")
        if (inner.isBlank()) return null
        val replacement = "if $q.Contains(${s.text}, ${needle.text}) {\n$inner\n$indent}"
        return GoFixPlan("Loop can be simplified using slices.Contains", "Replace loop by call to slices.Contains", listOf(GoFixPsi.replaceStatement(loop, replacement)),
            listOf("slices"), range = GoFixPsi.keywordRange(loop))
    }

    /** `e == x` or `x == e` with `e` the range value: `x` when it is pure and does not use the loop variables. */
    private fun needle(condition: GoExpression?, value: GoVarDefinition, key: GoVarDefinition?): GoExpression? {
        val cond = GoFixPsi.unparen(condition) as? GoConditionalExpr ?: return null
        if (cond.eql == null) return null
        val l = GoFixPsi.unparen(cond.left) ?: return null
        val r = GoFixPsi.unparen(cond.right) ?: return null
        val x = when {
            GoFixPsi.refersTo(l, value) -> r
            GoFixPsi.refersTo(r, value) -> l
            else -> return null
        }
        if (!GoFixPsi.isPure(x) || GoFixPsi.mentions(x, value) || (key != null && GoFixPsi.mentions(x, key))) return null
        return x
    }

    /** A branch statement, label or function literal anywhere in [statement] (moving it out of the loop could change where it jumps). */
    private fun hasBranch(statement: PsiElement): Boolean =
        statement is GoBreakStatement || statement is GoContinueStatement || statement is GoGotoStatement || statement is GoLabeledStatement ||
            PsiTreeUtil.findChildOfAnyType(statement, GoBreakStatement::class.java, GoContinueStatement::class.java, GoGotoStatement::class.java, GoLabeledStatement::class.java, GoFunctionLit::class.java) != null
}
