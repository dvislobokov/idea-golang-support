package io.github.golangsupport.ide.intentions

import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.completion.GoCompletionSemantics
import io.github.golangsupport.lang.psi.GoCompositeLit
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoLiteralValue
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil.elements
import io.github.golangsupport.semantic.types.GoField
import io.github.golangsupport.semantic.types.GoStructType

/**
 * Alt+Enter inside `T{…}`, `&T{…}` or an elided nested literal (`[]T{{…}}`): the fields of the struct not written yet, keyed, one
 * per line, with their zero values (`nil` for pointers, `T{}` for structs and arrays). An embedded field is filled by its type name
 * (`Base: Base{}`): promoted fields are not valid keys of a literal. Unexported fields of another package are not offered, and a
 * positional literal (`T{1, 2}`) gets nothing.
 */
abstract class GoFillStructIntentionBase(private val requiredOnly: Boolean) : GoCodeActionIntention() {

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        val leaf = GoIntentionText.leafAt(file, offset) ?: return null
        val value = literalAt(leaf) ?: return null
        val rbrace = value.rbrace ?: return null
        val service = GoSemanticService.getInstance(file.project)
        val struct = GoCompletionSemantics.literalType(value, service)?.let(GoCompletionSemantics::derefUnderlying) as? GoStructType ?: return null
        val elements = value.elements
        if (elements.any { it.key == null }) return null
        val written = elements.mapNotNull { (it.key?.expression as? GoReferenceExpression)?.identifier?.text }.toSet()
        val source = GoSourceText(file)
        val fields = struct.fields.filter { it.name != "_" && it.name !in written && visible(it, source) && (!requiredOnly || !GoZeroValues.isNilable(it.type)) }
        if (fields.isEmpty()) return null
        val lines = fields.map { "${it.name}: ${source.zero(it.type)}," }
        val text = file.viewProvider.contents
        val indent = GoIntentionText.indentAt(text, value.lbrace.textRange.startOffset)
        val lbraceEnd = value.lbrace.textRange.endOffset
        val rbraceStart = rbrace.textRange.startOffset
        val edits = ArrayList<GoEditPlan.Edit>()
        val last = elements.lastOrNull()
        when {
            last == null -> edits += GoEditPlan.Edit(lbraceEnd, rbraceStart, "\n" + lines.joinToString("") { "$indent\t$it\n" } + indent)
            text.subSequence(GoIntentionText.lineStart(text, rbraceStart), rbraceStart).isBlank() -> {
                // A literal over several lines: the new lines go before the closing brace, after a comma for the last element.
                val elementIndent = GoIntentionText.indentAt(text, last.textRange.startOffset)
                edits += GoEditPlan.Edit(GoIntentionText.lineStart(text, rbraceStart), GoIntentionText.lineStart(text, rbraceStart), lines.joinToString("") { "$elementIndent$it\n" })
                var after = last.textRange.endOffset
                while (after < text.length && (text[after] == ' ' || text[after] == '\t')) after++
                if (after >= text.length || text[after] != ',') edits += GoEditPlan.Edit(last.textRange.endOffset, last.textRange.endOffset, ",")
            }
            else -> {
                // One line (`T{A: 1}`): every element on a line of its own.
                val all = elements.map { it.text + "," } + lines
                edits += GoEditPlan.Edit(lbraceEnd, rbraceStart, "\n" + all.joinToString("") { "$indent\t$it\n" } + indent)
            }
        }
        return GoEditPlan(edits, source.imports)
    }

    /** The innermost literal value at the caret: inside its braces, or on the type of its composite literal. */
    private fun literalAt(leaf: com.intellij.psi.PsiElement): GoLiteralValue? {
        val value = PsiTreeUtil.getParentOfType(leaf, GoLiteralValue::class.java, false)
        val composite = PsiTreeUtil.getParentOfType(leaf, GoCompositeLit::class.java, false)
        if (composite != null && (value == null || PsiTreeUtil.isAncestor(value, composite, true))) return composite.literalValue
        return value
    }

    private fun visible(field: GoField, source: GoSourceText): Boolean =
        field.isExported || (field.declaration?.let(source::isOwnPackage) ?: source.isOwnPath(field.pkgPath))
}

/** Fill all fields: every field not written yet. */
class GoFillStructFieldsIntention : GoFillStructIntentionBase(requiredOnly = false) {
    override val defaultText: String = "Fill all fields"
}

/** Fill required fields: the fields whose zero value is not a usable default (`nil`able ones — pointers, slices, maps, channels, funcs, interfaces — are left out). */
class GoFillRequiredFieldsIntention : GoFillStructIntentionBase(requiredOnly = true) {
    override val defaultText: String = "Fill required fields"
}
