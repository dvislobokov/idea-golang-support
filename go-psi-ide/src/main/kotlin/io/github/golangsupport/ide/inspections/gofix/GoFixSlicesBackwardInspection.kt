package io.github.golangsupport.ide.inspections.gofix

import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.ide.rules.builtin.simple.GoSimplePsi
import io.github.golangsupport.lang.psi.GoAddExpr
import io.github.golangsupport.lang.psi.GoArgumentList
import io.github.golangsupport.lang.psi.GoBinaryExpr
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoConditionalExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoForClause
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoIncDecStatement
import io.github.golangsupport.lang.psi.GoIndexOrSliceExpr
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoReturnStatement
import io.github.golangsupport.lang.psi.GoSendStatement
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.lang.psi.GoValue
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.semantic.types.GoSliceType

/**
 * `for i := len(s) - 1; i >= 0; i--` → `for i, v := range slices.Backward(s)` (go1.23): `s` is a local slice variable or parameter the body does
 * not write, `i` is not written in the body. Reads of `s[i]` as a value (an operand, an argument, a value assigned, sent or returned)
 * become `v`; the index stays when something else uses it (`_` otherwise). When the body may change elements of `s` (writes or takes the
 * address of `s[j]`, passes `s` to a call, aliases it), reads keep the `s[i]` form: `v` would be stale after such a write.
 */
class GoFixSlicesBackwardInspection : GoFixInspectionBase() {

    override val minGoVersion: String = "1.23"

    override fun plan(element: PsiElement): GoFixPlan? {
        if (element !is GoForClause || GoFixPsi.hasComments(element)) return null
        val loop = element.parent as? GoForStatement ?: return null
        val body = loop.block ?: return null
        val parts = GoFixPsi.forParts(element) ?: return null
        val init = GoFixPsi.unwrap(parts.init) as? GoShortVarDeclaration ?: return null
        val i = init.varDefinitionList.singleOrNull() ?: return null
        if (i.name == "_") return null
        val start = GoFixPsi.unparen(init.expressionList.singleOrNull()) as? GoAddExpr ?: return null
        if (!GoPsiTokens.isMinus(start) || !GoFixPsi.isInt(start.right, "1")) return null
        val s = GoFixPsi.builtinArg(start.left, "len") ?: return null
        val cond = GoFixPsi.unparen(parts.cond) as? GoConditionalExpr ?: return null
        if (cond.geq == null || !GoFixPsi.refersTo(cond.left, i) || !GoFixPsi.isInt(cond.right, "0")) return null
        val post = GoFixPsi.unwrap(parts.post) as? GoIncDecStatement ?: return null
        if (post.dec == null || !GoFixPsi.refersTo(post.leftHandExprList.expressionList.singleOrNull(), i)) return null
        val slice = GoFixPsi.target(s) as? GoNamedElement ?: return null
        if (GoFixPsi.name(s) == null || !GoFixPsi.isLocal(slice) || GoFixPsi.writes(body, slice) || GoFixPsi.writes(body, i)) return null
        if (GoFixPsi.typeOf(s).underlying() !is GoSliceType) return null
        val q = GoFixPsi.qualifier(loop, "slices") ?: return null
        val uses = GoFixPsi.references(body, i)
        // An element written (`s[j] = …`, `s[j]++`, `&s[j]`, `s[j].f = …`) or `s` handed elsewhere (a call, a closure, an alias) may change
        // what `s[i]` holds after the iteration started: `v` would be stale, so the reads stay.
        val stable = GoFixPsi.references(body, slice).all { isPlainRead(it) }
        val reads = if (!stable) emptyList() else uses.mapNotNull { ref -> (ref.parent as? GoIndexOrSliceExpr)?.takeIf { isValueRead(it, s, ref) } }
        val v = if (reads.isEmpty()) null else GoFixPsi.freshName(body, body, "v", "elem", "item")
        val replaced = if (v == null) emptyList() else reads
        val indexUsed = uses.size > replaced.size
        val header = when {
            v != null -> "${if (indexUsed) i.name else "_"}, $v := range $q.Backward(${s.text})"
            indexUsed -> "${i.name} := range $q.Backward(${s.text})"
            else -> "range $q.Backward(${s.text})"
        }
        val edits = listOf(GoFixPsi.replace(element, header)) + replaced.map { GoEditPlan.Edit(it.textRange.startOffset, it.textRange.endOffset, v!!) }
        return GoFixPlan("for loop can be modernized using slices.Backward", "Replace with range over slices.Backward", edits, listOf("slices"))
    }

    /** A use of the slice that cannot change its elements: `len(s)` / `cap(s)`, or `s[x]` standing where only its value is read. */
    private fun isPlainRead(ref: GoReferenceExpression): Boolean {
        var e: PsiElement = ref
        while (e.parent is io.github.golangsupport.lang.psi.GoParenthesesExpr) e = e.parent
        val p = e.parent
        if (p is GoArgumentList) return (p.parent as? GoCallExpr)?.let { GoFixPsi.isBuiltin(it.expression, "len") || GoFixPsi.isBuiltin(it.expression, "cap") } == true
        if (p !is GoIndexOrSliceExpr) return false
        val (operand, _) = GoSimplePsi.index(p) ?: return false
        return operand == e && isValueUse(p)
    }

    /** `s[i]` (not a slice expression) standing where only its value is read. */
    private fun isValueRead(index: GoIndexOrSliceExpr, s: GoExpression, ref: PsiElement): Boolean {
        val (operand, at) = GoSimplePsi.index(index) ?: return false
        if (at != ref || !GoFixPsi.same(operand, s)) return false
        return isValueUse(index)
    }

    /** [index] (possibly parenthesized) is an operand, an argument, or a value assigned, declared, sent or returned. */
    private fun isValueUse(index: GoIndexOrSliceExpr): Boolean {
        var e: PsiElement = index
        while (e.parent is io.github.golangsupport.lang.psi.GoParenthesesExpr) e = e.parent
        return when (val p = e.parent) {
            is GoArgumentList, is GoBinaryExpr, is GoReturnStatement, is GoSendStatement, is GoValue, is io.github.golangsupport.lang.psi.GoKey -> true
            is GoShortVarDeclaration -> p.expressionList.contains(e)
            is GoVarSpec -> p.expressionList.contains(e)
            is GoAssignmentStatement -> p.expressionList.contains(e)
            else -> false
        }
    }
}

/** Operator tokens of binary expressions. */
internal object GoPsiTokens {
    fun isMinus(e: GoBinaryExpr): Boolean = e.node.findChildByType(GoTypes.SUB) != null
}
