package io.github.golangsupport.project.api

import io.github.golangsupport.lang.psi.GoBuildConstraint
import io.github.golangsupport.lang.psi.GoFile

/**
 * A parsed build constraint expression (`go/build/constraint.Expr`). [toString] prints the
 * `//go:build` syntax with the same parenthesization as Go.
 */
sealed class GoConstraintExpr {
    /** Evaluates the expression; both operands of `&&`/`||` are always evaluated (as in Go). */
    abstract fun eval(ok: (String) -> Boolean): Boolean

    data class Tag(val tag: String) : GoConstraintExpr() {
        override fun eval(ok: (String) -> Boolean): Boolean = ok(tag)
        override fun toString(): String = tag
    }

    data class Not(val x: GoConstraintExpr) : GoConstraintExpr() {
        override fun eval(ok: (String) -> Boolean): Boolean = !x.eval(ok)
        override fun toString(): String = if (x is And || x is Or) "!($x)" else "!$x"
    }

    data class And(val x: GoConstraintExpr, val y: GoConstraintExpr) : GoConstraintExpr() {
        override fun eval(ok: (String) -> Boolean): Boolean {
            val xok = x.eval(ok)
            val yok = y.eval(ok)
            return xok && yok
        }

        override fun toString(): String = arg(x) + " && " + arg(y)
        private fun arg(e: GoConstraintExpr) = if (e is Or) "($e)" else e.toString()
    }

    data class Or(val x: GoConstraintExpr, val y: GoConstraintExpr) : GoConstraintExpr() {
        override fun eval(ok: (String) -> Boolean): Boolean {
            val xok = x.eval(ok)
            val yok = y.eval(ok)
            return xok || yok
        }

        override fun toString(): String = arg(x) + " || " + arg(y)
        private fun arg(e: GoConstraintExpr) = if (e is And) "($e)" else e.toString()
    }
}

/** A syntax error in a build constraint (`go/build/constraint.SyntaxError`). */
class GoConstraintSyntaxException(val offset: Int, message: String) : IllegalArgumentException(message)

/**
 * Evaluates Go build constraints the way `go/build` does: `//go:build` expressions, legacy
 * `// +build` lines, file name suffixes (`_GOOS`, `_GOARCH`, `_GOOS_GOARCH`, optionally followed by
 * `_test`), and the special tags handled by [GoBuildContext.matchTag].
 *
 * Pure Kotlin; ports `go/build/constraint` (expr.go, vers.go) and `go/build` (`parseFileHeader`,
 * `shouldBuild`, `goodOSArchFile`, `matchFile`).
 */
object GoBuildConstraintEvaluator {

    /** `go/build/constraint.maxSize`. */
    private const val MAX_SIZE = 1000
    private const val MAX_OLD_SIZE = 100

    /** Extensions `go/build` considers part of a package (`fileListForExt` plus `.go`). */
    private val PACKAGE_EXTENSIONS = setOf(
        ".go", ".c", ".cc", ".cxx", ".cpp", ".m", ".h", ".hh", ".hpp", ".hxx", ".f", ".F", ".for",
        ".f90", ".s", ".S", ".sx", ".swig", ".swigcxx", ".syso",
    )

    // ---- go/build/constraint ---------------------------------------------------------------

    /** Parses a single `//go:build ...` or `// +build ...` line (`constraint.Parse`). */
    @JvmStatic
    fun parse(line: String): GoConstraintExpr {
        splitGoBuild(line)?.let { return parseExpr(it) }
        splitPlusBuild(line)?.let { return parsePlusBuildExpr(it) }
        throw GoConstraintSyntaxException(0, "not a build constraint")
    }

    @JvmStatic
    fun isGoBuild(line: String): Boolean = splitGoBuild(line) != null

    @JvmStatic
    fun isPlusBuild(line: String): Boolean = splitPlusBuild(line) != null

    /** Parses a `//go:build` expression body (`x && (y || !z)`). */
    @JvmStatic
    fun parseExpr(text: String): GoConstraintExpr {
        val p = ExprParser(text)
        val x = p.or()
        if (p.tok.isNotEmpty()) throw GoConstraintSyntaxException(p.pos, "unexpected token ${p.tok}")
        return x
    }

