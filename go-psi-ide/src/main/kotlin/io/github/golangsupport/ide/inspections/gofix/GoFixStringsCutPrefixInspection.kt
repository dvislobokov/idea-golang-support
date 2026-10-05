package io.github.golangsupport.ide.inspections.gofix

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoConditionalExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil.block
import io.github.golangsupport.semantic.psi.GoPsiUtil.condition
import io.github.golangsupport.semantic.psi.GoPsiUtil.initStatement
import io.github.golangsupport.semantic.types.GoConstant

/**
 * `strings.HasPrefix` + `strings.TrimPrefix` → `strings.CutPrefix` (go1.20; modernize `stringscutprefix`), also the `Suffix` pair and package `bytes`:
 * - `if strings.HasPrefix(s, pre) { … strings.TrimPrefix(s, pre) … }` → `if after, ok := strings.CutPrefix(s, pre); ok { … after … }`;
 * - `if after := strings.TrimPrefix(s, pre); after != s { … }` → `if after, ok := strings.CutPrefix(s, pre); ok { … }`.
 * `s` and `pre` are pure and not written inside the `if`. The second form needs `pre` to be a non-empty string constant: with `pre == ""`
 * `after != s` is false where `ok` is true.
 */
class GoFixStringsCutPrefixInspection : GoFixInspectionBase() {

    override val minGoVersion: String = "1.20"

    private class Kind(val has: String, val trim: String, val cut: String, val result: String)

    private val kinds = listOf(Kind("HasPrefix", "TrimPrefix", "CutPrefix", "after"), Kind("HasSuffix", "TrimSuffix", "CutSuffix", "before"))

    override fun plan(element: PsiElement): GoFixPlan? {
        if (element !is GoIfStatement || GoFixPsi.hasComments(element.condition ?: return null)) return null
        return if (element.initStatement == null) hasForm(element) else trimForm(element)
    }

    /** `if strings.HasPrefix(s, pre) { … strings.TrimPrefix(s, pre) … }`. */
    private fun hasForm(statement: GoIfStatement): GoFixPlan? {
        val cond = GoFixPsi.unparen(statement.condition) as? GoCallExpr ?: return null
        val (pkg, kind) = call(cond, kinds.map { it.has }) ?: return null
        val (s, pre) = GoFixPsi.args(cond)?.takeIf { it.size == 2 } ?: return null
        if (!invariant(pre, statement)) return null
        val block = statement.block ?: return null
        val trims = PsiTreeUtil.findChildrenOfType(block, GoCallExpr::class.java).filter { c ->
            call(c, listOf(kind.trim))?.first == pkg && GoFixPsi.args(c)?.let { it.size == 2 && GoFixPsi.same(it[0], s) && GoFixPsi.same(it[1], pre) } == true
        }
        if (trims.isEmpty()) return null
        if (!invariant(s, statement) && !trimAssignedBack(s, trims, statement)) return null
        val result = GoFixPsi.freshName(statement, statement, kind.result, "rest") ?: return null
        val ok = GoFixPsi.freshName(statement, statement, "ok", "found") ?: return null
        val qualifier = (GoFixPsi.unparen(cond.expression) as GoReferenceExpression).expression!!.text
        val header = "$result, $ok := $qualifier.${kind.cut}(${s.text}, ${pre.text}); $ok"
        val edits = listOf(GoFixPsi.replace(cond, header)) + trims.map { GoEditPlan.Edit(it.textRange.startOffset, it.textRange.endOffset, result) }
        return plan(kind, edits, statement, cond)
    }

