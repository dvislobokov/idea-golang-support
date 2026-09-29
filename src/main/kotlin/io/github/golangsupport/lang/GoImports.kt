package io.github.golangsupport.lang

/**
 * The import of a package, written by the plugin itself: a package chosen from the completion list is imported at once, in the same
 * edit, so that what is typed after its dot is known to the language server. Where the line goes is a matter of gofmt and goimports,
 * which sort the block on save; here it is put next to its kind (the standard library first, as they do).
 */
object GoImports {
    class Insertion(val offset: Int, val text: String)

    private val PACKAGE_CLAUSE = Regex("""^package[ \t]+\w+[^\n]*""", RegexOption.MULTILINE)
    private val DECLARING = setOf("package", "func", "type", "var", "const", "import", "goto", "break", "continue")

    /** A path of the standard library has no domain in its first part. */
    fun isStandard(path: String): Boolean = '.' !in path.substringBefore('/')

    /** What to insert to import [path], null when it is imported already or the file has no package clause to put it after. */
    fun add(text: CharSequence, path: String): Insertion? {
        val imports = GoDeclarations.scan(text).imports
        if (imports.any { it.path == path }) return null
        val quoted = "\"$path\""
        if (imports.isEmpty()) {
            val clause = PACKAGE_CLAUSE.find(text) ?: return null
            return Insertion(clause.range.last + 1, "\n\nimport $quoted")
        }
        val grouped = imports.filter { !lineOf(text, it.range.startOffset).trimStart().startsWith("import") }
        if (grouped.isEmpty()) return Insertion(lineEnd(text, imports.last().range.endOffset), "\nimport $quoted")
        val standard = isStandard(path)
        val sameKind = grouped.lastOrNull { isStandard(it.path) == standard }
        return when {
            sameKind != null -> Insertion(lineEnd(text, sameKind.range.endOffset), "\n\t$quoted")
            // the first of its kind: the standard library above the rest, the rest below it, a blank line between them
            standard -> Insertion(lineStart(text, grouped.first().range.startOffset), "\t$quoted\n\n")
            else -> Insertion(lineEnd(text, grouped.last().range.endOffset), "\n\n\t$quoted")
        }
    }

    /**
     * Whether a name that begins at [start] may be the name of a package: not after a dot (a member of something), not where a name is
     * declared.
     */
    fun isPackagePlace(text: CharSequence, start: Int): Boolean {
        var i = start.coerceIn(0, text.length)
        while (i > 0 && (text[i - 1] == ' ' || text[i - 1] == '\t')) i--
        if (i > 0 && text[i - 1] == '.') return false
        var wordStart = i
        while (wordStart > 0 && (text[wordStart - 1].isLetterOrDigit() || text[wordStart - 1] == '_')) wordStart--
        return text.subSequence(wordStart, i).toString() !in DECLARING
    }

    /**
     * Whether [offset] is in the imports of the file: on a line of an import, from the first `import` to the end of the last one and
     * the bracket that closes its block. Where Alt+Enter is pressed for the imports to be put in order.
     */
    fun isInImports(text: CharSequence, offset: Int): Boolean {
        val imports = GoDeclarations.scan(text).imports
        if (imports.isEmpty()) return false
        val keyword = text.toString().lastIndexOf("import", imports.first().range.startOffset)
        val start = lineStart(text, if (keyword >= 0) keyword else imports.first().range.startOffset)
        var end = lineEnd(text, imports.last().range.endOffset)
        var next = end
        while (next < text.length && text[next].isWhitespace()) next++
        if (next < text.length && text[next] == ')') end = lineEnd(text, next)
        return offset in start..end
    }

    /** The name an import is used by in the file: its alias, or the name of the package as its path tells it. */
    fun nameOf(import: GoImport): String? = when (import.alias) {
        null -> GoSemanticColors.packageName(import.path)
        "_", "." -> null
        else -> import.alias
    }

    private val QUALIFIED = Regex("""(?<![\w.])([A-Za-z_]\w*)\.([A-Z]\w*)""")

    /**
     * What an edit has written, given the text before and after it: from the first character that differs to the last one, as
     * offsets of [after]. Null when nothing was added.
     */
    fun written(before: CharSequence, after: CharSequence): IntRange? {
        var start = 0
        val shorter = minOf(before.length, after.length)
        while (start < shorter && before[start] == after[start]) start++
        var tail = 0
        while (tail < shorter - start && before[before.length - 1 - tail] == after[after.length - 1 - tail]) tail++
        val end = after.length - tail
        return if (end > start) start until end else null
    }

    /**
     * The packages [range] of the text speaks of and the file does not import, each with the names it takes of it: `url` with `URL`
     * and `Values`. What gopls writes into a struct it fills: `URL: &url.URL{}`, and no import (checked with its answer).
     */
    fun missing(text: CharSequence, range: IntRange): Map<String, Set<String>> {
        val imported = GoDeclarations.scan(text).imports.mapNotNullTo(HashSet()) { nameOf(it) }
        val result = LinkedHashMap<String, MutableSet<String>>()
        val from = range.first.coerceIn(0, text.length)
        val part = text.subSequence(from, (range.last + 1).coerceIn(from, text.length))
        for (match in QUALIFIED.findAll(part)) {
            val (qualifier, name) = match.destructured
            if (qualifier !in imported) result.getOrPut(qualifier) { LinkedHashSet() } += name
        }
        return result
    }

