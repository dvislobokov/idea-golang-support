package io.github.golangsupport.project.impl

import org.jetbrains.annotations.ApiStatus

/**
 * Semantic version comparison with Go's conventions (`golang.org/x/mod/semver`): a leading `v`,
 * shorthands `v1` and `v1.2`, prerelease ordering, build metadata (`+incompatible`) ignored.
 * Pseudo-versions (`v0.0.0-20240101000000-abcdef123456`) are prereleases and order correctly.
 * Invalid versions sort before all valid ones; `none` is handled by MVS, not here.
 */
@ApiStatus.Internal
object SemVer {
    private data class Parsed(val major: String, val minor: String, val patch: String, val prerelease: String)

    private val SYNTAX = Regex("""v(0|[1-9]\d*)(?:\.(0|[1-9]\d*)(?:\.(0|[1-9]\d*)(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?)?)?""")

    fun isValid(v: String): Boolean = parse(v) != null

    private fun parse(v: String): Parsed? {
        val m = SYNTAX.matchEntire(v) ?: return null
        val g = m.groupValues
        return Parsed(g[1], g[2].ifEmpty { "0" }, g[3].ifEmpty { "0" }, g[4])
    }

    /** `v1.2.3-pre+build` -> `v1`. */
    fun major(v: String): String? = parse(v)?.let { "v${it.major}" }

    fun isPseudo(v: String): Boolean = Regex("""-(?:0\.)?\d{14}-[0-9a-f]{12}(\+.*)?$|\.0\.\d{14}-[0-9a-f]{12}(\+.*)?$""").containsMatchIn(v)

    fun compare(v: String, w: String): Int {
        val pv = parse(v)
        val pw = parse(w)
        if (pv == null || pw == null) return (if (pv == null) 0 else 1) - (if (pw == null) 0 else 1)
        return compareInt(pv.major, pw.major).takeIf { it != 0 }
            ?: compareInt(pv.minor, pw.minor).takeIf { it != 0 }
            ?: compareInt(pv.patch, pw.patch).takeIf { it != 0 }
            ?: comparePrerelease(pv.prerelease, pw.prerelease)
    }

    /** The larger of two versions (`semver.Max` semantics for MVS). */
    fun max(v: String, w: String): String = if (compare(v, w) >= 0) v else w

    private fun compareInt(x: String, y: String): Int = when {
        x == y -> 0
        x.length != y.length -> x.length.compareTo(y.length)
        else -> x.compareTo(y)
    }

    private fun comparePrerelease(x0: String, y0: String): Int {
        if (x0 == y0) return 0
        if (x0.isEmpty()) return 1
        if (y0.isEmpty()) return -1
        val xs = x0.substring(1).split('.')
        val ys = y0.substring(1).split('.')
        for (i in 0 until minOf(xs.size, ys.size)) {
            val a = xs[i]
            val b = ys[i]
            if (a == b) continue
            val an = a.all { it.isDigit() }
            val bn = b.all { it.isDigit() }
            return when {
                an && bn -> compareInt(a, b)
                an -> -1
                bn -> 1
                else -> a.compareTo(b).coerceIn(-1, 1)
            }
        }
        return xs.size.compareTo(ys.size)
    }
}
