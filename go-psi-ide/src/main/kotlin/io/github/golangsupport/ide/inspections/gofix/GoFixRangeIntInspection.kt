package io.github.golangsupport.ide.inspections.gofix

import com.intellij.psi.PsiElement
import io.github.golangsupport.lang.psi.GoConditionalExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoForClause
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoIncDecStatement
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType

/**
 * `for i := 0; i < n; i++` → `for i := range n` (go1.22; modernize `rangeint`; GoLand's GO_SYNTAX_UPDATE text). The loop variable is not written
 * in the body; the limit is `int` (or an untyped integer constant) and loop-invariant: a constant, a local variable or parameter the
 * body does not write (whose address the function never takes), or `len` of one. An unused index becomes `for range n`.
 */
class GoFixRangeIntInspection : GoFixInspectionBase() {

    override val minGoVersion: String = "1.22"

    override fun plan(element: PsiElement): GoFixPlan? {
        if (element !is GoForClause || GoFixPsi.hasComments(element)) return null
        val loop = element.parent as? GoForStatement ?: return null
        val body = loop.block ?: return null
        val parts = GoFixPsi.forParts(element) ?: return null
        val init = GoFixPsi.unwrap(parts.init) as? GoShortVarDeclaration ?: return null
        val def = init.varDefinitionList.singleOrNull() ?: return null
        if (def.name == "_" || !GoFixPsi.isInt(init.expressionList.singleOrNull(), "0")) return null
        val cond = GoFixPsi.unparen(parts.cond) as? GoConditionalExpr ?: return null
        if (cond.lss == null || !GoFixPsi.refersTo(cond.left, def)) return null
        val post = GoFixPsi.unwrap(parts.post) as? GoIncDecStatement ?: return null
        if (post.inc == null || !GoFixPsi.refersTo(post.leftHandExprList.expressionList.singleOrNull(), def)) return null
        val limit = GoFixPsi.unparen(cond.right) ?: return null
        if (!isIntLimit(limit) || !isInvariant(limit, body, def)) return null
        if (GoFixPsi.writes(body, def)) return null
        val used = GoFixPsi.mentions(body, def)
        val header = if (used) "${def.name} := range ${limit.text}" else "range ${limit.text}"
        return GoFixPlan("for loop can be modernized using range over int", "Replace with range over int", listOf(GoFixPsi.replace(element, header)))
    }

    /** `int`, or an untyped integer constant (then the variable is `int` as before). */
    private fun isIntLimit(limit: GoExpression): Boolean {
        val type = GoFixPsi.typeOf(limit) as? GoBasicType ?: return false
        return type.kind == GoBasicKind.INT || type.kind == GoBasicKind.UNTYPED_INT
    }

    /** The limit is evaluated once by `range`: it must give the same value on every iteration of the old loop. */
    private fun isInvariant(limit: GoExpression, body: PsiElement, def: GoVarDefinition): Boolean {
        GoFixPsi.builtinArg(limit, "len")?.let { return isInvariantName(it, body, def) }
        return isInvariantName(limit, body, def)
    }

    private fun isInvariantName(e: GoExpression, body: PsiElement, def: GoVarDefinition): Boolean {
        val x = GoFixPsi.unparen(e)
        if (x is io.github.golangsupport.lang.psi.GoLiteral) return true
        val ref = x as? GoReferenceExpression ?: return false
        val target = GoFixPsi.target(ref) as? GoNamedElement ?: return false
        if (target == def) return false
        if (GoFixPsi.isConst(target)) return true
        if (ref.expression != null || !GoFixPsi.isLocal(target)) return false
        if (GoFixPsi.writes(body, target)) return false
        // A pointer taken anywhere in the function could write it from a call in the body.
        val owner = GoPsiUtil.functionOwner(target) ?: return false
        return GoFixPsi.references(owner, target).none { (it.parent as? io.github.golangsupport.lang.psi.GoUnaryExpr)?.and != null }
    }
}
