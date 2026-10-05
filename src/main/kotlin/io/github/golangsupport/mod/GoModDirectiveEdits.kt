package io.github.golangsupport.mod

/**
 * The text edits of the go.mod / go.work intentions, over lines (input lines → output lines), so the tests need no platform. A directive
 * is a line `verb args [// comment]` or a block `verb (` … `)`; the plugin has no go.mod PSI beyond tokens, and the project-model
 * parser behind [GoModFile] does not keep the layout these edits must preserve.
 */
object GoModDirectiveEdits {
    /** The verbs that may stand in a block; `module`, `go` and `toolchain` take one value per file. */
    val MERGEABLE = setOf("require", "replace", "exclude", "retract", "tool", "ignore", "godebug", "use")

    /** A directive over the zero-based lines [start]..[end]: one line, or a block from `verb (` to its `)`. */
    data class Directive(val verb: String, val start: Int, val end: Int, val block: Boolean)

    /** The directives of [lines]; an unterminated block ends the scan (the rest is not touched). */
    fun directives(lines: List<String>): List<Directive> {
        val result = ArrayList<Directive>()
        var i = 0
        while (i < lines.size) {
            val code = code(lines[i])
            val verb = code.takeWhile { !it.isWhitespace() && it != '(' }
            val rest = code.substring(verb.length).trim()
            when {
                verb.isEmpty() || verb == ")" -> i++
                rest == "(" -> {
                    var j = i + 1
                    while (j < lines.size && code(lines[j]) != ")") j++
                    if (j == lines.size) break
                    result += Directive(verb, i, j, true)
                    i = j + 1
                }
                else -> {
                    if (rest.isNotEmpty() && !rest.startsWith("(")) result += Directive(verb, i, i, false)
                    i++
                }
            }
        }
        return result
    }

    /** "Merge a group of directives": the run of consecutive one-line directives of the verb at [line] (two or more) as one block. */
    fun mergeGroup(lines: List<String>, line: Int): List<String>? {
        val ds = directives(lines)
        val at = ds.indexOfFirst { !it.block && it.start == line }.takeIf { it >= 0 } ?: return null
        val verb = ds[at].verb.takeIf { it in MERGEABLE } ?: return null
        fun joins(a: Int, b: Int) = !ds[a].block && !ds[b].block && ds[a].verb == verb && ds[b].verb == verb && ds[b].start == ds[a].start + 1
        var from = at
        while (from > 0 && joins(from - 1, from)) from--
        var to = at
        while (to < ds.lastIndex && joins(to, to + 1)) to++
        if (from == to) return null
        val group = (from..to).map { ds[it] }
        val block = listOf("$verb (") + group.map { "\t" + entry(lines[it.start], verb) } + ")"
        return edit(lines, group.mapTo(HashSet()) { it.start }, mapOf(group.first().start to block))
    }

    /** "Merge all directives": every directive of the verb at [line] (lines and blocks, two or more) as one block where the first stands. */
    fun mergeAll(lines: List<String>, line: Int): List<String>? {
        val ds = directives(lines)
        val verb = ds.firstOrNull { line in it.start..it.end }?.verb?.takeIf { it in MERGEABLE } ?: return null
        val same = ds.filter { it.verb == verb }
        if (same.size < 2) return null
        val entries = same.flatMap { d ->
            if (!d.block) listOf("\t" + entry(lines[d.start], verb)) else lines.subList(d.start + 1, d.end).dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        }
        val first = same.first()
        val header = if (first.block) lines[first.start] else "$verb ("
        val removed = same.flatMapTo(HashSet()) { it.start..it.end }
        return edit(lines, removed, mapOf(first.start to listOf(header) + entries + ")"))
    }

    /** "Merge directive up": the one-line directive at [line] into the block of the same verb right above it (blank lines between are allowed). */
    fun mergeUp(lines: List<String>, line: Int): List<String>? {
        val ds = directives(lines)
        val at = ds.indexOfFirst { !it.block && it.start == line }.takeIf { it > 0 } ?: return null
        val verb = ds[at].verb.takeIf { it in MERGEABLE } ?: return null
        val above = ds[at - 1]
        if (!above.block || above.verb != verb || (above.end + 1 until line).any { lines[it].isNotBlank() }) return null
        return edit(lines, setOf(line), mapOf(above.end to listOf("\t" + entry(lines[line], verb))))
    }

    /**
     * Whether the requires stand as `go mod tidy` lays them out: one directive (a line or a block), or two of which one holds only direct
     * requires and the other only `// indirect` ones. What `VgoRequireDirectivesMerge` is quiet on.
     */
    fun requiresAreGrouped(lines: List<String>): Boolean {
        val requires = directives(lines).filter { it.verb == "require" }
        if (requires.size < 2) return true
        if (requires.size > 2) return false
        val kinds = requires.map { d -> requireEntries(lines, d).filter { code(it).isNotEmpty() }.map(::isIndirect).toSet() }
        return kinds.all { it.size == 1 } && kinds[0] != kinds[1]
    }

