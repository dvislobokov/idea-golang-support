package io.github.golangsupport.ide.intentions

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.completion.GoCompletionSemantics
import io.github.golangsupport.lang.psi.GoCompositeLit
import io.github.golangsupport.lang.psi.GoElement
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
 * positional literal (`T{1, 2}`) gets nothing. The text comes from [GoFillStruct], which completion's Fill items share.
 */
abstract class GoFillStructIntentionBase(private val requiredOnly: Boolean) : GoCodeActionIntention() {

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        val leaf = GoIntentionText.leafAt(file, offset) ?: return null
        val value = GoFillStruct.literalAt(leaf) ?: return null
        val target = GoFillStruct.target(file, value, requiredOnly) ?: return null
        return GoFillStruct.plan(file, target)
    }
}

/** Fill all fields: every field not written yet. */
class GoFillStructFieldsIntention : GoFillStructIntentionBase(requiredOnly = false) {
    override val defaultText: String = "Fill all fields"
}

/** Fill required fields: the fields whose zero value is not a usable default (`nil`able ones — pointers, slices, maps, channels, funcs, interfaces — are left out). */
class GoFillRequiredFieldsIntention : GoFillStructIntentionBase(requiredOnly = true) {
    override val defaultText: String = "Fill required fields"
}

/** The fields a struct literal can be filled with and the text that fills it: the Fill intentions and the Fill items of completion. */
object GoFillStruct {
    /** A keyed (or empty) struct literal [value] and the [fields] it does not write yet, in declaration order. */
    class Target(val value: GoLiteralValue, val fields: List<GoField>, val source: GoSourceText)

    /** The innermost literal value at [leaf]: inside its braces, or on the type of its composite literal. */
    fun literalAt(leaf: PsiElement): GoLiteralValue? {
        val value = PsiTreeUtil.getParentOfType(leaf, GoLiteralValue::class.java, false)
        val composite = PsiTreeUtil.getParentOfType(leaf, GoCompositeLit::class.java, false)
        if (composite != null && (value == null || PsiTreeUtil.isAncestor(value, composite, true))) return composite.literalValue
        return value
    }

    /**
     * The fields [value] can still take; null when it is no struct literal, has positional elements or nothing is left. [ignore] is an
     * element that does not count (the one completion is typing in).
     */
    fun target(file: GoFile, value: GoLiteralValue, requiredOnly: Boolean = false, ignore: GoElement? = null): Target? {
        val service = GoSemanticService.getInstance(file.project)
        val struct = GoCompletionSemantics.literalType(value, service)?.let(GoCompletionSemantics::derefUnderlying) as? GoStructType ?: return null
        val elements = value.elements.filter { it !== ignore }
        if (elements.any { it.key == null }) return null
        val written = elements.mapNotNull { keyOf(it) }.toSet()
        val source = GoSourceText(file)
        val fields = struct.fields.filter { it.name != "_" && it.name !in written && visible(it, source) && (!requiredOnly || !GoZeroValues.isNilable(it.type)) }
        return if (fields.isEmpty()) null else Target(value, fields, source)
    }

