package io.github.golangsupport.ide.inspections

/**
 * Struct tags as `reflect.StructTag` reads them and vet `structtag` validates them: `key:"value"` pairs separated by spaces, the
 * value a Go double-quoted string. Pure functions over the tag value (the literal already unquoted), so they test without a project.
 */
object GoStructTags {

    /** One `key:"value"` pair; [start] / [end] are offsets of `key` and after the closing quote in the tag. */
    class Pair(val key: String, val value: String, val start: Int, val end: Int)

    /** The pairs `reflect.StructTag.Lookup` sees, and vet's error for the tag (null when valid). */
    class Parsed(val pairs: List<Pair>, val error: String?) {
        /** `reflect.StructTag.Lookup`: the value of the first pair with [key]. */
        fun lookup(key: String): String? = pairs.firstOrNull { it.key == key }?.value
    }

    const val ERR_PAIR = "bad syntax for struct tag pair"
    const val ERR_KEY = "bad syntax for struct tag key"
    const val ERR_VALUE = "bad syntax for struct tag value"
    const val ERR_VALUE_SPACE = "suspicious space in struct tag value"
    const val ERR_SPACE = "key:\"value\" pairs not separated by spaces"

    /** Keys whose values vet checks for spaces. */
    private val CHECK_SPACES = setOf("json", "xml", "asn1")

    /** vet `validateStructTag`, collecting the pairs on the way (reflect's view: also pairs after a missing separator). */
    fun parse(tag: String): Parsed {
        val pairs = ArrayList<Pair>()
        var error: String? = null
        var i = 0
        while (i < tag.length) {
            if (pairs.isNotEmpty() && tag[i] != ' ' && error == null) error = ERR_SPACE
            while (i < tag.length && tag[i] == ' ') i++
            if (i >= tag.length) break
            val keyStart = i
            while (i < tag.length && tag[i] > ' ' && tag[i] != ':' && tag[i] != '"' && tag[i].code != 0x7f) i++
            if (i == keyStart) return Parsed(pairs, error ?: ERR_KEY)
            if (i + 1 >= tag.length || tag[i] != ':') return Parsed(pairs, error ?: ERR_PAIR)
            if (tag[i + 1] != '"') return Parsed(pairs, error ?: ERR_VALUE)
            val key = tag.substring(keyStart, i)
            val quoteStart = i + 1
            var j = quoteStart + 1
            while (j < tag.length && tag[j] != '"') {
                if (tag[j] == '\\') j++
                j++
            }
            if (j >= tag.length) return Parsed(pairs, error ?: ERR_VALUE)
            val value = unquote(tag.substring(quoteStart, j + 1)) ?: return Parsed(pairs, error ?: ERR_VALUE)
            pairs += Pair(key, value, keyStart, j + 1)
            i = j + 1
            if (error == null && key in CHECK_SPACES) error = valueSpaceError(key, value)
        }
        return Parsed(pairs, error)
    }

    private fun valueSpaceError(key: String, original: String): String? {
        var value = original
        if (key == "xml") {
            if (value.trim(' ') != value || value.count { it == ' ' } > 1) return ERR_VALUE_SPACE
            val comma = value.indexOf(',')
            if (comma < 0) return null
            if (comma > 0 && value[comma - 1] == ' ') return ERR_VALUE_SPACE
            value = value.substring(comma + 1)
        } else if (key == "json") {
            val comma = value.indexOf(',')
            if (comma < 0) return null
            value = value.substring(comma + 1)
        }
        return if (' ' in value) ERR_VALUE_SPACE else null
    }