    /**
     * "Merge 'require' directives": every require in one block of direct requires where the first require stands, then one block of
     * `// indirect` ones, as `go mod tidy` writes them. A comment line in a block goes with the require below it. Null when there is
     * nothing to merge.
     */
    fun mergeRequires(lines: List<String>): List<String>? {
        val requires = directives(lines).filter { it.verb == "require" }
        if (requires.size < 2) return null
        val direct = ArrayList<String>()
        val indirect = ArrayList<String>()
        for (d in requires) {
            val comments = ArrayList<String>()
            for (e in requireEntries(lines, d)) {
                if (code(e).isEmpty()) { comments += e; continue }
                (if (isIndirect(e)) indirect else direct).apply { addAll(comments); add(e) }
                comments.clear()
            }
            direct += comments
        }
        val merged = buildList {
            if (direct.isNotEmpty()) { add("require ("); addAll(direct); add(")") }
            if (direct.isNotEmpty() && indirect.isNotEmpty()) add("")
            if (indirect.isNotEmpty()) { add("require ("); addAll(indirect); add(")") }
        }
        return edit(lines, requires.flatMapTo(HashSet()) { it.start..it.end }, mapOf(requires.first().start to merged)).takeIf { it != lines }
    }

    /**
     * [lines] without the entries at [targets] (lines inside blocks or one-line directives); a block left with nothing but comments goes
     * whole. The fixes that drop a `replace` or an `ignore` path.
     */
    fun removeEntries(lines: List<String>, targets: Set<Int>): List<String> {
        val removed = HashSet(targets)
        for (d in directives(lines)) {
            if (!d.block) continue
            val inner = d.start + 1 until d.end
            if (inner.any { it in targets } && inner.all { it in targets || code(lines[it]).isEmpty() }) removed += d.start..d.end
        }
        return edit(lines, removed, emptyMap())
    }

    /** The entries of a require directive, each with its leading tab; blank lines of a block are dropped. */
    private fun requireEntries(lines: List<String>, d: Directive): List<String> =
        if (!d.block) listOf("\t" + entry(lines[d.start], d.verb)) else lines.subList(d.start + 1, d.end).filter { it.isNotBlank() }

    /** `// indirect` or `// indirect; comment`, as `modfile` reads it. */
    private fun isIndirect(line: String): Boolean = line.substringAfter("//", "").trim().let { it == "indirect" || it.startsWith("indirect;") }

    /** The lines of the `require` directive (or block) at [line], or null when [line] is not in one. */
    fun requireAt(lines: List<String>, line: Int): Directive? = directives(lines).firstOrNull { it.verb == "require" && line in it.start..it.end }

    /**
     * The smallest replacement that turns [old] into [new]: the first changed line, the end (exclusive) of the changed lines of [old],
     * and the lines that replace them.
     */
    fun change(old: List<String>, new: List<String>): Triple<Int, Int, List<String>> {
        var from = 0
        while (from < old.size && from < new.size && old[from] == new[from]) from++
        var tail = 0
        while (tail < old.size - from && tail < new.size - from && old[old.size - 1 - tail] == new[new.size - 1 - tail]) tail++
        return Triple(from, old.size - tail, new.subList(from, new.size - tail))
    }

    /** The line without its `//` comment, trimmed. */
    private fun code(line: String): String = line.substringBefore("//").trim()

    /** What follows the verb on a one-line directive, its comment included. */
    private fun entry(line: String, verb: String): String = line.trim().removePrefix(verb).trim()

    /**
     * [lines] without [removed], with [inserted] before the line of its key. A removed run between two blank lines (or a blank line
     * and the end) would leave two blank lines behind: the one before it goes too.
     */
    private fun edit(lines: List<String>, removed: Set<Int>, inserted: Map<Int, List<String>>): List<String> {
        val drop = HashSet(removed)
        var i = 0
        while (i < lines.size) {
            if (i !in removed || i in inserted) { i++; continue }
            var j = i
            while (j + 1 < lines.size && j + 1 in removed && j + 1 !in inserted) j++
            val before = i - 1
            val after = j + 1
            if (before >= 0 && before !in drop && lines[before].isBlank() && (after >= lines.size || lines[after].isBlank())) drop += before
            i = j + 1
        }
        return buildList {
            for (k in lines.indices) {
                inserted[k]?.let(::addAll)
                if (k !in drop) add(lines[k])
            }
        }
    }
}
