package io.github.golangsupport.project.impl

import io.github.golangsupport.project.api.GoModuleVersion
import io.github.golangsupport.project.api.GoReplace
import io.github.golangsupport.project.api.GoRequire
import io.github.golangsupport.project.api.GoRetract
import org.jetbrains.annotations.ApiStatus

/** The contents of a go.mod file. [errors] lists `line N: message` for malformed lines (which are skipped). */
@ApiStatus.Internal
data class GoModFile(
    val module: String? = null,
    val deprecated: String? = null,
    val go: String? = null,
    val toolchain: String? = null,
    val godebug: List<Pair<String, String>> = emptyList(),
    val requires: List<GoRequire> = emptyList(),
    val excludes: List<GoModuleVersion> = emptyList(),
    val replaces: List<GoReplace> = emptyList(),
    val retracts: List<GoRetract> = emptyList(),
    val tools: List<String> = emptyList(),
    val ignores: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
)

/** The contents of a go.work file. */
@ApiStatus.Internal
data class GoWorkFile(
    val go: String? = null,
    val toolchain: String? = null,
    val godebug: List<Pair<String, String>> = emptyList(),
    val uses: List<String> = emptyList(),
    val replaces: List<GoReplace> = emptyList(),
    val errors: List<String> = emptyList(),
)

/** A go.sum line: `path version[/go.mod] hash`. */
@ApiStatus.Internal
data class GoSumEntry(val path: String, val version: String, val isGoMod: Boolean, val hash: String)

/** A module section of `vendor/modules.txt`. */
@ApiStatus.Internal
data class GoVendoredModule(
    val path: String,
    val version: String?,
    val replacement: GoModuleVersion?,
    val explicit: Boolean,
    val goVersion: String?,
    val packages: List<String>,
)

/**
 * Pure parsers for go.mod, go.work, go.sum and vendor/modules.txt, following the grammar of
 * `golang.org/x/mod/modfile` (read.go, rule.go, work.go) and `cmd/go/internal/modload/vendor.go`.
 *
 * Supported: `//` comments (attached to the next line or trailing), factored blocks
 * `verb ( ... )`, interpreted and raw string quoting, `// indirect` (also `// indirect; ...`),
 * `// Deprecated:` on the module directive, retract rationale comments, and the directives
 * `module go toolchain godebug require exclude replace retract tool ignore` (go.mod) and
 * `go toolchain godebug use replace` (go.work).
 */
@ApiStatus.Internal
object GoModFileParser {

    /** One logical directive line, with its verb (the block verb inside blocks). */
    private data class Entry(val verb: String, val args: List<String>, val before: List<String>, val suffix: String?, val line: Int)

    private data class Token(val text: String, val quoted: Boolean)

    fun parseGoMod(text: CharSequence): GoModFile {
        val errors = mutableListOf<String>()
        var module: String? = null
        var deprecated: String? = null
        var go: String? = null
        var toolchain: String? = null
        val godebug = mutableListOf<Pair<String, String>>()
        val requires = mutableListOf<GoRequire>()
        val excludes = mutableListOf<GoModuleVersion>()
        val replaces = mutableListOf<GoReplace>()
        val retracts = mutableListOf<GoRetract>()
        val tools = mutableListOf<String>()
        val ignores = mutableListOf<String>()
        for (e in entries(text, errors)) {
            fun err(msg: String) {
                errors += "line ${e.line}: $msg"
            }
            val a = e.args
            when (e.verb) {
                "module" -> if (a.size == 1) {
                    module = a[0]
                    deprecated = deprecation(e.before + listOfNotNull(e.suffix))
                } else err("usage: module module/path")
                "go" -> if (a.size == 1) go = a[0] else err("usage: go 1.23")
                "toolchain" -> if (a.size == 1) toolchain = a[0] else err("usage: toolchain go1.23.1")
                "godebug" -> parseGodebug(a)?.let { godebug += it } ?: err("usage: godebug key=value")
                "require" -> if (a.size == 2) requires += GoRequire(a[0], a[1], isIndirect(e.suffix)) else err("usage: require module/path v1.2.3")
                "exclude" -> if (a.size == 2) excludes += GoModuleVersion(a[0], a[1]) else err("usage: exclude module/path v1.2.3")
                "replace" -> parseReplace(a)?.let { replaces += it } ?: err("usage: replace module/path [v1.2.3] => other/module v1.4 | replace module/path [v1.2.3] => ../local/directory")
                "retract" -> parseRetract(a, e)?.let { retracts += it } ?: err("usage: retract v1.2.3 | retract [v1.0.0, v1.1.0]")
                "tool" -> if (a.size == 1) tools += a[0] else err("usage: tool module/path/cmd")
                "ignore" -> if (a.size == 1) ignores += a[0] else err("usage: ignore ./dir")
                else -> err("unknown directive: ${e.verb}")
            }
        }
        return GoModFile(module, deprecated, go, toolchain, godebug, requires, excludes, replaces, retracts, tools, ignores, errors)
    }

