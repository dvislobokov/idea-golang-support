package io.github.golangsupport.lang.psi.impl

import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.LiteralTextEscaper
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.impl.source.tree.LeafElement
import io.github.golangsupport.lang.psi.GoTypes

/**
 * A Go string literal as an injection host: `"interpreted"` (escapes decoded by [GoInterpretedStringEscaper]) or `` `raw` `` (verbatim).
 * Language injection (regexp, JSON) needs this on the literal itself; nothing else in the PSI depends on it.
 */
abstract class GoStringLiteralMixin(node: ASTNode) : GoCompositeElementImpl(node), PsiLanguageInjectionHost {
    private val isRaw: Boolean get() = node.firstChildNode?.elementType == GoTypes.RAW_STRING

    /** True when the literal has its closing quote: an unterminated one (`"abc` at the end of a line) has none to cut. */
    private fun closed(): Boolean = text.length >= 2 && text.last() == text.first()

    /** The text between the quotes. */
    fun contentRange(): TextRange = TextRange(1, if (closed()) text.length - 1 else text.length)

    override fun isValidHost(): Boolean = closed()

    override fun updateText(text: String): PsiLanguageInjectionHost {
        (node.firstChildNode as? LeafElement)?.replaceWithText(text)
        return this
    }

    override fun createLiteralTextEscaper(): LiteralTextEscaper<out PsiLanguageInjectionHost> =
        if (isRaw) RawStringEscaper(this) else GoInterpretedStringEscaper(this)
}

/** A raw literal is taken verbatim between the backquotes. */
class RawStringEscaper(host: GoStringLiteralMixin) : LiteralTextEscaper<GoStringLiteralMixin>(host) {
    override fun decode(rangeInsideHost: TextRange, outChars: StringBuilder): Boolean {
        outChars.append(rangeInsideHost.substring(myHost.text))
        return true
    }

    override fun getOffsetInHost(offsetInDecoded: Int, rangeInsideHost: TextRange): Int =
        (rangeInsideHost.startOffset + offsetInDecoded).coerceAtMost(rangeInsideHost.endOffset)

    override fun getRelevantTextRange(): TextRange = myHost.contentRange()

    override fun isOneLine(): Boolean = false
}

/** Decodes Go escapes (`\n \t \\ \" \xHH \ooo \uHHHH \UHHHHHHHH`) of an interpreted literal, keeping the source offset of every decoded char. */
class GoInterpretedStringEscaper(host: GoStringLiteralMixin) : LiteralTextEscaper<GoStringLiteralMixin>(host) {
    private var sourceOffsets: IntArray? = null

    override fun decode(rangeInsideHost: TextRange, outChars: StringBuilder): Boolean {
        val src = rangeInsideHost.substring(myHost.text)
        val offsets = IntArray(src.length + 1)
        var out = 0
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (c != '\\') {
                outChars.append(c)
                offsets[out++] = i
                i++
                continue
            }
            val (decoded, len) = decodeEscape(src, i) ?: return false
            outChars.append(decoded)
            repeat(decoded.length) { offsets[out++] = i }
            i += len
        }
        offsets[out] = src.length
        sourceOffsets = offsets
        return true
    }

    override fun getOffsetInHost(offsetInDecoded: Int, rangeInsideHost: TextRange): Int {
        val offsets = sourceOffsets ?: return -1
        if (offsetInDecoded >= offsets.size) return -1
        return (rangeInsideHost.startOffset + offsets[offsetInDecoded]).coerceAtMost(rangeInsideHost.endOffset)
    }

    override fun getRelevantTextRange(): TextRange = myHost.contentRange()

    override fun isOneLine(): Boolean = true

    private companion object {
        /** The decoded text and the source length of the escape at [at] (a backslash), or null when it is malformed. */
        fun decodeEscape(s: String, at: Int): Pair<String, Int>? {
            val e = s.getOrNull(at + 1) ?: return null
            val simple = when (e) {
                'a' -> '\u0007'
                'b' -> '\b'
                'f' -> '\u000c'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'v' -> '\u000b'
                '\\' -> '\\'
                '\'' -> '\''
                '"' -> '"'
                else -> null
            }
            if (simple != null) return simple.toString() to 2
            val (digits, radix) = when (e) {
                'x' -> 2 to 16
                'u' -> 4 to 16
                'U' -> 8 to 16
                in '0'..'7' -> 3 to 8
                else -> return null
            }
            val from = if (radix == 8) at + 1 else at + 2
            val num = s.substring(from, minOf(from + digits, s.length))
            if (num.length != digits) return null
            val cp = num.toIntOrNull(radix) ?: return null
            if (!Character.isValidCodePoint(cp)) return null
            return String(Character.toChars(cp)) to (from + digits - at)
        }
    }
}
