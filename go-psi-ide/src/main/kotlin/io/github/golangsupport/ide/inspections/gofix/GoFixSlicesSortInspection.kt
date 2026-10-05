package io.github.golangsupport.ide.inspections.gofix

import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.rules.builtin.simple.GoSimplePsi
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoConditionalExpr
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoReturnStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.block
import io.github.golangsupport.semantic.types.GoSliceType

/**
 * `sort.Slice(s, func(i, j int) bool { return s[i] < s[j] })` → `slices.Sort(s)` (go1.21; modernize `sortslice`): `s` is a pure slice
 * expression of an ordered basic element type (integers, strings; not floats: `slices.Sort` puts NaNs first where `<` leaves them
 * anywhere), written the same in all three places. The `sort` import goes
 * when nothing else uses it.
 */
class GoFixSlicesSortInspection : GoFixInspectionBase() {

    override val minGoVersion: String = "1.21"

    override fun plan(element: PsiElement): GoFixPlan? {
        if (element !is GoCallExpr || GoFixPsi.packageFunction(element, "sort", setOf("Slice")) == null) return null
        val (s, less) = GoFixPsi.args(element)?.takeIf { it.size == 2 } ?: return null
        val lit = GoFixPsi.unparen(less) as? GoFunctionLit ?: return null
        val params = lit.signature?.parameters?.parameterDeclarationList.orEmpty().flatMap { it.paramDefinitionList }
        if (params.size != 2 || params.any { it.name == "_" }) return null
        val ret = GoFixPsi.statements(lit.block).singleOrNull() as? GoReturnStatement ?: return null
        val cmp = GoFixPsi.unparen(ret.expressionList.singleOrNull()) as? GoConditionalExpr ?: return null
        if (cmp.lss == null) return null
        val (ls, li) = GoSimplePsi.index(cmp.left) ?: return null
        val (rs, ri) = GoSimplePsi.index(cmp.right) ?: return null
        if (!GoFixPsi.refersTo(li, params[0]) || !GoFixPsi.refersTo(ri, params[1])) return null
        if (!GoFixPsi.isPure(s) || !GoFixPsi.same(s, ls) || !GoFixPsi.same(s, rs)) return null
        val elem = (GoFixPsi.typeOf(s).underlying() as? GoSliceType)?.elem ?: return null
        if (!GoFixPsi.isOrderedBasic(elem, floats = false)) return null
        if (GoFixPsi.hasComments(element)) return null
        val q = GoFixPsi.qualifier(element, "slices") ?: return null
        val callee = element.expression ?: return null
        return GoFixPlan("sort.Slice can be modernized using slices.Sort", "Replace sort.Slice with slices.Sort", listOf(GoFixPsi.replace(element, "$q.Sort(${s.text})")),
            listOf("slices"), listOf("sort"), GoFixPsi.rangeIn(element, callee))
    }
}