    fun parseGoWork(text: CharSequence): GoWorkFile {
        val errors = mutableListOf<String>()
        var go: String? = null
        var toolchain: String? = null
        val godebug = mutableListOf<Pair<String, String>>()
        val uses = mutableListOf<String>()
        val replaces = mutableListOf<GoReplace>()
        for (e in entries(text, errors)) {
            fun err(msg: String) {
                errors += "line ${e.line}: $msg"
            }
            val a = e.args
            when (e.verb) {
                "go" -> if (a.size == 1) go = a[0] else err("usage: go 1.23")
                "toolchain" -> if (a.size == 1) toolchain = a[0] else err("usage: toolchain go1.23.1")
                "godebug" -> parseGodebug(a)?.let { godebug += it } ?: err("usage: godebug key=value")
                "use" -> if (a.size == 1) uses += a[0] else err("usage: use local/dir")
                "replace" -> parseReplace(a)?.let { replaces += it } ?: err("usage: replace module/path [v1.2.3] => other/module v1.4")
                else -> err("unknown directive: ${e.verb}")
            }
        }
        return GoWorkFile(go, toolchain, godebug, uses, replaces, errors)
    }

    fun parseGoSum(text: CharSequence): List<GoSumEntry> = text.lineSequence().mapNotNull { line ->
        val f = line.trim().split(Regex("\\s+"))
        if (f.size != 3) return@mapNotNull null
        val isGoMod = f[1].endsWith("/go.mod")
        GoSumEntry(f[0], f[1].removeSuffix("/go.mod"), isGoMod, f[2])
    }.toList()

