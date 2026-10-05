package io.github.golangsupport.ide.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.inspections.GoStructTags
import io.github.golangsupport.ide.inspections.printf.GoFormatString
import io.github.golangsupport.ide.inspections.printf.GoPrintfCalls
import io.github.golangsupport.ide.inspections.printf.GoStringValue
import io.github.golangsupport.lang.GoColors
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.lang.psi.GoTag
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.semantic.psi.GoPsiUtil.expressions
import io.github.golangsupport.semantic.psi.GoPsiUtil.inner

/**
 * Colours inside string literals, as GoLand does: the parts of a struct tag (key, colon, quoted value, other text) and the verbs of
 * the format string of a printf-like call (`%d`, `%-10s`, `%[1]d`, `%%`). The tag is split by [GoStructTags.parse] (the parser of
 * the struct tag inspection), the format by [GoFormatString.parse] and the calls are those of the printf inspection
 * ([GoPrintfCalls]), so all three agree. Also the escapes of interpreted strings and runes, valid and invalid (the lexer keeps a
 * literal one token: the quote handler reads it whole). Tags and escapes are coloured in dumb mode too; verbs need resolve.
 */
class GoStringContentAnnotator : Annotator, DumbAware {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        when (element) {
            is GoTag -> element.stringLiteral?.let { literal -> paint(holder, literal, tagRanges(literal.text)) }
            is GoCallExpr -> if (!DumbService.isDumb(element.project)) verbs(element, holder)
            else -> if (element.node.elementType == GoTypes.STRING || element.node.elementType == GoTypes.CHAR) paint(holder, element, escapeRanges(element.text))
        }
    }

    private fun verbs(call: GoCallExpr, holder: AnnotationHolder) {
        // cheap filter before resolve: only calls with a string literal among their arguments
        val args = call.argumentList?.expressions ?: return
        if (args.none { unparen(it) is GoStringLiteral }) return
        val printf = GoPrintfCalls.of(call)?.takeIf { it.isPrintf } ?: return
        val literal = unparen(printf.format) as? GoStringLiteral ?: return
        paint(holder, literal, verbRanges(literal.text))
    }

    private fun paint(holder: AnnotationHolder, literal: PsiElement, ranges: List<Pair<TextRange, TextAttributesKey>>) {
        val start = literal.textRange.startOffset
        for ((range, key) in ranges) {
            if (range.isEmpty) continue
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(range.shiftRight(start)).textAttributes(key).create()
        }
    }

    private fun unparen(e: GoExpression?): GoExpression? {
        var x = e
        while (x is GoParenthesesExpr) x = x.inner as? GoExpression
        return x
    }

    companion object {
        /** The parts of a struct tag literal (its source text, quotes included), ranges relative to that text. */
        fun tagRanges(source: String): List<Pair<TextRange, TextAttributesKey>> {
            val value = GoStringValue.decode(source) ?: return emptyList()
            val tag = value.value
            val out = ArrayList<Pair<TextRange, TextAttributesKey>>()
            fun add(from: Int, to: Int, key: TextAttributesKey) {
                value.sourceRange(from, to)?.let { out += TextRange(it.first, it.last + 1) to key }
            }
            fun text(from: Int, to: Int) {
                var a = from
                var b = to
                while (a < b && tag[a].isWhitespace()) a++
                while (b > a && tag[b - 1].isWhitespace()) b--
                if (a < b) add(a, b, GoColors.TAG_TEXT)
            }
            var at = 0
            for (pair in GoStructTags.parse(tag).pairs) {
                text(at, pair.start)
                val colon = pair.start + pair.key.length
                add(pair.start, colon, GoColors.TAG_KEY)
                add(colon, colon + 1, GoColors.TAG_COLON)
                add(colon + 1, pair.end, GoColors.TAG_VALUE)
                at = pair.end
            }
            text(at, tag.length)
            return out
        }

        /**
         * The escapes of an interpreted string or rune literal (its source text): `\n`, `\x41`, `\101`, `\u00e9`, `\U0001F600` and the
         * quote of the literal are valid; any other char after `\`, too few digits, an octal over 255 or a code point out of range is not.
         */
        fun escapeRanges(source: String): List<Pair<TextRange, TextAttributesKey>> {
            val quote = source.firstOrNull()?.takeIf { it == '"' || it == '\'' } ?: return emptyList()
            val out = ArrayList<Pair<TextRange, TextAttributesKey>>()
            var i = 1
            while (i < source.length) {
                if (source[i] != '\\') { i++; continue }
                if (i + 1 >= source.length) break
                val e = source[i + 1]
                var end = i + 2
                val valid = when (e) {
                    'a', 'b', 'f', 'n', 'r', 't', 'v', '\\', quote -> true
                    'x', 'u', 'U' -> {
                        val n = when (e) { 'x' -> 2; 'u' -> 4; else -> 8 }
                        while (end < source.length && end < i + 2 + n && source[end].isHexDigit()) end++
                        end == i + 2 + n && (e == 'x' || source.substring(i + 2, end).toLong(16).let { it <= 0x10FFFF && it !in 0xD800..0xDFFF })
                    }
                    in '0'..'7' -> {
                        end = i + 1
                        while (end < source.length && end < i + 4 && source[end] in '0'..'7') end++
                        end == i + 4 && source.substring(i + 1, end).toInt(8) <= 255
                    }
                    else -> false
                }
                out += TextRange(i, end) to (if (valid) GoColors.VALID_STRING_ESCAPE else GoColors.INVALID_STRING_ESCAPE)
                i = end
            }
            return out
        }

        private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

        /** The directives of a format string literal (its source text), ranges relative to that text; up to the first malformed one. */
        fun verbRanges(source: String): List<Pair<TextRange, TextAttributesKey>> {
            val value = GoStringValue.decode(source) ?: return emptyList()
            return GoFormatString.parse(value.value).directives.mapNotNull { d ->
                value.sourceRange(d.start, d.end)?.let { TextRange(it.first, it.last + 1) to GoColors.FORMAT_VERB }
            }
        }
    }
}