    /** Parses a legacy `+build` body: space-separated options are ORed, comma-separated terms ANDed. */
    @JvmStatic
    fun parsePlusBuildExpr(text: String): GoConstraintExpr {
        var size = 0
        var x: GoConstraintExpr? = null
        for (clause in text.split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }) {
            var y: GoConstraintExpr? = null
            for (lit0 in clause.split(',')) {
                var lit = lit0
                val z: GoConstraintExpr
                if (lit.startsWith("!!") || lit == "!") {
                    z = GoConstraintExpr.Tag("ignore")
                } else {
                    var neg = false
                    if (lit.startsWith("!")) {
                        neg = true
                        lit = lit.substring(1)
                    }
                    val t = if (isValidTag(lit)) GoConstraintExpr.Tag(lit) else GoConstraintExpr.Tag("ignore")
                    z = if (neg) GoConstraintExpr.Not(t) else t
                }
                y = if (y == null) z else {
                    if (++size > MAX_OLD_SIZE) throw GoConstraintSyntaxException(0, "expression too complex for // +build lines")
                    GoConstraintExpr.And(y, z)
                }
            }
            x = if (x == null) y else {
                if (++size > MAX_OLD_SIZE) throw GoConstraintSyntaxException(0, "expression too complex for // +build lines")
                GoConstraintExpr.Or(x, y!!)
            }
        }
        return x ?: GoConstraintExpr.Tag("ignore")
    }

    /**
     * The minimum Go version implied by [x] (`constraint.GoVersion`): `go1.21` for
     * `linux && go1.21`, null when the expression can be satisfied without a version tag.
     */
    @JvmStatic
    fun goVersion(x: GoConstraintExpr): String? {
        val v = minVersion(x, 1)
        return when {
            v < 0 -> null
            v == 0 -> "go1"
            else -> "go1.$v"
        }
    }

    private fun minVersion(z: GoConstraintExpr, sign: Int): Int = when (z) {
        is GoConstraintExpr.And -> if (sign < 0) minOf(minVersion(z.x, sign), minVersion(z.y, sign)) else maxOf(minVersion(z.x, sign), minVersion(z.y, sign))
        is GoConstraintExpr.Or -> if (sign < 0) maxOf(minVersion(z.x, sign), minVersion(z.y, sign)) else minOf(minVersion(z.x, sign), minVersion(z.y, sign))
        is GoConstraintExpr.Not -> minVersion(z.x, -sign)
        is GoConstraintExpr.Tag -> when {
            sign < 0 -> -1
            z.tag == "go1" -> 0
            else -> z.tag.substringAfter("go1.", "").toIntOrNull()?.takeIf { z.tag.startsWith("go1.") } ?: -1
        }
    }

    private fun splitGoBuild(line0: String): String? {
        var line = line0
        if (line.endsWith("\n")) line = line.dropLast(1)
        if (line.contains('\n')) return null
        if (!line.startsWith("//go:build")) return null
        line = line.trim().substring("//go:build".length)
        val trim = line.trim()
        if (line.length == trim.length && line.isNotEmpty()) return null
        return trim
    }

    private fun splitPlusBuild(line0: String): String? {
        var line = line0
        if (line.endsWith("\n")) line = line.dropLast(1)
        if (line.contains('\n')) return null
        if (!line.startsWith("//")) return null
        line = line.substring(2).trim()
        if (!line.startsWith("+build")) return null
        line = line.substring("+build".length)
        val trim = line.trim()
        if (line.length == trim.length && line.isNotEmpty()) return null
        return trim
    }

    private fun isTagChar(c: Int): Boolean = Character.isLetter(c) || Character.isDigit(c) || c == '_'.code || c == '.'.code

    private fun isValidTag(word: String): Boolean = word.isNotEmpty() && word.codePoints().allMatch { isTagChar(it) }

    private class ExprParser(val s: String) {
        var i = 0
        var tok = ""
        var isTag = false
        var pos = 0
        var size = 0

        fun or(): GoConstraintExpr {
            var x = and()
            while (tok == "||") x = GoConstraintExpr.Or(x, and())
            return x
        }

        fun and(): GoConstraintExpr {
            var x = not()
            while (tok == "&&") x = GoConstraintExpr.And(x, not())
            return x
        }

        fun not(): GoConstraintExpr {
            if (++size > MAX_SIZE) throw GoConstraintSyntaxException(pos, "build expression too large")
            lex()
            if (tok == "!") {
                lex()
                if (tok == "!") throw GoConstraintSyntaxException(pos, "double negation not allowed")
                return GoConstraintExpr.Not(atom())
            }
            return atom()
        }

        fun atom(): GoConstraintExpr {
            if (tok == "(") {
                val start = pos
                val x = try {
                    or()
                } catch (e: GoConstraintSyntaxException) {
                    if (e.message == "unexpected end of expression") throw GoConstraintSyntaxException(e.offset, "missing close paren")
                    throw e
                }
                if (tok != ")") throw GoConstraintSyntaxException(start, "missing close paren")
                lex()
                return x
            }
            if (!isTag) {
                if (tok.isEmpty()) throw GoConstraintSyntaxException(pos, "unexpected end of expression")
                throw GoConstraintSyntaxException(pos, "unexpected token $tok")
            }
            val t = tok
            lex()
            return GoConstraintExpr.Tag(t)
        }

        fun lex() {
            isTag = false
            while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
            if (i >= s.length) {
                tok = ""
                pos = i
                return
            }
            when (s[i]) {
                '(', ')', '!' -> {
                    pos = i
                    i++
                    tok = s.substring(pos, i)
                    return
                }
                '&', '|' -> {
                    if (i + 1 >= s.length || s[i + 1] != s[i]) throw GoConstraintSyntaxException(i, "invalid syntax at ${s[i]}")
                    pos = i
                    i += 2
                    tok = s.substring(pos, i)
                    return
                }
            }
            var end = i
            while (end < s.length) {
                val c = s.codePointAt(end)
                if (!isTagChar(c)) break
                end += Character.charCount(c)
            }
            if (end == i) {
                val c = s.codePointAt(i)
                throw GoConstraintSyntaxException(i, "invalid syntax at " + String(Character.toChars(c)))
            }
            pos = i
            i = end
            tok = s.substring(pos, i)
            isTag = true
        }
    }

    // ---- go/build: file header ---------------------------------------------------------------

    /**
     * The build-relevant part of a file header (`go/build.parseFileHeader`).
     *
     * @property goBuild the `//go:build` line (anywhere in the leading comments), or null.
     * @property plusBuild `// +build` lines that end before the last blank line preceding the first
     *   non-comment text.
     * @property binaryOnly whether `//go:binary-only-package` was seen.
     * @property multipleGoBuild more than one `//go:build` line (an error in Go).
     */
    data class FileHeader(val goBuild: String?, val plusBuild: List<String>, val binaryOnly: Boolean, val multipleGoBuild: Boolean) {
        /** The same information in core's representation (expression text, `+build` lines). */
        fun toBuildConstraint(): GoBuildConstraint =
            GoBuildConstraint(goBuild?.let { splitGoBuild(it) }, plusBuild.map { it.removePrefix("//").trim() })
    }

    /** Port of `go/build.parseFileHeader` plus the `+build` line collection of `shouldBuild`. */
    @JvmStatic
    fun parseFileHeader(content: CharSequence): FileHeader {
        val text = content.toString()
        var end = 0
        var p = 0
        var ended = false
        var inSlashStar = false
        var goBuild: String? = null
        var multiple = false
        var binaryOnly = false
        lines@ while (p < text.length) {
            val nl = text.indexOf('\n', p)
            var line: String
            if (nl >= 0) {
                line = text.substring(p, nl)
                p = nl + 1
            } else {
                line = text.substring(p)
                p = text.length
            }
            line = line.trim()
            if (line.isEmpty() && !ended) {
                end = p
                continue@lines
            }
            if (!line.startsWith("//")) ended = true
            if (!inSlashStar && isGoBuildComment(line)) {
                if (goBuild != null) multiple = true else goBuild = line
            }
            if (!inSlashStar && line == "//go:binary-only-package") binaryOnly = true
            comments@ while (line.isNotEmpty()) {
                if (inSlashStar) {
                    val i = line.indexOf("*/")
                    if (i >= 0) {
                        inSlashStar = false
                        line = line.substring(i + 2).trim()
                        continue@comments
                    }
                    continue@lines
                }
                if (line.startsWith("//")) continue@lines
                if (line.startsWith("/*")) {
                    inSlashStar = true
                    line = line.substring(2).trim()
                    continue@comments
                }
                break@lines
            }
        }
        val plusBuild = text.substring(0, end).lineSequence().map { it.trim() }
            .filter { it.startsWith("//") && it.contains("+build") && isPlusBuild(it) }
            .toList()
        return FileHeader(goBuild, plusBuild, binaryOnly, multiple)
    }

    private fun isGoBuildComment(line: String): Boolean {
        if (!line.startsWith("//go:build")) return false
        val rest = line.substring("//go:build".length)
        return rest.isEmpty() || rest[0] == ' ' || rest[0] == '\t'
    }

    /**
     * Port of `go/build.Context.shouldBuild`: the `//go:build` line controls when present,
     * otherwise every `// +build` line must be satisfied. A malformed `//go:build` line excludes the file.
     */
    @JvmStatic
    fun shouldBuild(content: CharSequence, context: GoBuildContext): Boolean = shouldBuild(content, context::matchTag)

    /** [shouldBuild] with an arbitrary tag predicate (lets tests observe consulted tags). */
    @JvmStatic
    fun shouldBuild(content: CharSequence, matchTag: (String) -> Boolean): Boolean {
        val header = parseFileHeader(content)
        if (header.multipleGoBuild) return false
        return evalHeader(header.goBuild, header.plusBuild, matchTag)
    }

    /**
     * Evaluates a constraint captured by core (`GoFile.buildConstraint`, `GoBuildTagsIndex`).
     * Note: core's scanner only records `//go:build` lines that precede a blank line; prefer
     * [shouldBuild] on the file text where exact `go/build` behaviour matters.
     */
    @JvmStatic
    fun matches(constraint: GoBuildConstraint, context: GoBuildContext): Boolean =
        evalHeader(constraint.goBuild?.let { "//go:build $it" }, constraint.plusBuild.map { "//$it" }, context::matchTag)

    private fun evalHeader(goBuild: String?, plusBuild: List<String>, matchTag: (String) -> Boolean): Boolean {
        if (goBuild != null) {
            val x = try {
                parse(goBuild)
            } catch (_: GoConstraintSyntaxException) {
                return false
            }
            return x.eval(matchTag)
        }
        var result = true
        for (line in plusBuild) {
            val x = try {
                parse(line)
            } catch (_: GoConstraintSyntaxException) {
                continue
            }
            if (!x.eval(matchTag)) result = false
        }
        return result
    }

    // ---- go/build: file names ------------------------------------------------------------------

    /**
     * Port of `go/build.Context.goodOSArchFile`: false when [name] has a known `_GOOS`, `_GOARCH`
     * or `_GOOS_GOARCH` suffix (before an optional `_test`) that does not match [context].
     */
    @JvmStatic
    fun goodOSArchFile(name: String, context: GoBuildContext): Boolean = goodOSArchFile(name, context::matchTag)

    @JvmStatic
    fun goodOSArchFile(name0: String, matchTag: (String) -> Boolean): Boolean {
        var name = name0.substringBefore('.')
        val i = name.indexOf('_')
        if (i < 0) return true
        name = name.substring(i)
        var l = name.split('_')
        if (l.isNotEmpty() && l.last() == "test") l = l.dropLast(1)
        val n = l.size
        if (n >= 2 && l[n - 2] in GoPlatforms.KNOWN_OS && l[n - 1] in GoPlatforms.KNOWN_ARCH) {
            val arch = matchTag(l[n - 1])
            return arch && matchTag(l[n - 2])
        }
        if (n >= 1 && (l[n - 1] in GoPlatforms.KNOWN_OS || l[n - 1] in GoPlatforms.KNOWN_ARCH)) return matchTag(l[n - 1])
        return true
    }

    /**
     * Port of `go/build.Context.MatchFile`: whether file [name] with [content] belongs to the
     * package under [context]. Names starting with `_` or `.` and unknown extensions never match.
     */
    @JvmStatic
    fun matchFile(name: String, content: CharSequence, context: GoBuildContext): Boolean {
        if (name.startsWith("_") || name.startsWith(".")) return false
        val dot = name.lastIndexOf('.')
        val ext = if (dot < 0) "" else name.substring(dot)
        if (ext !in PACKAGE_EXTENSIONS) return false
        if (!goodOSArchFile(name, context)) return false
        if (ext == ".syso") return true
        return shouldBuild(content, context)
    }

    /** Whether [file] is part of its package under [context] (name rules and header constraints). */
    @JvmStatic
    fun matches(file: GoFile, context: GoBuildContext): Boolean = matchFile(file.name, file.viewProvider.contents, context)
}
