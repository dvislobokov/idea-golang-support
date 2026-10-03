package io.github.golangsupport.ide.rules.builtin.staticcheck

import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoExprCaseClause
import io.github.golangsupport.lang.psi.GoExprSwitchStatement
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoMulExpr
import io.github.golangsupport.semantic.infer.GoSizes
import io.github.golangsupport.semantic.psi.GoPsiUtil.block
import io.github.golangsupport.semantic.psi.GoPsiUtil.condition
import io.github.golangsupport.semantic.psi.GoPsiUtil.elseStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.tag
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoConstant
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypeParamType

/** staticcheck SA9006: `x << 40` for a 32-bit `x` (or `x <<= 40`) clears it: the shift happens before any widening. */
class GoDubiousShiftRule : GoStaticcheckExpressionRule() {
    override val id: String get() = ID
    override val title: String get() = "Dubious bit shifting of a fixed size integer value"
    override val description: String get() =
        "Shifting an <code>int8</code>...<code>uint64</code> by at least its width always yields 0: <code>uint64(uint32(n) &lt;&lt; 40)</code> " +
            "shifts in 32 bits. Convert first: <code>uint64(n) &lt;&lt; 40</code>."

    override fun checkExpression(expression: GoExpression, ctx: GoRuleContext) {
        val shift = GoShiftPsi.shiftOf(expression) ?: return
        val (size, amount) = dubious(shift, ctx) ?: return
        ctx.report(shift.node, "shifting $size-bit value by $amount bits will always clear it")
    }

    companion object {
        const val ID = "SA9006"

        /** (bits, amount) when [shift] shifts a fixed-size integer by at least its width. */
        internal fun dubious(shift: GoShiftPsi.Shift, ctx: GoRuleContext): Pair<Int, Long>? {
            val bits = fixedBits(ctx.typeOf(shift.x)) ?: return null
            val amount = GoShiftPsi.amount(shift.y, ctx) ?: return null
            return if (amount >= bits) bits to amount else null
        }

        /** The width of `int8`...`uint64` (not `int`, `uint`, `uintptr`, whose size depends on the target). */
        internal fun fixedBits(type: GoType): Int? {
            if (type is GoTypeParamType) return null
            return when ((type.underlying() as? GoBasicType)?.kind) {
                GoBasicKind.INT8, GoBasicKind.UINT8 -> 8
                GoBasicKind.INT16, GoBasicKind.UINT16 -> 16
                GoBasicKind.INT32, GoBasicKind.UINT32 -> 32
                GoBasicKind.INT64, GoBasicKind.UINT64 -> 64
                else -> null
            }
        }
    }
}

/** govet `shift`: a shift by at least the operand's width (sizes of 64-bit targets); dead branches of constant conditions are skipped. */
class GoVetShiftRule : GoVetExpressionRule() {
    override val id: String get() = "govet:shift"
    override val title: String get() = "Shift that equals or exceeds the width of the integer"
    override val description: String get() =
        "govet <code>shift</code>: <code>x &lt;&lt; 64</code> for a 64-bit <code>x</code> is always 0. Shifts of constants and code in " +
            "branches of constant-false conditions are left alone. Where SA9006 reports the same shift, this rule stays quiet."

    override fun checkExpression(expression: GoExpression, ctx: GoRuleContext) {
        val shift = GoShiftPsi.shiftOf(expression) ?: return
        if (ctx.semantic.constantValue(shift.x) != null) return // ^uint(0) >> 63 and other bit tricks
        val amount = GoShiftPsi.amount(shift.y, ctx) ?: return
        val type = ctx.typeOf(shift.x)
        val sizes = (GoExpressionPsi.typeSet(type) ?: return).map { t ->
            val basic = t.underlying() as? GoBasicType ?: return
            if (!basic.kind.isInteger || basic.isUntyped) return
            8 * (GoSizes.sizeof(basic) ?: return)
        }.toSet()
        val min = sizes.minOrNull() ?: return
        if (amount < min) return
        if (GoShiftPsi.isDead(shift.node, ctx)) return
        if (GoDubiousShiftRule.dubious(shift, ctx) != null && GoStaticcheckPsi.enabled(GoDubiousShiftRule.ID, ctx)) return
        val may = if (sizes.size > 1) "may be " else ""
        ctx.report(shift.node, "${GoExpressionPsi.render(shift.x)} ($may$min bits) too small for shift of $amount")
    }
}

internal object GoShiftPsi {
    /** A shift `x << y` / `x >> y` or a shift assignment `x <<= y`; problems go on [node]. */
    class Shift(val node: PsiElement, val x: GoExpression, val y: GoExpression)

    /** The shift [e] is, or whose right-hand side [e] is (`x <<= e`, single assignment only). */
    fun shiftOf(e: GoExpression): Shift? {
        if (e is GoMulExpr) {
            if (e.shl == null && e.shr == null) return null
            return Shift(e, e.left, e.right ?: return null)
        }
        val assignment = e.parent as? GoAssignmentStatement ?: return null
        val op = assignment.assignOp
        if (op.shlAssign == null && op.shrAssign == null) return null
        val lhs = assignment.leftHandExprList.expressionList.singleOrNull() ?: return null
        if (assignment.expressionList.singleOrNull() !== e) return null
        return Shift(assignment, lhs, e)
    }

    /** The constant shift count [y], or null. */
    fun amount(y: GoExpression, ctx: GoRuleContext): Long? {
        val c = ctx.semantic.constantValue(y) ?: return null
        val v = (if (c is GoConstant.Int) c.value else c.toBigInteger()) ?: return null
        return if (v.bitLength() < 63) v.toLong() else null
    }

    /**
     * vet's `updateDead`: [e] is in the body of an `if` whose condition is constant false (or its `else` when true), or in a `case` of a
     * tagless switch whose expressions are all constant false, or of a switch on a constant integer that none of its values match.
     */
    fun isDead(e: PsiElement, ctx: GoRuleContext): Boolean {
        var child = e
        var p = e.parent
        while (p != null && p !is GoFile) {
            when (p) {
                is GoIfStatement -> {
                    val cond = (p.condition?.let { ctx.semantic.constantValue(it) } as? GoConstant.Bool)?.value
                    if (cond != null && (child === p.block && !cond || child === p.elseStatement && cond)) return true
                }
                is GoExprCaseClause -> if (child !in p.expressionList && isDeadCase(p, ctx)) return true
            }
            child = p
            p = p.parent
        }
        return false
    }

    private fun isDeadCase(clause: GoExprCaseClause, ctx: GoRuleContext): Boolean {
        val cases = clause.expressionList
        if (cases.isEmpty()) return false
        val switch = clause.parent as? GoExprSwitchStatement ?: return false
        val tag = switch.tag
        if (tag == null) return cases.all { (ctx.semantic.constantValue(it) as? GoConstant.Bool)?.value == false }
        val value = (ctx.semantic.constantValue(tag) as? GoConstant.Int)?.value ?: return false
        return cases.all { c -> (ctx.semantic.constantValue(c) as? GoConstant.Int)?.value.let { it != null && it != value } }
    }
}
