package io.github.golangsupport.ide.rules.builtin.simple

import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.lang.psi.GoConditionalExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoIncDecStatement
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.semantic.psi.GoPsiUtil.forClause
import io.github.golangsupport.semantic.psi.GoPsiUtil.rangeClause
import io.github.golangsupport.semantic.types.GoArrayType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypePredicates

/**
 * staticcheck S1001: a loop copying element by element (`for i, x := range src { dst[i] = x }`, `for i := range src { dst[i] = src[i] }`,
 * `for i := 0; i < len(src); i++ { dst[i] = src[i] }`) is `copy(dst, src)`; between arrays of the same type, `dst = src`.
 */
class GoLoopCopyRule : GoSimpleStatementRule() {
    override val id: String get() = "S1001"
    override val title: String get() = "Replace for loop with call to copy"
    override val description: String get() =
        "Use <code>copy()</code> for copying elements from one slice to another. For arrays of identical size, you can use simple assignment."

    private class Match(val loop: GoForStatement, val dst: GoExpression, val src: GoExpression, val arrays: Boolean, val dstPointer: Boolean,
                        val srcPointer: Boolean, val dstArray: Boolean, val srcArray: Boolean)

    override fun checkStatement(statement: GoStatement, ctx: GoRuleContext) {
        if (statement !is GoForStatement) return
        val m = match(statement, ctx) ?: return
        if (m.arrays) {
            ctx.report(statement, GoSimplePsi.keywordRange(statement), "should copy arrays using assignment instead of using a loop",
                *GoRewriteFix.offer("Replace loop with assignment", statement, ctx, ::fix))
        } else {
            if (GoSimplePsi.shadowsBuiltin(statement.containingFile, "copy")) return
            val to = if (m.dstArray) "to[:]" else "to"
            val from = if (m.srcArray) "from[:]" else "from"
            ctx.report(statement, GoSimplePsi.keywordRange(statement), "should use copy($to, $from) instead of a loop",
                *GoRewriteFix.offer("Replace loop with call to copy()", statement, ctx, ::fix))
        }
    }

    private fun fix(element: PsiElement, ctx: GoRuleContext): List<GoEditPlan.Edit>? {
        val loop = element as? GoForStatement ?: return null
        if (GoSimplePsi.hasComments(loop)) return null
        val m = match(loop, ctx) ?: return null
        val text = if (m.arrays) {
            (if (m.dstPointer) "*" else "") + m.dst.text + " = " + (if (m.srcPointer) "*" else "") + m.src.text
        } else {
            "copy(" + m.dst.text + (if (m.dstArray) "[:]" else "") + ", " + m.src.text + (if (m.srcArray) "[:]" else "") + ")"
        }
        return GoSimplePsi.replace(loop, text)
    }

    private fun match(loop: GoForStatement, ctx: GoRuleContext): Match? {
        val body = loop.block?.statementList?.singleOrNull() ?: return null
        val (lhs, rhs) = GoSimplePsi.simpleAssignment(body) ?: return null
        val (dst, dstIndex) = GoSimplePsi.index(lhs) ?: return null
        val key: PsiElement
        val value: PsiElement?
        val src: GoExpression
        val range = loop.rangeClause
        if (range != null) {
            val defs = range.varDefinitionList
            if (range.define == null || defs.isEmpty() || defs.size > 2) return null
            key = defs[0]
            value = defs.getOrNull(1)
            src = range.expression ?: return null
            if (value != null) {
                if (GoSimplePsi.identName(GoSimplePsi.unparen(rhs)) != value.text) return null
            } else {
                val (rhsSrc, rhsIndex) = GoSimplePsi.index(rhs) ?: return null
                if (GoSimplePsi.norm(rhsSrc) != GoSimplePsi.norm(src) || GoSimplePsi.identName(GoSimplePsi.unparen(rhsIndex)) != key.text) return null
            }
        } else {
            val parts = GoSimplePsi.forParts(loop.forClause ?: return null) ?: return null
            val init = parts.init as? GoShortVarDeclaration ?: return null
            key = init.varDefinitionList.singleOrNull() ?: return null
            if (!GoSimplePsi.intLiteral(init.expressionList.singleOrNull(), "0")) return null
            val cond = GoSimplePsi.unparen(parts.cond) as? GoConditionalExpr ?: return null
            if (cond.lss == null || GoSimplePsi.identName(GoSimplePsi.unparen(cond.left)) != key.text) return null
            val len = GoSimplePsi.unparen(cond.right)
            if (!GoSimplePsi.isBuiltinCall(len, "len", ctx)) return null
            src = GoSimplePsi.args(len as io.github.golangsupport.lang.psi.GoCallExpr).singleOrNull() as? GoExpression ?: return null
            val post = parts.post as? GoIncDecStatement ?: return null
            if (post.inc == null || GoSimplePsi.identName(post.leftHandExprList?.expressionList?.singleOrNull()) != key.text) return null
            val (rhsSrc, rhsIndex) = GoSimplePsi.index(rhs) ?: return null
            if (GoSimplePsi.norm(rhsSrc) != GoSimplePsi.norm(src) || GoSimplePsi.identName(GoSimplePsi.unparen(rhsIndex)) != key.text) return null
            value = null
        }
        if (GoSimplePsi.identName(GoSimplePsi.unparen(dstIndex)) != key.text) return null
        val names = listOfNotNull(key.text, value?.text).filter { it != "_" }
        if (!invariant(dst, names) || !invariant(src, names)) return null
        val tSrc = ctx.typeOf(src)
        val tDst = ctx.typeOf(dst)
        if (!GoTypePredicates.isKnown(tSrc) || !GoTypePredicates.isKnown(tDst)) return null
        val s = elem(tSrc) ?: return null
        val d = elem(tDst) ?: return null
        if (!GoTypePredicates.identical(s.elem, d.elem)) return null
        val arrays = s.array && d.array && GoTypePredicates.identical(s.container, d.container)
        return Match(loop, dst, src, arrays, d.pointer, s.pointer, d.array, s.array)
    }

    /** No side effects and no mention of the loop variables (`a[i][i] = v` is not a copy). */
    private fun invariant(e: GoExpression, names: List<String>): Boolean =
        GoSimplePsi.isPure(e) && e !is GoUnaryExpr && names.none { GoSimplePsi.mentions(e, it) }

    private class Elem(val elem: GoType, val container: GoType, val array: Boolean, val pointer: Boolean)

    private fun elem(t: GoType): Elem? = when (val u = t.underlying()) {
        is GoSliceType -> Elem(u.elem, t, false, false)
        is GoArrayType -> Elem(u.elem, t, true, false)
        is GoPointerType -> (u.elem.underlying() as? GoArrayType)?.let { Elem(it.elem, u.elem, true, true) }
        else -> null
    }
}
