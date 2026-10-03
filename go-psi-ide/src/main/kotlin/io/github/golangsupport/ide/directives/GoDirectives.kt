package io.github.golangsupport.ide.directives

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile

/** One whitespace-separated word of a directive comment: its [value] (unquoted) and where that is in the comment text. */
class GoDirectiveField(val value: String, val range: TextRange, val quoted: Boolean)

/** `//go:embed`, `//go:linkname` and `//go:generate` comments: word splitting and the file lookups their references share. */
object GoDirectives {

    /** Whether [text] is the comment `//go:[name]` followed by blanks or nothing. */
    fun isDirective(text: String, name: String): Boolean {
        val prefix = "//go:$name"
        return text.startsWith(prefix) && (text.length == prefix.length || text[prefix.length].let { it == ' ' || it == '\t' })
    }

    /**
     * The words of [text] from [from]; a word starting with one of [quotes] runs to the closing quote (`"` honours `\"` and `\\`).
     * The second value is a message when a quote is not closed (the words before it are kept).
     */
    fun fields(text: String, from: Int, quotes: String): Pair<List<GoDirectiveField>, String?> {
        val result = ArrayList<GoDirectiveField>()
        var i = from
        while (i < text.length) {
            if (text[i].isWhitespace()) { i++; continue }
            val q = text[i]
            if (q in quotes) {
                var j = i + 1
                val sb = StringBuilder()
                while (j < text.length && text[j] != q) {
                    if (q == '"' && text[j] == '\\' && j + 1 < text.length) j++
                    sb.append(text[j])
                    j++
                }
                if (j >= text.length) return result to "invalid quoted string: ${text.substring(i)}"
                result += GoDirectiveField(sb.toString(), TextRange(i + 1, j), true)
                i = j + 1
            } else {
                var j = i
                while (j < text.length && !text[j].isWhitespace()) j++
                result += GoDirectiveField(text.substring(i, j), TextRange(i, j), false)
                i = j
            }
        }
        return result to null
    }

    /** The file or directory [path] (slash-separated, relative) names under [dir]; null when absent or it leaves the tree. */
    fun relative(dir: VirtualFile, path: String): VirtualFile? {
        var cur: VirtualFile = dir
        for (seg in path.split('/')) {
            cur = when (seg) {
                "", "." -> cur
                ".." -> cur.parent ?: return null
                else -> cur.findChild(seg) ?: return null
            }
        }
        return cur
    }
}

/** `//go:embed` patterns: parsing, go's pattern validity, and matching against the file tree as `go build` does. */
object GoEmbed {

    /** One pattern word: [raw] as written, [pattern] without the `all:` prefix, [range] of [pattern] in the comment. */
    class Pattern(val raw: String, val pattern: String, val all: Boolean, val range: TextRange)

    fun isEmbed(text: String) = GoDirectives.isDirective(text, "embed")

    /** The patterns of an embed comment and the quoting error, if any. */
    fun patterns(text: String): Pair<List<Pattern>, String?> {
        val (fields, error) = GoDirectives.fields(text, "//go:embed".length, "\"`")
        return fields.map { f ->
            val all = f.value.startsWith("all:")
            val skip = if (all) 4 else 0
            Pattern(f.value, f.value.substring(skip), all, TextRange(f.range.startOffset + skip, f.range.endOffset))
        } to error
    }

    /** go's message when [pattern] is not a valid embed pattern (`fs.ValidPath` plus `path.Match` syntax), else null. */
    fun validate(pattern: Pattern): String? {
        val p = pattern.pattern
        val bad = p.isEmpty() || p.contains('\\') || p.startsWith("/") || p.endsWith("/") ||
            p.split('/').any { it.isEmpty() || it == "." || it == ".." || segmentRegex(it) == null }
        return if (bad) "pattern ${pattern.raw}: invalid pattern syntax" else null
    }

    private fun hasMeta(segment: String) = segment.any { it == '*' || it == '?' || it == '[' }

    /** [segment] (one path element, path.Match syntax) as a regex over names; null for a malformed class. */
    fun segmentRegex(segment: String): Regex? {
        val sb = StringBuilder()
        fun lit(c: Char) { if (c.isLetterOrDigit()) sb.append(c) else sb.append('\\').append(c) }
        var i = 0
        while (i < segment.length) {
            val c = segment[i]
            when (c) {
                '*' -> sb.append("[^/]*")
                '?' -> sb.append("[^/]")
                '[' -> {
                    var j = i + 1
                    val negated = j < segment.length && segment[j] == '^'
                    if (negated) j++
                    val start = j
                    while (j < segment.length && segment[j] != ']') j++
                    if (j >= segment.length || j == start) return null
                    sb.append(if (negated) "[^" else "[")
                    for (k in start until j) if (segment[k] == '-') sb.append('-') else lit(segment[k])
                    sb.append(']')
                    i = j
                }
                else -> lit(c)
            }
            i++
        }
        return runCatching { Regex(sb.toString()) }.getOrNull()
    }

    /** The files and directories [pattern] (valid) names under [dir]: glob elements match any child, hidden ones included. */
    fun match(dir: VirtualFile, pattern: String): List<VirtualFile> {
        var current = listOf(dir)
        for (seg in pattern.split('/')) {
            val regex = if (hasMeta(seg)) segmentRegex(seg) ?: return emptyList() else null
            current = current.flatMap { d ->
                when {
                    !d.isDirectory -> emptyList()
                    regex == null -> listOfNotNull(d.findChild(seg))
                    else -> d.children.filter { regex.matches(it.name) }
                }
            }
            if (current.isEmpty()) break
        }
        return current
    }

    /** Whether a directory has a file go would embed: not under `.`/`_` names unless [all]. */
    fun hasEmbeddable(dir: VirtualFile, all: Boolean): Boolean = dir.children.any { c ->
        val hidden = !all && (c.name.startsWith(".") || c.name.startsWith("_"))
        !hidden && (!c.isDirectory || hasEmbeddable(c, all))
    }
}
