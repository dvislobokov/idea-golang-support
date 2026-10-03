package io.github.golangsupport.ide.inspections

import io.github.golangsupport.project.api.GoBuildConstraintEvaluator
import io.github.golangsupport.project.api.GoConstraintExpr
import io.github.golangsupport.project.api.GoConstraintSyntaxException
import io.github.golangsupport.project.api.GoPlatforms

/**
 * Pure helpers of the build-constraint inspection over the evaluator of the project model (`go/build/constraint` port): the
 * syntax check with an offset, `// +build` to `//go:build` conversion and the "looks like a GOOS/GOARCH but is not" lookup.
 */
object GoBuildConstraints {

    const val GO_BUILD = "//go:build"

    /** A syntax error of the expression of a `//go:build` line: [offset] counts from the start of the line. */
    class SyntaxError(val offset: Int, val message: String)

    /** The syntax error of the `//go:build` [line] (the comment text), or null when it parses. */
    fun syntaxError(line: String): SyntaxError? {
        val body = line.substring(GO_BUILD.length)
        val skipped = body.length - body.trimStart().length
        return try {
            GoBuildConstraintEvaluator.parseExpr(body.trim())
            null
        } catch (e: GoConstraintSyntaxException) {
            SyntaxError(GO_BUILD.length + skipped + e.offset, e.message ?: "syntax error")
        }
    }

    /** The `//go:build` expression equivalent to the `// +build` [lines] (several lines are ANDed), or null when one does not parse. */
    fun plusBuildToGoBuild(lines: List<String>): String? {
        val exprs = lines.map { line ->
            if (!GoBuildConstraintEvaluator.isPlusBuild(line)) return null
            try {
                GoBuildConstraintEvaluator.parse(line)
            } catch (_: GoConstraintSyntaxException) {
                return null
            }
        }
        if (exprs.isEmpty()) return null
        return exprs.reduce { a, b -> GoConstraintExpr.And(a, b) }.toString()
    }

    /** A tag of an expression: its text and the start offset in the line. */
    data class TagAt(val tag: String, val offset: Int)

    private val TAG = Regex("[A-Za-z0-9_.]+")

    /** Tags of [line] after its first [prefixLength] characters, with offsets from the line start. */
    fun tags(line: String, prefixLength: Int): List<TagAt> = TAG.findAll(line, prefixLength).map { TagAt(it.value, it.range.first) }.toList()

    /** Tags the toolchain defines besides GOOS/GOARCH: never suspected of being a typo. */
    private val SPECIAL = setOf("cgo", "unix", "ignore", "race", "msan", "asan", "purego", "boringcrypto", "gccgo", "compiler_bootstrap", "gc")

    /** The known GOOS/GOARCH [tag] is one edit away from (but not equal to), or null. Short tags and toolchain tags are never suspected. */
    fun suggest(tag: String): String? {
        if (tag.length < 3 || tag in SPECIAL || tag in GoPlatforms.KNOWN_OS || tag in GoPlatforms.KNOWN_ARCH) return null
        if (tag.startsWith("go1") || tag.startsWith("goexperiment.")) return null
        return (GoPlatforms.KNOWN_OS.sorted() + GoPlatforms.KNOWN_ARCH.sorted()).firstOrNull { withinOneEdit(tag, it) }
    }

    /** Whether the Levenshtein distance of [a] and [b] is exactly one (insertion, deletion or substitution). */
    fun withinOneEdit(a: String, b: String): Boolean {
        if (a == b || kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return when {
            a.length == b.length -> a.substring(i + 1) == b.substring(i + 1)
            a.length > b.length -> a.substring(i + 1) == b.substring(i)
            else -> a.substring(i) == b.substring(i + 1)
        }
    }
}
