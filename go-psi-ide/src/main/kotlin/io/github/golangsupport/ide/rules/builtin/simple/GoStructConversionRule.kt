package io.github.golangsupport.ide.rules.builtin.simple

import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.lang.psi.GoCompositeLit
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.semantic.psi.GoPsiUtil.elements
import io.github.golangsupport.semantic.psi.GoPsiUtil.literalType
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoStructType
import io.github.golangsupport.semantic.types.GoTypePredicates
import io.github.golangsupport.semantic.types.GoTypeRenderer

/**
 * staticcheck S1016: `T2{A: x.A, B: x.B}` copying every field of `x` (of a struct type `T1` of the same package with identical fields)
 * is the conversion `T2(x)`. Struct tags are ignored from go1.8 on (identical tags required before). Not for `&T2{...}`, not across packages.
 */
class GoStructConversionRule : GoSimpleExpressionRule() {
    override val id: String get() = "S1016"
    override val title: String get() = "Use a type conversion instead of manually copying struct fields"
    override val description: String get() =
        "Two struct types with identical fields can be converted between each other. In older versions of Go, the fields had to have identical " +
            "struct tags. Since Go 1.8, however, struct tags are ignored during conversions. It is thus not necessary to manually copy every field individually."

    private class Match(val lit: GoCompositeLit, val source: GoReferenceExpression, val to: GoNamedType, val from: GoNamedType)

    override fun checkExpression(expression: GoExpression, ctx: GoRuleContext) {
        if (expression !is GoCompositeLit) return
        val m = match(expression, ctx) ?: return
        val path = GoLintPsi.packagePath(ctx.file)
        val render = { t: GoNamedType -> GoTypeRenderer.render(t) { n -> n.pkgPath?.takeIf { it != path } } }
        ctx.report(expression, "should convert ${m.source.text} (type ${render(m.from)}) to ${render(m.to)} instead of using struct literal",
            *GoRewriteFix.offer("Use type conversion", expression, ctx, ::fix))
    }

    private fun match(lit: GoCompositeLit, ctx: GoRuleContext): Match? {
        if ((lit.parent as? GoUnaryExpr)?.and != null) return null
        val elements = lit.literalValue?.elements ?: return null
        if (elements.isEmpty()) return null
        val to = ctx.typeOf(lit) as? GoNamedType ?: return null
        val s1 = to.underlying() as? GoStructType ?: return null
        if (s1.fields.size != elements.size) return null
        var source: GoReferenceExpression? = null
        var sourceTarget: PsiElement? = null
        for ((i, element) in elements.withIndex()) {
            val sel = element.value?.expression as? GoReferenceExpression ?: return null
            val x = sel.expression as? GoReferenceExpression ?: return null
            if (!GoSimplePsi.isIdent(x)) return null
            val field = sel.identifier.text
            val key = element.key
            if (key == null) {
                if (s1.fields[i].name != field) return null
            } else if ((key.expression as? GoReferenceExpression)?.identifier?.text != field || key.expression?.let { (it as GoReferenceExpression).expression } != null) {
                return null
            }
            val target = GoSimplePsi.target(x, ctx) ?: return null
            if (sourceTarget != null && target != sourceTarget) return null
            source = x
            sourceTarget = target
        }
        val x = source ?: return null
        val from = ctx.typeOf(x) as? GoNamedType ?: return null
        val path = to.pkgPath ?: return null
        if (from.pkgPath != path) return null
        val s2 = from.underlying() as? GoStructType ?: return null
        if (GoTypePredicates.identical(to, from)) return null
        if (!GoTypePredicates.isKnown(s1) || !GoTypePredicates.isKnown(s2)) return null
        val version = GoLintPsi.goVersion(ctx.file)
        val ignoreTags = version == null || version.first > 1 || version.second >= 8
        if (if (ignoreTags) !GoTypePredicates.identicalIgnoreTags(s1, s2) else !GoTypePredicates.identical(s1, s2)) return null
        return Match(lit, x, to, from)
    }

    private fun fix(element: PsiElement, ctx: GoRuleContext): List<GoEditPlan.Edit>? {
        val lit = element as? GoCompositeLit ?: return null
        val m = match(lit, ctx) ?: return null
        val type = lit.literalType ?: return null
        if (GoSimplePsi.hasComments(lit)) return null
        return listOf(GoEditPlan.Edit(lit.textRange.startOffset, lit.textRange.endOffset, "${type.text}(${m.source.text})"))
    }
}
