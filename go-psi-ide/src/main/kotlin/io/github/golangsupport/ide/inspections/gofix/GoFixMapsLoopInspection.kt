package io.github.golangsupport.ide.inspections.gofix

import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.rules.builtin.simple.GoSimplePsi
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoVarDeclaration
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil.rangeClause
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypePredicates

/**
 * Map loops → `maps` functions (modernize `mapsloop`), clear-cut shapes only:
 * - `for k, v := range src { dst[k] = v }` → `maps.Copy(dst, src)` (go1.21): both maps of the same key and value types;
 * - `for k := range m { keys = append(keys, k) }` → `keys = slices.AppendSeq(keys, maps.Keys(m))`, or `keys := slices.Collect(maps.Keys(m))`
 *   when the loop follows `var keys []K` (go1.23); the same for values with `for _, v := range m` and `maps.Values`.
 * Destinations are pure and do not use the loop variables.
 */
class GoFixMapsLoopInspection : GoFixInspectionBase() {

    override val minGoVersion: String = "1.21"

    override fun plan(element: PsiElement): GoFixPlan? {
        if (element !is GoForStatement || !GoFixPsi.inList(element)) return null
        val range = element.rangeClause ?: return null
        if (range.define == null) return null
        val src = range.expression ?: return null
        val map = GoFixPsi.typeOf(src).underlying() as? GoMapType ?: return null
        val statement = GoFixPsi.unwrap(GoFixPsi.statements(element.block).singleOrNull()) ?: return null
        val (lhs, rhs) = GoSimplePsi.simpleAssignment(statement) ?: return null
        if (GoFixPsi.hasComments(element)) return null
        val defs = range.varDefinitionList.map { it.takeIf { d -> d.name != "_" } }
        val key = defs.getOrNull(0)
        val value = defs.getOrNull(1)
        if (!GoFixPsi.isPure(lhs)) return null
        if (key != null && value != null) return copy(element, src, map, lhs, rhs, key, value)
        val item = key ?: value ?: return null
        return collect(element, src, map, lhs, rhs, item, isKey = key != null)
    }

    /** `dst[k] = v` → `maps.Copy(dst, src)`. */
    private fun copy(loop: GoForStatement, src: PsiElement, map: GoMapType, lhs: PsiElement, rhs: PsiElement, key: GoVarDefinition, value: GoVarDefinition): GoFixPlan? {
        val (dst, at) = GoSimplePsi.index(lhs) ?: return null
        if (!GoFixPsi.refersTo(at, key) || !GoFixPsi.refersTo(rhs, value) || !GoFixPsi.isPure(dst) || GoFixPsi.same(dst, src)) return null
        if (GoFixPsi.mentions(dst, key) || GoFixPsi.mentions(dst, value)) return null
        val target = GoFixPsi.typeOf(dst).underlying() as? GoMapType ?: return null
        if (!GoTypePredicates.identical(target.key, map.key) || !GoTypePredicates.identical(target.value, map.value)) return null
        val q = GoFixPsi.qualifier(loop, "maps") ?: return null
        return GoFixPlan("Replace m[k]=v loop with maps.Copy", "Replace loop with maps.Copy", listOf(GoFixPsi.replaceStatement(loop, "$q.Copy(${dst.text}, ${src.text})")),
            listOf("maps"), range = GoFixPsi.keywordRange(loop))
    }

    /** `keys = append(keys, k)` → `slices.AppendSeq` / `slices.Collect` over `maps.Keys` (`maps.Values` for the value). */
    private fun collect(loop: GoForStatement, src: PsiElement, map: GoMapType, lhs: PsiElement, rhs: PsiElement, item: GoVarDefinition, isKey: Boolean): GoFixPlan? {
        if (!atLeast(loop, "1.23")) return null
        val call = GoFixPsi.unparen(rhs) as? GoCallExpr ?: return null
        if (!GoFixPsi.isBuiltin(call.expression, "append")) return null
        val (acc, added) = GoFixPsi.args(call)?.takeIf { it.size == 2 } ?: return null
        if (!GoFixPsi.same(acc, lhs) || !GoFixPsi.refersTo(added, item) || GoFixPsi.mentions(lhs, item)) return null
        val itemType: GoType = if (isKey) map.key else map.value
        val slice = GoFixPsi.typeOf(lhs as io.github.golangsupport.lang.psi.GoExpression)
        if (!GoTypePredicates.identical((slice.underlying() as? GoSliceType)?.elem ?: return null, itemType)) return null
        val maps = GoFixPsi.qualifier(loop, "maps") ?: return null
        val slices = GoFixPsi.qualifier(loop, "slices") ?: return null
        val seq = "$maps.${if (isKey) "Keys" else "Values"}(${src.text})"
        val declaration = GoFixPsi.previous(loop) as? GoVarDeclaration
        val declared = declaration?.varSpecList?.singleOrNull()?.takeIf { spec ->
            spec.expressionList.isEmpty() && spec.varDefinitionList.size == 1 && GoFixPsi.refersTo(lhs, spec.varDefinitionList[0]) &&
                spec.type?.let { GoSemanticService.getInstance(loop.project).declarationType(spec.varDefinitionList[0] as GoNamedElement) }?.let { t ->
                    t is GoSliceType && GoTypePredicates.identical(t.elem, itemType)
                } == true
        }
        if (declaration != null && declared != null && !GoFixPsi.hasComments(declaration)) {
            val edits = listOf(GoFixPsi.replaceStatement(declaration, ""), GoFixPsi.replaceStatement(loop, "${lhs.text} := $slices.Collect($seq)"))
            return GoFixPlan("Replace append loop with slices.Collect", "Replace loop with slices.Collect", edits, listOf("slices", "maps"), range = GoFixPsi.keywordRange(loop))
        }
        val text = "${lhs.text} = $slices.AppendSeq(${lhs.text}, $seq)"
        return GoFixPlan("Replace append loop with slices.AppendSeq", "Replace loop with slices.AppendSeq", listOf(GoFixPsi.replaceStatement(loop, text)),
            listOf("slices", "maps"), range = GoFixPsi.keywordRange(loop))
    }
}
