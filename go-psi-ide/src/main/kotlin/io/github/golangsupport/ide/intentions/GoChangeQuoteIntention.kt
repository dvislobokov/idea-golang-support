package io.github.golangsupport.ide.intentions

import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoStringLiteral

/**
 * Change quote (gopls `changequote`): the string literal under the caret, interpreted → raw when its value can be written between
 * backquotes unchanged (`strconv.CanBackquote`: no backquote, no newline or other control character but a tab), raw → interpreted
 * always (`strconv.Quote`: the carriage returns a raw literal drops are dropped here too).
 */
class GoChangeQuoteIntention : GoCodeActionIntention() {
    override val defaultText: String = "Change quote"

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        val leaf = GoIntentionText.leafAt(file, offset) ?: return null
        val literal = leaf.parent as? GoStringLiteral ?: return null
        val text = literal.text
        if (text.length < 2 || text.last() != text.first()) return null
        val body = text.substring(1, text.length - 1)
        val range = literal.textRange
        if (literal.rawString != null) {
            val quoted = GoStringQuotes.quote(body.replace("\r", ""))
            return GoEditPlan(listOf(GoEditPlan.Edit(range.startOffset, range.endOffset, quoted)), text = "Convert to interpreted string literal")
        }
        val value = GoStringQuotes.unquote(body) ?: return null
        if (!GoStringQuotes.canBackquote(value)) return null
        return GoEditPlan(listOf(GoEditPlan.Edit(range.startOffset, range.endOffset, "`$value`")), text = "Convert to raw string literal")
    }
}

/** `strconv.Unquote`, `strconv.Quote` and `strconv.CanBackquote` for the bodies of Go string literals (without the quotes). */
internal object GoStringQuotes {

    /** The value of an interpreted literal's body; null when malformed or when a byte escape (`\xff`, `\377`) is not ASCII (no raw form keeps it). */
    fun unquote(body: String): String? {
        val out = StringBuilder(body.length)
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c != '\\') {
                out.append(c)
                i++
                continue
            }
            if (i + 1 >= body.length) return null
            val e = body[i + 1]
            i += 2
            when (e) {
                'a' -> out.append('\u0007')
                'b' -> out.append('\b')
                'f' -> out.append('\u000c')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'v' -> out.append('\u000b')
                '\\' -> out.append('\\')
                '"' -> out.append('"')
                'x', 'u', 'U' -> {
                    val digits = when (e) { 'x' -> 2; 'u' -> 4; else -> 8 }
                    if (i + digits > body.length) return null
                    val v = body.substring(i, i + digits).toIntOrNull(16) ?: return null
                    i += digits
                    when {
                        e == 'x' && v >= 0x80 -> return null
                        v > 0x10FFFF || v in 0xD800..0xDFFF -> return null
                        else -> out.appendCodePoint(v)
                    }
                }
                in '0'..'7' -> {
                    if (i + 2 > body.length) return null
                    val v = body.substring(i - 1, i + 2).toIntOrNull(8) ?: return null
                    i += 2
                    if (v >= 0x80) return null
                    out.append(v.toChar())
                }
                else -> return null
            }
        }
        return out.toString()
    }

    /** `strconv.CanBackquote`: one line, no control character but a tab, no backquote, no BOM. */
    fun canBackquote(value: String): Boolean = value.codePoints().allMatch { cp ->
        !(cp < 0x20 && cp != '\t'.code) && cp != '`'.code && cp != 0x7F && cp != 0xFEFF && cp !in 0xD800..0xDFFF
    }

    /** `strconv.Quote`: printable characters as they are, the rest escaped like Go writes them. */
    fun quote(value: String): String {
        val out = StringBuilder(value.length + 2).append('"')
        value.codePoints().forEach { cp ->
            when {
                cp == '"'.code || cp == '\\'.code -> out.append('\\').appendCodePoint(cp)
                isPrint(cp) -> out.appendCodePoint(cp)
                cp == 0x07 -> out.append("\\a")
                cp == '\b'.code -> out.append("\\b")
                cp == 0x0C -> out.append("\\f")
                cp == '\n'.code -> out.append("\\n")
                cp == '\r'.code -> out.append("\\r")
                cp == '\t'.code -> out.append("\\t")
                cp == 0x0B -> out.append("\\v")
                cp < 0x20 || cp == 0x7F -> out.append("\\x").append(hex(cp, 2))
                cp < 0x10000 -> out.append("\\u").append(hex(cp, 4))
                else -> out.append("\\U").append(hex(cp, 8))
            }
        }
        return out.append('"').toString()
    }

    private fun hex(v: Int, width: Int): String = Integer.toHexString(v).padStart(width, '0')

    /** `strconv.IsPrint`: letters, marks, numbers, punctuation, symbols and the ASCII space. */
    private fun isPrint(cp: Int): Boolean {
        if (cp == ' '.code) return true
        return when (Character.getType(cp).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
            Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> false
            else -> true
        }
    }
}
