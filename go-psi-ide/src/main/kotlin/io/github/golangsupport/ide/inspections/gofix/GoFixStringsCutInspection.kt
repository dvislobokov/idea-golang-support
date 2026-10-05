package io.github.golangsupport.ide.inspections.gofix

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.ide.rules.builtin.simple.GoSimplePsi
import io.github.golangsupport.lang.psi.GoAddExpr
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoConditionalExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoIndexOrSliceExpr
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.semantic.psi.GoPsiUtil.block
import io.github.golangsupport.semantic.psi.GoPsiUtil.condition
import io.github.golangsupport.semantic.psi.GoPsiUtil.elseStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.initStatement

/**
 * `strings.Index` + slicing → `strings.Cut` (go1.18; modernize `stringscut`), also package `bytes`:
 * `i := strings.Index(s, sep); if i >= 0 { use(s[:i], s[i+len(sep):]) }` → `before, after, ok := strings.Cut(s, sep); if ok { use(before, after) }`,
 * with the index in the `if` header or in the statement right before it. Every use of `i` is `s[:i]` or `s[i+len(sep):]` (`s[i+1:]` for a
 * one-byte literal `sep`) inside the `if` block; the condition is `i >= 0`, `i != -1` or `i > -1`; nothing else (the `else` branch, the
 * statements after the `if`) uses `i`; `s` and `sep` are pure and not written inside the `if`.
 */
class GoFixStringsCutInspection : GoFixInspectionBase() {

    override val minGoVersion: String = "1.18"

    override fun plan(element: PsiElement): GoFixPlan? {
        if (element !is GoIfStatement) return null
        val inHeader = element.initStatement != null
        val decl = (if (inHeader) element.initStatement else GoFixPsi.previous(element).takeIf { GoFixPsi.inList(element) }) ?: return null
        val init = GoFixPsi.unwrap(decl) as? GoShortVarDeclaration ?: return null
        val i = init.varDefinitionList.singleOrNull() ?: return null
        if (i.name == "_") return null
        val index = GoFixPsi.unparen(init.expressionList.singleOrNull()) as? GoCallExpr ?: return null
        val pkg = listOf("strings", "bytes").firstOrNull { GoFixPsi.packageFunction(index, it, setOf("Index")) != null } ?: return null
        val (s, sep) = GoFixPsi.args(index)?.takeIf { it.size == 2 } ?: return null
        if (!invariant(s, element) || !invariant(sep, element) || !isFound(element.condition, i)) return null
        if (GoFixPsi.hasComments(decl) || GoFixPsi.hasComments(element.condition!!)) return null
        val block = element.block ?: return null
        element.elseStatement?.let { if (GoFixPsi.mentions(it, i)) return null }
        if (!inHeader && GoFixPsi.following(element).any { GoFixPsi.mentions(it, i) }) return null
        val befores = ArrayList<GoIndexOrSliceExpr>()
        val afters = ArrayList<GoIndexOrSliceExpr>()
        for (ref in GoFixPsi.references(block, i)) {
            val slice = PsiTreeUtil.getParentOfType(ref, GoIndexOrSliceExpr::class.java) ?: return null
            when {
                isBefore(slice, ref, s) -> befores += slice
                isAfter(slice, ref, s, sep, i) -> afters += slice
                else -> return null
            }
        }
        if (befores.isEmpty() && afters.isEmpty()) return null
        // The new names live in the `if` (header form) or in the enclosing block from here on.
        val scope = if (inHeader) element else decl.parent
        val before = if (befores.isEmpty()) "_" else GoFixPsi.freshName(scope, decl, "before") ?: return null
        val after = if (afters.isEmpty()) "_" else GoFixPsi.freshName(scope, decl, "after") ?: return null
        val ok = GoFixPsi.freshName(scope, decl, "ok", "found") ?: return null
        val qualifier = (GoFixPsi.unparen(index.expression) as GoReferenceExpression).expression!!.text
        val edits = ArrayList<GoEditPlan.Edit>()
        edits += GoFixPsi.replace(init, "$before, $after, $ok := $qualifier.Cut(${s.text}, ${sep.text})")
        edits += GoFixPsi.replace(element.condition!!, ok)
        befores.mapTo(edits) { GoFixPsi.replace(it, before) }
        afters.mapTo(edits) { GoFixPsi.replace(it, after) }
        val range = if (inHeader) GoFixPsi.rangeIn(element, index) else GoFixPsi.keywordRange(element)
        return GoFixPlan("$pkg.Index can be simplified using $pkg.Cut", "Replace $pkg.Index with $pkg.Cut", edits, range = range)
    }

    /** `i >= 0`, `i != -1`, `i > -1`. */
    private fun isFound(condition: GoExpression?, i: GoVarDefinition): Boolean {
        val c = GoFixPsi.unparen(condition) as? GoConditionalExpr ?: return false
        if (!GoFixPsi.refersTo(c.left, i)) return false
        return (c.geq != null && GoFixPsi.isInt(c.right, "0")) || ((c.neq != null || c.gtr != null) && GoFixPsi.isInt(c.right, "-1"))
    }

    /** `s[:i]`. */
    private fun isBefore(slice: GoIndexOrSliceExpr, ref: GoReferenceExpression, s: GoExpression): Boolean {
        val parts = GoSimplePsi.slice(slice) ?: return false
        return parts.low == null && GoFixPsi.unparen(parts.high) == ref && GoFixPsi.same(parts.operand, s)
    }

    /** `s[i+len(sep):]`, or `s[i+1:]` when `sep` is a one-byte literal. */
    private fun isAfter(slice: GoIndexOrSliceExpr, ref: GoReferenceExpression, s: GoExpression, sep: GoExpression, i: GoVarDefinition): Boolean {
        val parts = GoSimplePsi.slice(slice) ?: return false
        if (parts.high != null || !GoFixPsi.same(parts.operand, s)) return false
        val low = GoFixPsi.unparen(parts.low) as? GoAddExpr ?: return false
        if (low.node.findChildByType(io.github.golangsupport.lang.psi.GoTypes.ADD) == null || GoFixPsi.unparen(low.left) != ref || !GoFixPsi.refersTo(ref, i)) return false
        val length = low.right
        GoFixPsi.builtinArg(length, "len")?.let { return GoFixPsi.same(it, sep) }
        return GoFixPsi.isInt(length, "1") && isOneByteLiteral(sep)
    }

    private fun isOneByteLiteral(e: GoExpression): Boolean {
        val text = (GoFixPsi.unparen(e) as? GoStringLiteral)?.text ?: return false
        return text.length == 3 && text[1] != '\\' && text[1].code in 0x20..0x7e
    }

    /** A pure expression whose variables are not written inside [statement]. */
    private fun invariant(e: GoExpression, statement: GoIfStatement): Boolean {
        if (!GoFixPsi.isPure(e)) return false
        val refs = PsiTreeUtil.findChildrenOfType(e, GoReferenceExpression::class.java) + listOfNotNull(GoFixPsi.unparen(e) as? GoReferenceExpression)
        return refs.none { r -> (GoFixPsi.target(r) as? GoNamedElement)?.let { GoFixPsi.writes(statement, it) } == true }
    }
}