    /**
     * The edits that write the fields of [target] ([chosen] names only, when given) into its literal, one per line. With [align] the
     * values of consecutive one-line elements line up after their keys, as gofmt writes them (the lines already there are re-padded).
     */
    fun plan(file: GoFile, target: Target, chosen: Collection<String>? = null, align: Boolean = false): GoEditPlan? {
        val value = target.value
        val rbrace = value.rbrace ?: return null
        val fields = target.fields.filter { chosen == null || it.name in chosen }
        if (fields.isEmpty()) return null
        val added = fields.map { Line(it.name, target.source.zero(it.type)) }
        val text = file.viewProvider.contents
        val indent = GoIntentionText.indentAt(text, value.lbrace.textRange.startOffset)
        val lbraceEnd = value.lbrace.textRange.endOffset
        val rbraceStart = rbrace.textRange.startOffset
        val edits = ArrayList<GoEditPlan.Edit>()
        val elements = value.elements
        val last = elements.lastOrNull()
        when {
            last == null -> edits += GoEditPlan.Edit(lbraceEnd, rbraceStart, "\n" + render(added, align).joinToString("") { "$indent\t$it\n" } + indent)
            text.subSequence(GoIntentionText.lineStart(text, rbraceStart), rbraceStart).isBlank() -> {
                // A literal over several lines: the new lines go before the closing brace, after a comma for the last element; blank
                // lines between them (the line completion was typing on) give way to the new ones.
                val elementIndent = GoIntentionText.indentAt(text, last.textRange.startOffset)
                val braceLine = GoIntentionText.lineStart(text, rbraceStart)
                val lastLineEnd = text.indexOf('\n', last.textRange.endOffset).let { if (it < 0) text.length else it + 1 }
                val insertAt = if (lastLineEnd <= braceLine && text.subSequence(lastLineEnd, braceLine).isBlank()) lastLineEnd else braceLine
                val block = if (align) trailingBlock(text, elements, insertAt) else emptyList()
                val width = (block.map { keyOf(it)!! } + added.map { it.key!! }).maxOf { it.length }
                for (element in block) {
                    val key = element.key!!
                    val start = element.value?.textRange?.startOffset ?: continue
                    edits += GoEditPlan.Edit(key.textRange.endOffset, start, ":" + " ".repeat(width - key.textLength + 1))
                }
                val lines = if (align) added.map { "${it.key}:${" ".repeat(width - it.key!!.length + 1)}${it.value}," } else render(added, false)
                edits += GoEditPlan.Edit(insertAt, braceLine, lines.joinToString("") { "$elementIndent$it\n" })
                var after = last.textRange.endOffset
                while (after < text.length && (text[after] == ' ' || text[after] == '\t')) after++
                if (after >= text.length || text[after] != ',') edits += GoEditPlan.Edit(last.textRange.endOffset, last.textRange.endOffset, ",")
            }
            else -> {
                // One line (`T{A: 1}`): every element on a line of its own.
                val all = elements.map { e -> lineOf(e) } + added
                edits += GoEditPlan.Edit(lbraceEnd, rbraceStart, "\n" + render(all, align).joinToString("") { "$indent\t$it\n" } + indent)
            }
        }
        return GoEditPlan(edits, target.source.imports)
    }

    /** `Key: value` of an element; [key] null for an element written as it is ([value] is then its whole text). */
    private class Line(val key: String?, val value: String)

    private fun lineOf(e: GoElement): Line {
        val key = keyOf(e)
        val value = e.value?.text
        return if (key != null && value != null && '\n' !in value) Line(key, value) else Line(null, e.text)
    }

    /** The lines with their commas; with [align] the values of each run of keyed one-line elements start in one column. */
    private fun render(lines: List<Line>, align: Boolean): List<String> {
        val out = ArrayList<String>(lines.size)
        var i = 0
        while (i < lines.size) {
            if (lines[i].key == null || '\n' in lines[i].value) {
                out += (lines[i].key?.let { "$it: " } ?: "") + lines[i].value + ","
                i++
                continue
            }
            var end = i
            while (end < lines.size && lines[end].key != null && '\n' !in lines[end].value) end++
            val width = if (align) lines.subList(i, end).maxOf { it.key!!.length } else 0
            for (line in lines.subList(i, end)) out += "${line.key}:${" ".repeat(maxOf(width - line.key!!.length, 0) + 1)}${line.value},"
            i = end
        }
        return out
    }

    /**
     * The last elements of a literal over several lines that gofmt aligns with lines added after them: each keyed, on one line of its
     * own, right below the one before (a blank line, a comment line or a multi-line value ends the run).
     */
    private fun trailingBlock(text: CharSequence, elements: List<GoElement>, insertAt: Int): List<GoElement> {
        val block = ArrayList<GoElement>()
        // the added lines must follow the last one directly (no comment after it), or they are not part of its run
        val tail = text.subSequence(elements.last().textRange.endOffset, insertAt)
        if (tail.count { it == '\n' } != 1 || tail.any { it != ',' && !it.isWhitespace() }) return block
        var below: GoElement? = null
        for (e in elements.asReversed()) {
            val value = e.value ?: break
            val key = e.key ?: break
            if (keyOf(e) == null || e.textContains('\n') || text.subSequence(key.textRange.endOffset, value.textRange.startOffset).any { it != ':' && it != ' ' && it != '\t' }) break
            if (!text.subSequence(GoIntentionText.lineStart(text, e.textRange.startOffset), e.textRange.startOffset).isBlank()) break
            if (below != null && text.subSequence(e.textRange.endOffset, below.textRange.startOffset).count { it == '\n' } != 1) break
            block += e
            below = e
        }
        return block.asReversed()
    }

    private fun keyOf(e: GoElement): String? = (e.key?.expression as? GoReferenceExpression)?.takeIf { it.expression == null }?.identifier?.text

    private fun visible(field: GoField, source: GoSourceText): Boolean =
        field.isExported || (field.declaration?.let(source::isOwnPackage) ?: source.isOwnPath(field.pkgPath))
}