    /** `if after := strings.TrimPrefix(s, pre); after != s { … }`. */
    private fun trimForm(statement: GoIfStatement): GoFixPlan? {
        val init = GoFixPsi.unwrap(statement.initStatement) as? GoShortVarDeclaration ?: return null
        val def = init.varDefinitionList.singleOrNull() ?: return null
        if (def.name == "_") return null
        val trim = GoFixPsi.unparen(init.expressionList.singleOrNull()) as? GoCallExpr ?: return null
        val (_, kind) = call(trim, kinds.map { it.trim }) ?: return null
        val (s, pre) = GoFixPsi.args(trim)?.takeIf { it.size == 2 } ?: return null
        if (!invariant(s, statement) || !invariant(pre, statement) || !nonEmptyConstant(pre)) return null
        val cond = GoFixPsi.unparen(statement.condition) as? GoConditionalExpr ?: return null
        if (cond.neq == null) return null
        val matches = (GoFixPsi.refersTo(cond.left, def) && GoFixPsi.same(cond.right, s)) || (GoFixPsi.refersTo(cond.right, def) && GoFixPsi.same(cond.left, s))
        if (!matches) return null
        val ok = GoFixPsi.freshName(statement, statement, "ok", "found") ?: return null
        val qualifier = (GoFixPsi.unparen(trim.expression) as GoReferenceExpression).expression!!.text
        val condition = statement.condition ?: return null
        val edits = listOf(GoFixPsi.replace(init, "${def.name}, $ok := $qualifier.${kind.cut}(${s.text}, ${pre.text})"), GoFixPsi.replace(condition, ok))
        return plan(kind, edits, statement, init)
    }

    private fun plan(kind: Kind, edits: List<GoEditPlan.Edit>, statement: GoIfStatement, highlight: PsiElement): GoFixPlan {
        val pair = "${kind.has} + ${kind.trim}"
        return GoFixPlan("$pair can be simplified to ${kind.cut}", "Replace $pair with ${kind.cut}", edits, range = GoFixPsi.rangeIn(statement, highlight))
    }

    /** `strings.Name(…)` / `bytes.Name(…)` with a name of [names]: the package path and the kind. */
    private fun call(call: GoCallExpr, names: List<String>): Pair<String, Kind>? {
        for (path in listOf("strings", "bytes")) {
            val name = GoFixPsi.packageFunction(call, path, names.toSet()) ?: continue
            return path to kinds.first { it.has == name || it.trim == name }
        }
        return null
    }

    /** A string constant (literal or named) with at least one byte. */
    private fun nonEmptyConstant(e: GoExpression): Boolean =
        (GoSemanticService.getInstance(e.project).constantValue(e) as? GoConstant.Str)?.value?.isNotEmpty() == true

    /**
     * `s = strings.TrimPrefix(s, pre)` as the only write of `s` inside [statement] and the only trim (GoLand reports this form too): the
     * assignment becomes `s = after`, and nothing else sees a changed `s` before the trim.
     */
    private fun trimAssignedBack(s: GoExpression, trims: List<GoCallExpr>, statement: GoIfStatement): Boolean {
        if (!GoFixPsi.isPure(s) || trims.size != 1) return false
        val def = (GoFixPsi.unparen(s) as? GoReferenceExpression)?.let { GoFixPsi.target(it) as? GoNamedElement } ?: return false
        val assignment = trims[0].parent as? GoAssignmentStatement ?: return false
        if (assignment.expressionList.singleOrNull() !== trims[0] || assignment.assignOp.assign == null) return false
        val target = assignment.leftHandExprList.expressionList.singleOrNull() ?: return false
        if (!GoFixPsi.same(target, s)) return false
        val writes = GoFixPsi.references(statement, def).filter(GoFixPsi::isWrite)
        return writes.size == 1 && GoFixPsi.unparen(writes[0]) === GoFixPsi.unparen(target)
    }

    /** A pure expression whose variables are not written inside [statement]. */
    private fun invariant(e: GoExpression, statement: GoIfStatement): Boolean {
        if (!GoFixPsi.isPure(e)) return false
        val refs = PsiTreeUtil.findChildrenOfType(e, GoReferenceExpression::class.java) + listOfNotNull(GoFixPsi.unparen(e) as? GoReferenceExpression)
        return refs.none { r -> (GoFixPsi.target(r) as? GoNamedElement)?.let { GoFixPsi.writes(statement, it) } == true }
    }
}
