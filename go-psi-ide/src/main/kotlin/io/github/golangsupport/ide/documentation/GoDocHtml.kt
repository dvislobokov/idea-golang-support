package io.github.golangsupport.ide.documentation

import com.intellij.openapi.util.text.StringUtil

/**
 * Go doc comment syntax to HTML: the essentials of `go/doc/comment` (Go 1.19+).
 *
 * - paragraphs are separated by blank lines;
 * - a span of indented lines is a code block (`<pre>`), unless it is a list;
 * - list items start with `-`, `*`, `+`, `•` (bullets) or `N.` / `N)` (numbers), indented or not
 *   (`go/doc/comment` only treats indented markers as lists; unindented `- x` lines are accepted too
 *   since gofmt indents them anyway);
 * - `# Heading` on a line of its own, surrounded by blank lines, is a heading;
 * - `[Text]: URL` link definitions at the end are removed and turn `[Text]` into links;
 * - `[Name]`, `[pkg.Name]`, `[*pkg.Name]` doc links are rendered as code;
 * - bare `http://` / `https://` URLs become links;
 * - ```` `` ```` and `''` become typographic quotes.
 */
object GoDocHtml {

    private sealed class Block {
        data class Paragraph(val lines: List<String>) : Block()
        data class Heading(val text: String) : Block()
        data class Code(val lines: List<String>) : Block()
        data class ListBlock(val ordered: Boolean, val items: List<List<String>>) : Block()
    }