    /** `vendor/modules.txt` (`modload.readVendorList`). Wildcard replacements appear as `# old => new` without packages. */
    fun parseVendorModulesTxt(text: CharSequence): List<GoVendoredModule> {
        val result = mutableListOf<GoVendoredModule>()
        var path: String? = null
        var version: String? = null
        var replacement: GoModuleVersion? = null
        var explicit = false
        var goVersion: String? = null
        val packages = mutableListOf<String>()
        fun flush() {
            val p = path ?: return
            result += GoVendoredModule(p, version, replacement, explicit, goVersion, packages.toList())
            path = null
            version = null
            replacement = null
            explicit = false
            goVersion = null
            packages.clear()
        }
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            when {
                line.startsWith("# ") -> {
                    flush()
                    val f = line.substring(2).trim().split(Regex("\\s+"))
                    val arrow = f.indexOf("=>")
                    val left = if (arrow >= 0) f.subList(0, arrow) else f
                    val right = if (arrow >= 0) f.subList(arrow + 1, f.size) else emptyList()
                    if (left.isEmpty() || left.size > 2) continue
                    path = left[0]
                    version = left.getOrNull(1)
                    replacement = when (right.size) {
                        1 -> GoModuleVersion(right[0], null)
                        2 -> GoModuleVersion(right[0], right[1])
                        else -> null
                    }
                }
                line.startsWith("## ") -> {
                    if (path == null) continue
                    for (annotation in line.substring(3).split(';').map { it.trim() }) {
                        when {
                            annotation == "explicit" -> explicit = true
                            annotation.startsWith("go ") -> goVersion = annotation.substring(3).trim()
                        }
                    }
                }
                line.isNotBlank() && !line.startsWith("#") -> if (path != null) packages += line.trim()
            }
        }
        flush()
        return result
    }

    // ---- shared syntax ----------------------------------------------------------------------

    private fun entries(text: CharSequence, errors: MutableList<String>): List<Entry> {
        val result = mutableListOf<Entry>()
        var pendingComments = mutableListOf<String>()
        var blockVerb: String? = null
        var lineNo = 0
        for (raw in text.lineSequence()) {
            lineNo++
            val (tokens, comment) = tokenize(raw, lineNo, errors)
            if (tokens.isEmpty()) {
                if (comment != null) pendingComments += comment else pendingComments = mutableListOf()
                continue
            }
            val words = tokens.map { it.text }
            if (blockVerb != null) {
                if (tokens.size == 1 && !tokens[0].quoted && words[0] == ")") {
                    blockVerb = null
                    pendingComments = mutableListOf()
                    continue
                }
                result += Entry(blockVerb, words, pendingComments, comment, lineNo)
                pendingComments = mutableListOf()
                continue
            }
            val verb = words[0]
            if (tokens.size == 2 && !tokens[1].quoted && words[1] == "(") {
                blockVerb = verb
                pendingComments = mutableListOf()
                continue
            }
            if (tokens.size == 3 && !tokens[1].quoted && words[1] == "(" && words[2] == ")") {
                pendingComments = mutableListOf()
                continue // empty block on one line
            }
            result += Entry(verb, words.drop(1), pendingComments, comment, lineNo)
            pendingComments = mutableListOf()
        }
        if (blockVerb != null) errors += "line $lineNo: unterminated block"
        return result
    }

    /** Splits one line into tokens and an optional `//` comment (returned without the slashes, trimmed). */
    private fun tokenize(line: String, lineNo: Int, errors: MutableList<String>): Pair<List<Token>, String?> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c.isWhitespace() -> i++
                line.startsWith("//", i) -> return tokens to line.substring(i + 2).trim()
                c == '(' || c == ')' || c == '[' || c == ']' || c == '{' || c == '}' || c == ',' -> {
                    tokens += Token(c.toString(), false)
                    i++
                }
                c == '"' || c == '`' -> {
                    val end = findClosingQuote(line, i)
                    if (end < 0) {
                        errors += "line $lineNo: unterminated quoted string"
                        return tokens to null
                    }
                    val literal = line.substring(i, end + 1)
                    val value = unquote(literal)
                    if (value == null) errors += "line $lineNo: invalid quoted string $literal"
                    tokens += Token(value ?: literal, true)
                    i = end + 1
                }
                else -> {
                    val start = i
                    while (i < line.length && isIdentChar(line[i]) && !line.startsWith("//", i)) i++
                    tokens += Token(line.substring(start, i), false)
                }
            }
        }
        return tokens to null
    }

    private fun isIdentChar(c: Char): Boolean =
        !c.isWhitespace() && c != '(' && c != ')' && c != '[' && c != ']' && c != '{' && c != '}' && c != ',' && c != '"' && c != '`'

    private fun findClosingQuote(s: String, start: Int): Int {
        val q = s[start]
        var i = start + 1
        while (i < s.length) {
            if (q == '"' && s[i] == '\\') {
                i += 2
                continue
            }
            if (s[i] == q) return i
            i++
        }
        return -1
    }

    /** Go `strconv.Unquote` for interpreted (`"..."`) and raw (`` `...` ``) strings. */
    fun unquote(literal: String): String? {
        if (literal.length < 2) return null
        if (literal[0] == '`') return if (literal.last() == '`') literal.substring(1, literal.length - 1) else null
        if (literal[0] != '"' || literal.last() != '"') return null
        val s = literal.substring(1, literal.length - 1)
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\') {
                sb.append(c)
                i++
                continue
            }
            if (i + 1 >= s.length) return null
            val e = s[i + 1]
            i += 2
            when (e) {
                'a' -> sb.append('\u0007')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'v' -> sb.append('\u000B')
                '\\' -> sb.append('\\')
                '"' -> sb.append('"')
                'x', 'u', 'U' -> {
                    val n = when (e) {
                        'x' -> 2
                        'u' -> 4
                        else -> 8
                    }
                    if (i + n > s.length) return null
                    val cp = s.substring(i, i + n).toIntOrNull(16) ?: return null
                    sb.appendCodePoint(cp)
                    i += n
                }
                in '0'..'7' -> {
                    if (i + 2 > s.length) return null
                    val cp = (e.toString() + s.substring(i, i + 2)).toIntOrNull(8) ?: return null
                    sb.append(cp.toChar())
                    i += 2
                }
                else -> return null
            }
        }
        return sb.toString()
    }

    private fun isIndirect(suffix: String?): Boolean {
        if (suffix == null) return false
        val f = suffix.trim()
        return f == "indirect" || f.startsWith("indirect;")
    }

    /**
     * `modfile.parseDeprecation`: in the directive comment (leading comments, then the suffix
     * comment), the paragraph starting with `Deprecated: `.
     */
    private fun deprecation(comments: List<String>): String? {
        val idx = comments.indexOfFirst { it.startsWith("Deprecated:") }
        if (idx < 0) return null
        val first = comments[idx].removePrefix("Deprecated:").trim()
        val rest = comments.drop(idx + 1).takeWhile { it.isNotBlank() }
        return (listOf(first) + rest).joinToString("\n").trim()
    }

    private fun parseGodebug(a: List<String>): Pair<String, String>? {
        if (a.size != 1) return null
        val eq = a[0].indexOf('=')
        if (eq <= 0) return null
        return a[0].substring(0, eq) to a[0].substring(eq + 1)
    }

    private fun parseReplace(a: List<String>): GoReplace? {
        val arrow = a.indexOf("=>")
        if (arrow !in 1..2) return null
        val left = a.subList(0, arrow)
        val right = a.subList(arrow + 1, a.size)
        if (right.size !in 1..2) return null
        return GoReplace(left[0], left.getOrNull(1), right[0], right.getOrNull(1))
    }

    private fun parseRetract(a: List<String>, e: Entry): GoRetract? {
        val rationale = (e.before.ifEmpty { listOfNotNull(e.suffix) }).joinToString("\n").trim().ifEmpty { null }
        if (a.size == 1) return GoRetract(a[0], a[0], rationale)
        if (a.size == 5 && a[0] == "[" && a[2] == "," && a[4] == "]") return GoRetract(a[1], a[3], rationale)
        return null
    }

    // ---- canonical printing (used to round-trip parsed values) ------------------------------

    /** Prints [mod] in canonical go.mod syntax; parsing the result yields the same values. */
    fun format(mod: GoModFile): String = buildString {
        mod.deprecated?.let { d -> d.lines().forEachIndexed { i, l -> append(if (i == 0) "// Deprecated: $l\n" else "// $l\n") } }
        mod.module?.let { append("module ").append(quoteIfNeeded(it)).append("\n") }
        mod.go?.let { append("\ngo ").append(it).append("\n") }
        mod.toolchain?.let { append("\ntoolchain ").append(it).append("\n") }
        block("godebug", mod.godebug.map { "${it.first}=${it.second}" })
        block("require", mod.requires.map { "${quoteIfNeeded(it.path)} ${it.version}" + if (it.indirect) " // indirect" else "" })
        block("exclude", mod.excludes.map { "${quoteIfNeeded(it.path)} ${it.version}" })
        block("replace", mod.replaces.map { r ->
            listOfNotNull(quoteIfNeeded(r.oldPath), r.oldVersion, "=>", quoteIfNeeded(r.newPath), r.newVersion).joinToString(" ")
        })
        if (mod.retracts.isNotEmpty()) {
            append("\nretract (\n")
            for (r in mod.retracts) {
                r.rationale?.lines()?.forEach { append("\t// ").append(it).append("\n") }
                append("\t").append(if (r.low == r.high) r.low else "[${r.low}, ${r.high}]").append("\n")
            }
            append(")\n")
        }
        block("tool", mod.tools.map(::quoteIfNeeded))
        block("ignore", mod.ignores.map(::quoteIfNeeded))
    }

    private fun StringBuilder.block(verb: String, lines: List<String>) {
        if (lines.isEmpty()) return
        append("\n").append(verb).append(" (\n")
        lines.forEach { append("\t").append(it).append("\n") }
        append(")\n")
    }

    private fun quoteIfNeeded(s: String): String {
        if (s.isNotEmpty() && s.all { isIdentChar(it) } && !s.contains("//")) return s
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
