package io.github.golangsupport.ide.rules.builtin.vet

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import io.github.golangsupport.ide.inspections.GoAnalysisPsi
import io.github.golangsupport.ide.inspections.GoImportEdits
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.ide.rules.GoRuleOption
import io.github.golangsupport.lang.psi.GoCompositeLit
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoLiteralValue
import io.github.golangsupport.semantic.infer.GoExpressionTyper
import io.github.golangsupport.semantic.psi.GoPsiUtil.elements
import io.github.golangsupport.semantic.types.GoArrayType
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoStructType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypeParamType
import io.github.golangsupport.semantic.types.GoUnknownType

/**
 * govet `composites`: a composite literal of a struct type imported from another package that lists its values without field names
 * (adding a field to the struct breaks it). Elided literals (`[]T{{1, 2}}`) included. Fix: add the field names when every value is
 * there and every field is exported.
 */
class GoVetCompositesRule : GoVetExprRule() {
    override val id: String get() = "govet:composites"
    override val title: String get() = "Unkeyed fields in a struct literal of another package"
    override val description: String get() =
        "govet <code>composites</code>: <code>&amp;net.DNSConfigError{err}</code> breaks when the struct gains a field (even an unexported one); " +
            "write <code>&amp;net.DNSConfigError{Err: err}</code>. Literals of the package's own types and a few stdlib types meant to be " +
            "written positionally (<code>image.Point</code>, <code>color.RGBA</code>, <code>unicode.Range16</code> …) are allowed."
    override val options: List<GoRuleOption<*>> get() = listOf(WHITELIST)

    override fun checkExpression(expression: GoExpression, ctx: GoRuleContext) {
        if (expression !is GoCompositeLit) return
        val value = expression.literalValue ?: return
        val type = ctx.typeOf(expression)
        check(expression, value, type, GoVetPsi.pathTypeString(type), ctx)
        nested(value, ctx)
    }

    /** The elided literals inside [value] (`{1, 2}` of `[]T{{1, 2}}`), whose type comes from the enclosing literal. */
    private fun nested(value: GoLiteralValue, ctx: GoRuleContext) {
        for (element in value.elements) {
            for (inner in listOfNotNull(element.key?.literalValue, element.value?.literalValue)) {
                val raw = elidedType(inner, ctx)
                if (raw != null) {
                    val type = if (raw is GoPointerType) raw.elem else raw
                    check(inner, inner, type, GoVetPsi.pathTypeString(raw), ctx)
                }
                nested(inner, ctx)
            }
        }
    }

    /** The type of an elided literal as written in its parent's element type (`*T` for `[]*T{{…}}`). */
    private fun elidedType(inner: GoLiteralValue, ctx: GoRuleContext): GoType? {
        val element = inner.parent?.parent ?: return null
        val outer = element.parent as? GoLiteralValue ?: return null
        val outerType = GoExpressionTyper.getInstance(ctx.project).typeOfLiteralValue(outer) ?: return null
        val u = (if (outerType is GoPointerType) outerType.elem else outerType).underlying()
        val isKey = inner.parent is io.github.golangsupport.lang.psi.GoKey
        return when (u) {
            is GoSliceType -> u.elem
            is GoArrayType -> u.elem
            is GoMapType -> if (isKey) u.key else u.value
            else -> null
        }
    }

    private fun check(anchor: com.intellij.psi.PsiElement, value: GoLiteralValue, type: GoType, typeName: String, ctx: GoRuleContext) {
        val elements = value.elements
        if (elements.isEmpty() || elements.any { it.key != null }) return
        if (type is GoUnknownType || type is GoTypeParamType) return
        val struct = (if (type is GoPointerType) type.elem else type).underlying() as? GoStructType ?: return
        if (isSamePackageType(type, ctx)) return
        if (ctx.option(WHITELIST) && typeName in UNKEYED_ALLOWED) return
        val fields = struct.fields
        val fix = if (elements.size == fields.size && fields.all { it.isExported }) AddFieldNamesFix(fields.map { it.name }) else null
        val range = if (anchor is GoCompositeLit) TextRange(0, value.startOffsetInParent).takeIf { !it.isEmpty } else TextRange(0, 1)
        ctx.report(anchor, range, "$typeName struct literal uses unkeyed fields", *listOfNotNull(fix).toTypedArray())
    }

    private fun isSamePackageType(type: GoType, ctx: GoRuleContext): Boolean = when (type) {
        is GoStructType -> true
        is GoPointerType -> isSamePackageType(type.elem, ctx)
        is GoNamedType -> {
            val here = GoAnalysisPsi.packagePath(ctx.file)?.removeSuffix("_test")
            val there = type.pkgPath?.removeSuffix("_test")
            there != null && there == here
        }
        else -> false
    }

    /** Inserts `Name: ` before every value of the literal (the problem element or its literal value). */
    private class AddFieldNamesFix(private val names: List<String>) : LocalQuickFix {
        override fun getFamilyName(): String = "Add field names to struct literal"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val element = descriptor.psiElement ?: return
            val value = (element as? GoCompositeLit)?.literalValue ?: element as? GoLiteralValue ?: return
            val elements = value.elements
            if (elements.size != names.size) return
            val file = value.containingFile
            val document = GoImportEdits.document(file) ?: return
            for (i in elements.indices.reversed()) document.insertString(elements[i].textRange.startOffset, "${names[i]}: ")
            GoImportEdits.commit(file, document)
        }
    }

    private companion object {
        val WHITELIST: GoRuleOption<Boolean> = GoRuleOption.bool("whitelist", true, "Allow the unkeyed literals of vet's list of stdlib types")

        /** vet's `unkeyedLiteral` list (composite/whitelist.go). */
        val UNKEYED_ALLOWED = setOf(
            "image/color.Alpha16", "image/color.Alpha", "image/color.CMYK", "image/color.Gray16", "image/color.Gray", "image/color.NRGBA64",
            "image/color.NRGBA", "image/color.NYCbCrA", "image/color.RGBA64", "image/color.RGBA", "image/color.YCbCr", "image.Point",
            "image.Rectangle", "image.Uniform", "unicode.Range16", "unicode.Range32", "testing.InternalBenchmark", "testing.InternalExample",
            "testing.InternalTest", "testing.InternalFuzzTarget",
        )
    }
}