    @JvmStatic
    fun toHtml(text: String): String {
        val lines = text.replace("\r\n", "\n").split('\n').toMutableList()
        val links = extractLinkDefinitions(lines)
        val blocks = parse(lines)
        val sb = StringBuilder()
        for (b in blocks) {
            when (b) {
                is Block.Paragraph -> sb.append("<p>").append(inline(b.lines.joinToString("\n"), links)).append("</p>")
                is Block.Heading -> sb.append("<h3>").append(inline(b.text, links)).append("</h3>")
                is Block.Code -> sb.append("<pre><code>").append(StringUtil.escapeXmlEntities(b.lines.joinToString("\n"))).append("</code></pre>")
                is Block.ListBlock -> {
                    val tag = if (b.ordered) "ol" else "ul"
                    sb.append('<').append(tag).append('>')
                    for (item in b.items) sb.append("<li>").append(inline(item.joinToString("\n"), links)).append("</li>")
                    sb.append("</").append(tag).append('>')
                }
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    /** Removes trailing `[Text]: URL` lines (go/doc/comment link definitions) and returns them. */
    private fun extractLinkDefinitions(lines: MutableList<String>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val iterator = lines.listIterator()
        while (iterator.hasNext()) {
            val m = LINK_DEF.matchEntire(iterator.next().trim()) ?: continue
            result.putIfAbsent(m.groupValues[1], m.groupValues[2])
            iterator.remove()
        }
        return result
    }

    private fun parse(lines: List<String>): List<Block> {
        val blocks = ArrayList<Block>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) { i++; continue }
            val indented = line[0] == ' ' || line[0] == '\t'
            when {
                listMarker(line) != null && (indented || startsListAt(lines, i)) -> {
                    val (block, next) = parseList(lines, i)
                    blocks += block
                    i = next
                }
                indented -> {
                    val code = ArrayList<String>()
                    while (i < lines.size && (lines[i].isBlank() || lines[i][0] == ' ' || lines[i][0] == '\t')) { code += lines[i]; i++ }
                    while (code.isNotEmpty() && code.last().isBlank()) code.removeAt(code.size - 1)
                    blocks += Block.Code(unindent(code))
                }
                isHeading(lines, i) -> {
                    blocks += Block.Heading(line.removePrefix("#").trim())
                    i++
                }
                else -> {
                    val para = ArrayList<String>()
                    while (i < lines.size && lines[i].isNotBlank() && lines[i][0] != ' ' && lines[i][0] != '\t' &&
                        !(para.isNotEmpty() && listMarker(lines[i]) != null && startsListAt(lines, i))
                    ) { para += lines[i]; i++ }
                    blocks += Block.Paragraph(para)
                }
            }
        }
        return blocks
    }

    /** An unindented `- item` starts a list only when it is not in the middle of running text. */
    private fun startsListAt(lines: List<String>, i: Int): Boolean = listMarker(lines[i]) != null && (i == 0 || lines[i - 1].isBlank() || listMarker(lines[i - 1]) != null)

    private fun isHeading(lines: List<String>, i: Int): Boolean {
        val line = lines[i]
        if (!line.startsWith("# ") || line.length < 3) return false
        val before = i == 0 || lines[i - 1].isBlank()
        val after = i + 1 >= lines.size || lines[i + 1].isBlank()
        return before && after
    }

    private fun parseList(lines: List<String>, start: Int): Pair<Block, Int> {
        val items = ArrayList<MutableList<String>>()
        var ordered = false
        var i = start
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                // A blank line ends the list unless the next line continues it.
                if (i + 1 < lines.size && listMarker(lines[i + 1])?.first == ordered) { i++; continue }
                break
            }
            val marker = listMarker(line)
            if (marker != null) {
                if (items.isEmpty()) ordered = marker.first else if (marker.first != ordered) break
                items += mutableListOf(line.trimStart().substring(marker.second).trim())
            } else if ((line[0] == ' ' || line[0] == '\t') && items.isNotEmpty()) {
                items.last() += line.trim()
            } else {
                break
            }
            i++
        }
        return Block.ListBlock(ordered, items) to i
    }

    /** (ordered, marker length) of a list item line, or null. */
    private fun listMarker(line: String): Pair<Boolean, Int>? {
        val t = line.trimStart()
        if (t.length >= 2 && t[0] in "-*+•" && (t[1] == ' ' || t[1] == '\t')) return false to 1
        val m = NUMBER_MARKER.find(t) ?: return null
        return true to m.value.length
    }

    private fun unindent(lines: List<String>): List<String> {
        val prefix = lines.filter { it.isNotBlank() }.map { l -> l.takeWhile { it == ' ' || it == '\t' } }
            .reduceOrNull { a, b -> a.commonPrefixWith(b) } ?: ""
        return lines.map { if (it.startsWith(prefix)) it.substring(prefix.length) else it.trim() }
    }

    /** Escapes text, then turns URLs, link definitions and doc links into markup. */
    private fun inline(text: String, links: Map<String, String>): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val url = if (text.startsWith("http", i)) URL.matchAt(text, i) else null
            if (url != null) {
                val href = url.value.trimEnd('.', ',', ':', ';', ')')
                sb.append("<a href=\"").append(StringUtil.escapeXmlEntities(href)).append("\">").append(StringUtil.escapeXmlEntities(href)).append("</a>")
                i += href.length
                continue
            }
            if (text[i] == '[') {
                val close = text.indexOf(']', i + 1)
                if (close > i + 1) {
                    val inner = text.substring(i + 1, close)
                    val target = links[inner]
                    when {
                        target != null -> {
                            sb.append("<a href=\"").append(StringUtil.escapeXmlEntities(target)).append("\">").append(StringUtil.escapeXmlEntities(inner)).append("</a>")
                            i = close + 1
                            continue
                        }
                        DOC_LINK.matches(inner) -> {
                            sb.append("<code>").append(StringUtil.escapeXmlEntities(inner)).append("</code>")
                            i = close + 1
                            continue
                        }
                    }
                }
            }
            when {
                text.startsWith("``", i) -> { sb.append("&ldquo;"); i += 2 }
                text.startsWith("''", i) -> { sb.append("&rdquo;"); i += 2 }
                else -> { sb.append(StringUtil.escapeXmlEntities(text[i].toString())); i++ }
            }
        }
        return sb.toString()
    }

    private val LINK_DEF = Regex("""\[([^\]]+)]:\s+(\S+)""")
    private val NUMBER_MARKER = Regex("""^\d+[.)][ \t]""")
    private val URL = Regex("""https?://[^\s<>"]+""")
    private val DOC_LINK = Regex("""\*?([\p{L}_][\p{L}\p{Nd}_]*(/[\p{L}\p{Nd}_.\-]+)*\.)?[\p{L}_][\p{L}\p{Nd}_]*(\.[\p{L}_][\p{L}\p{Nd}_]*)?""")
}