    /**
     * The tag with the quoting repaired, when the repair is unambiguous: bare values quoted (`json:x` → `json:"x"`), spaces after the
     * colon dropped, a missing closing quote at the end added, commas or nothing between pairs replaced by one space. Null when the
     * tag cannot be repaired or is already valid.
     */
    fun repaired(tag: String): String? {
        val out = ArrayList<String>()
        var i = 0
        while (true) {
            while (i < tag.length && (tag[i] == ' ' || (out.isNotEmpty() && tag[i] == ','))) i++
            if (i >= tag.length) break
            val keyStart = i
            while (i < tag.length && tag[i] > ' ' && tag[i] != ':' && tag[i] != '"' && tag[i] != ',' && tag[i].code != 0x7f) i++
            if (i == keyStart || i >= tag.length || tag[i] != ':') return null
            val key = tag.substring(keyStart, i)
            i++
            while (i < tag.length && tag[i] == ' ') i++
            val literal: String
            if (i < tag.length && tag[i] == '"') {
                var j = i + 1
                while (j < tag.length && tag[j] != '"') {
                    if (tag[j] == '\\') j++
                    j++
                }
                if (j >= tag.length) {
                    val rest = tag.substring(i + 1)
                    if (rest.isEmpty() || rest.any { it == ' ' || it == ':' || it == '"' || it == '\\' }) return null
                    literal = "\"$rest\""
                    i = tag.length
                } else {
                    literal = tag.substring(i, j + 1)
                    i = j + 1
                }
            } else {
                val start = i
                while (i < tag.length && tag[i] != ' ') i++
                val word = tag.substring(start, i)
                if (word.isEmpty() || word.any { it == '"' || it == '\'' || it == '`' || it == ':' || it == '\\' }) return null
                literal = "\"$word\""
            }
            if (unquote(literal) == null) return null
            out += "$key:$literal"
        }
        if (out.isEmpty()) return null
        val result = out.joinToString(" ")
        return result.takeIf { it != tag && parse(it).error == null }
    }

    /** [tag] without [pair] and the spaces before it (or after it, for the first pair). */
    fun without(tag: String, pair: Pair): String {
        var start = pair.start
        while (start > 0 && tag[start - 1] == ' ') start--
        var end = pair.end
        if (start == 0) while (end < tag.length && tag[end] == ' ') end++
        return tag.substring(0, start) + tag.substring(end)
    }

    /** Go `strconv.Unquote` of a double-quoted literal; null when it is not one. */
    fun unquote(literal: String): String? {
        if (literal.length < 2 || literal.first() != '"' || literal.last() != '"') return null
        val sb = StringBuilder()
        var i = 1
        val end = literal.length - 1
        while (i < end) {
            val c = literal[i]
            if (c == '"' || c == '\n') return null
            if (c != '\\') {
                sb.append(c)
                i++
                continue
            }
            if (i + 1 >= end) return null
            val e = literal[i + 1]
            i += 2
            when (e) {
                'a' -> sb.append('\u0007')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000c')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'v' -> sb.append('\u000b')
                '\\' -> sb.append('\\')
                '"' -> sb.append('"')
                'x', 'u', 'U' -> {
                    val n = when (e) { 'x' -> 2; 'u' -> 4; else -> 8 }
                    if (i + n > end) return null
                    val code = literal.substring(i, i + n).toIntOrNull(16) ?: return null
                    if (e == 'x') sb.append(code.toChar()) else if (Character.isValidCodePoint(code)) sb.appendCodePoint(code) else return null
                    i += n
                }
                in '0'..'7' -> {
                    if (i + 2 > end) return null
                    val code = (e.toString() + literal.substring(i, i + 2)).toIntOrNull(8) ?: return null
                    if (code > 255) return null
                    sb.append(code.toChar())
                    i += 2
                }
                else -> return null
            }
        }
        return sb.toString()
    }

    /** A Go double-quoted literal of [value] (for tags written as interpreted strings). */
    fun quote(value: String): String = buildString {
        append('"')
        for (c in value) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\t' -> append("\\t")
            '\r' -> append("\\r")
            else -> if (c < ' ') append("\\x%02x".format(c.code)) else append(c)
        }
        append('"')
    }
}