    private fun lineStart(text: CharSequence, offset: Int): Int {
        var i = offset.coerceIn(0, text.length)
        while (i > 0 && text[i - 1] != '\n') i--
        return i
    }

    private fun lineEnd(text: CharSequence, offset: Int): Int {
        var i = offset.coerceIn(0, text.length)
        while (i < text.length && text[i] != '\n' && text[i] != '\r') i++
        return i
    }

    private fun lineOf(text: CharSequence, offset: Int): String = text.subSequence(lineStart(text, offset), lineEnd(text, offset)).toString()
}

/**
 * Composite literals, by the text: where Fill All Fields has something to fill. `Options{}` and `&http.Client{Timeout: t}` are
 * literals; `if ready {`, `for _, x := range items {` and `type T struct {` are not, and the difference is the word the line begins with.
 */
object GoStructLiterals {
    private val BLOCKS = setOf("if", "for", "switch", "select", "func", "type", "else", "case", "default", "struct", "interface", "range")

    private fun isNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    /** The caret is inside the braces of a literal with a type before them, or on that type. */
    fun isInLiteral(text: CharSequence, offset: Int): Boolean {
        val caret = offset.coerceIn(0, text.length)
        // on the type, its brace after it
        nameAt(text, caret)?.let { (_, end) -> skipSpaces(text, end).let { if (text.getOrNull(it) == '{') return isLiteralBrace(text, it) } }
        var depth = 0
        var i = caret - 1
        val limit = (caret - MAX_LOOK_BACK).coerceAtLeast(0)
        while (i >= limit) {
            when (text[i]) {
                '}' -> depth++
                '{' -> if (depth == 0) return isLiteralBrace(text, i) else depth--
            }
            i--
        }
        return false
    }

    private fun isLiteralBrace(text: CharSequence, brace: Int): Boolean {
        // `Client {` is a literal as well: gofmt takes the space away, a person may have typed it (reported)
        var before = brace
        while (before > 0 && (text[before - 1] == ' ' || text[before - 1] == '\t')) before--
        if (before == 0 || !(isNameChar(text[before - 1]) || text[before - 1] == ']')) return false
        var lineStart = brace
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        val line = text.subSequence(lineStart, brace).toString().trimStart().removePrefix("}").trimStart()
        return line.takeWhile { isNameChar(it) } !in BLOCKS
    }

    /**
     * The name at the caret that stands by itself, `Options` or `http.Client`, as (start, end): where `{}` would make a literal of it.
     * Null when something follows it: braces, a call, a member.
     */
    fun bareName(text: CharSequence, offset: Int): Pair<Int, Int>? {
        val (start, end) = nameAt(text, offset.coerceIn(0, text.length)) ?: return null
        val next = text.getOrNull(skipSpaces(text, end))
        if (next == '{' || text.getOrNull(end) == '(' || text.getOrNull(end) == '[' || text.getOrNull(end) == '.') return null
        return if (text[start].isLetter() || text[start] == '_') start to end else null
    }

    private fun skipSpaces(text: CharSequence, offset: Int): Int {
        var i = offset
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        return i
    }

    /**
     * Whether a name that begins at [start] stands where a value is expected, so that a struct type there is the beginning of a
     * literal: after `:=`, `=`, `&`, `return`, as an argument of a call, as a value of a field. In a declaration it is a type.
     */
    fun isValuePlace(text: CharSequence, start: Int): Boolean {
        var end = start.coerceIn(0, text.length)
        while (end > 0 && (text[end - 1] == ' ' || text[end - 1] == '\t')) end--
        if (end == 0) return false
        var lineStart = end
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        val line = text.subSequence(lineStart, end).toString().trimStart()
        val first = line.takeWhile { isNameChar(it) }
        return when (text[end - 1]) {
            '&' -> true
            '=' -> first != "type" && (end < 2 || text[end - 2] !in "=!<>")
            '(', ',' -> GoExpectedTypes.enclosingCall(text, end) != null || first == "return"
            ':' -> isInLiteral(text, end)
            'n' -> line == "return" || line.endsWith(" return")
            else -> false
        }
    }

    /** The name with its qualifier around [offset], the caret inside of it or right after it. */
    private fun nameAt(text: CharSequence, offset: Int): Pair<Int, Int>? {
        fun part(c: Char) = isNameChar(c) || c == '.'
        var start = offset
        while (start > 0 && part(text[start - 1])) start--
        var end = offset
        while (end < text.length && part(text[end])) end++
        while (end > start && text[end - 1] == '.') end--
        return if (end > start) start to end else null
    }

    private const val MAX_LOOK_BACK = 4000
}
