package io.github.golangsupport.ide.inspections.gofix

import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.rules.builtin.simple.GoSimplePsi
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoConditionalExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.block
import io.github.golangsupport.semantic.psi.GoPsiUtil.condition
import io.github.golangsupport.semantic.psi.GoPsiUtil.elseStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.initStatement
import io.github.golangsupport.semantic.types.GoTypePredicates

/**
 * Conditional assignment → `min` / `max` (go1.21; modernize `minmax`):
 * `if a < b { x = a } else { x = b }` → `x = min(a, b)`, and `if x > y { x = y }` → `x = min(x, y)`.
 * Integers and strings only (floats differ for NaN and -0), pure operands, no comments, `min` / `max` not redeclared.
 */
class GoFixMinMaxInspection : GoFixInspectionBase() {

    override val minGoVersion: String = "1.21"

    override fun plan(element: PsiElement): GoFixPlan? {
        if (element !is GoIfStatement || element.initStatement != null || !GoFixPsi.inList(element)) return null
        val cond = GoFixPsi.unparen(element.condition) as? GoConditionalExpr ?: return null
        val less = when {
            cond.lss != null || cond.leq != null -> true
            cond.gtr != null || cond.geq != null -> false
            else -> return null
        }
        val a = GoFixPsi.unparen(cond.left) ?: return null
        val b = GoFixPsi.unparen(cond.right) ?: return null
        val (lhs, value) = single(element.block) ?: return null
        if (!GoFixPsi.isPure(a) || !GoFixPsi.isPure(b) || !GoFixPsi.isPure(lhs)) return null
        val elseStatement = element.elseStatement
        val fn: String
        val ifElse = elseStatement != null
        if (elseStatement != null) {
            val (lhs2, value2) = single(elseStatement.statement as? GoBlock ?: return null) ?: return null
            if (!GoFixPsi.same(lhs, lhs2)) return null
            fn = when {
                GoFixPsi.same(value, a) && GoFixPsi.same(value2, b) -> if (less) "min" else "max"
                GoFixPsi.same(value, b) && GoFixPsi.same(value2, a) -> if (less) "max" else "min"
                else -> return null
            }
        } else {
            fn = when {
                GoFixPsi.same(lhs, a) && GoFixPsi.same(value, b) -> if (less) "max" else "min"
                GoFixPsi.same(lhs, b) && GoFixPsi.same(value, a) -> if (less) "min" else "max"
                else -> return null
            }
        }
        if (GoFixPsi.hasComments(element) || !GoFixPsi.isUniverse(element, fn) || !orderedOperands(a, b)) return null
        val target = GoFixPsi.typeOf(lhs)
        val operand = listOf(GoFixPsi.typeOf(a), GoFixPsi.typeOf(b)).firstOrNull { !GoTypePredicates.isUntyped(it) } ?: return null
        if (!GoTypePredicates.identical(target, operand)) return null
        val message = if (ifElse) "if/else statement can be modernized using $fn" else "if statement can be modernized using $fn"
        val text = "${lhs.text} = $fn(${a.text}, ${b.text})"
        return GoFixPlan(message, "Replace with '$fn'", listOf(GoFixPsi.replaceStatement(element, text)), range = GoFixPsi.keywordRange(element))
    }

    /** Both operands of one ordered type (integer or string), or a typed operand and an untyped constant. */
    private fun orderedOperands(a: GoExpression, b: GoExpression): Boolean {
        val ta = GoFixPsi.typeOf(a)
        val tb = GoFixPsi.typeOf(b)
        val untypedA = GoTypePredicates.isUntyped(ta)
        val untypedB = GoTypePredicates.isUntyped(tb)
        return when {
            untypedA && untypedB -> false
            untypedA -> GoFixPsi.isOrderedBasic(tb, floats = false) && GoFixPsi.isOrderedBasic(ta, floats = false)
            untypedB -> GoFixPsi.isOrderedBasic(ta, floats = false) && GoFixPsi.isOrderedBasic(tb, floats = false)
            else -> GoTypePredicates.identical(ta, tb) && GoFixPsi.isOrderedBasic(ta, floats = false)
        }
    }

    /** The only statement of [block], a plain `lhs = value`. */
    private fun single(block: GoBlock?): Pair<GoExpression, GoExpression>? {
        val statement = GoFixPsi.statements(block).singleOrNull() ?: return null
        return GoSimplePsi.simpleAssignment(GoFixPsi.unwrap(statement))
    }
}
