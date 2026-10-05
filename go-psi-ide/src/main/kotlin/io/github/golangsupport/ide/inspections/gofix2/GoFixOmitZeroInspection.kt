package io.github.golangsupport.ide.inspections.gofix2

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.inspections.GoStructTags
import io.github.golangsupport.lang.psi.GoFieldDeclaration
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoTag
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.types.GoStructType

/**
 * modernize `omitzero` (go1.24): `json:",omitempty"` on a field of struct type (`time.Time` included) has no effect, encoding/json
 * never treats a struct as empty. Fixes: `omitzero` (omits the zero value: a behaviour change) or removing the option.
 * Raw-string tags only (`` `json:"x,omitempty"` ``); embedded fields and pointers are left alone.
 */
class GoFixOmitZeroInspection : GoFix2InspectionBase() {
    override val minVersion = "1.24"

    override fun detect(element: PsiElement, file: GoFile): GoFixFinding? {
        if (element !is GoTag) return null
        val field = element.parent as? GoFieldDeclaration ?: return null
        val definition = field.fieldDefinitionList.firstOrNull() ?: return null
        val literal = element.stringLiteral
        val raw = literal.text
        if (raw.length < 2 || raw.first() != '`' || raw.last() != '`') return null
        val tag = raw.substring(1, raw.length - 1)
        val pair = GoStructTags.parse(tag).pairs.firstOrNull { it.key == "json" } ?: return null
        val options = pair.value.split(',')
        if (options.drop(1).none { it == "omitempty" } || options.drop(1).any { it == "omitzero" }) return null
        if (pair.value.contains('\\') || tag.substring(pair.start, pair.end) != "json:\"${pair.value}\"") return null
        if (GoSemanticService.getInstance(file.project).declarationType(definition).underlying() !is GoStructType) return null
        val base = literal.textRange.startOffset - element.textRange.startOffset + 1
        val omitAt = tag.indexOf("omitempty", pair.start)
        val pairRange = TextRange(literal.textRange.startOffset + 1 + pair.start, literal.textRange.startOffset + 1 + pair.end)
        val zero = options.mapIndexed { i, o -> if (i > 0 && o == "omitempty") "omitzero" else o }.joinToString(",")
        val removed = options.filterIndexed { i, o -> i == 0 || o != "omitempty" }.joinToString(",")
        return GoFixFinding(element, "Omitempty has no effect on nested struct fields", listOf(
            "Replace omitempty with omitzero (behavior change)" to { _ -> listOf(GoFixEdit(pairRange, "json:\"$zero\"")) },
            "Remove redundant omitempty tags" to { _ ->
                if (removed.isEmpty()) {
                    // `json:",omitempty"` alone: the whole pair goes
                    val rest = GoStructTags.without(tag, pair)
                    listOf(if (rest.isBlank()) GoFixEdit.delete(TextRange(field.type!!.textRange.endOffset, element.textRange.endOffset)) else GoFixEdit(TextRange(literal.textRange.startOffset + 1, literal.textRange.endOffset - 1), rest))
                } else listOf(GoFixEdit(pairRange, "json:\"$removed\""))
            },
        ), TextRange(base + omitAt, base + omitAt + "omitempty".length))
    }
}
