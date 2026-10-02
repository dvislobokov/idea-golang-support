package io.github.golangsupport.project.api

/**
 * A Go toolchain or language version such as `1.21`, `1.21rc1` or `1.21.3`, stored without the
 * `go` prefix. Ordering follows `go/version.Compare` (`internal/gover`): a language version sorts
 * before its release candidates, which sort before the first patch release:
 * `1.21 < 1.21rc1 < 1.21.0 < 1.21.1 < 1.22`.
 *
 * Construct with [parse]; the raw constructor accepts any text and invalid values sort first.
 */
@JvmInline
value class GoVersion(val value: String) : Comparable<GoVersion> {

    /** Major version (`1`), or -1 when invalid. */
    val major: Int get() = parsed()?.major?.toIntOrNull() ?: -1

    /** Minor version (`21` for `1.21.3`), or -1 when absent or invalid. */
    val minor: Int get() = parsed()?.minor?.toIntOrNull() ?: -1

    /** True when the text follows the Go version syntax. */
    val isValid: Boolean get() = parsed() != null

    /** The language version (`1.21` for `1.21.3` and `1.21rc1`). */
    val languageVersion: GoVersion
        get() {
            val p = parsed() ?: return this
            return if (p.minor.isEmpty()) GoVersion(p.major) else GoVersion("${p.major}.${p.minor}")
        }

    fun isAtLeast(other: GoVersion): Boolean = this >= other

    override fun compareTo(other: GoVersion): Int {
        val x = parsed()
        val y = other.parsed()
        if (x == null || y == null) return (if (x == null) 0 else 1) - (if (y == null) 0 else 1)
        return compareNum(x.major, y.major).takeIf { it != 0 }
            ?: compareNum(x.minor, y.minor).takeIf { it != 0 }
            ?: compareNum(x.patch, y.patch).takeIf { it != 0 }
            ?: x.kind.compareTo(y.kind).takeIf { it != 0 }
            ?: compareNum(x.pre, y.pre)
    }

    /** `go1.21.3` form, as used by release tags and `GOVERSION`. */
    override fun toString(): String = "go$value"

    private data class Parts(val major: String, val minor: String, val patch: String, val kind: String, val pre: String)

    private fun parsed(): Parts? {
        val m = SYNTAX.matchEntire(value) ?: return null
        val (major, minor, patch, kind, pre) = m.destructured
        // "1.21.0rc1" is not a valid version; a prerelease only follows major.minor.
        if (kind.isNotEmpty() && patch.isNotEmpty()) return null
        return Parts(major, minor, patch, kind, pre)
    }

    companion object {
        private val SYNTAX = Regex("""(0|[1-9]\d*)(?:\.(0|[1-9]\d*)(?:\.(0|[1-9]\d*))?)?(?:(alpha|beta|rc)(0|[1-9]\d*))?""")

        /**
         * Parses `go1.21.3`, `1.21`, `go1.22rc1`, or the first line of `$GOROOT/VERSION`
         * (`go1.27.1` followed by more lines). Returns null for anything else (including `devel` builds).
         */
        @JvmStatic
        fun parse(text: String): GoVersion? {
            val first = text.lineSequence().firstOrNull()?.trim() ?: return null
            val token = first.substringBefore(' ').removePrefix("go")
            val version = GoVersion(token)
            return version.takeIf { it.isValid }
        }

        /** Compares decimal strings numerically; the empty string sorts first. */
        private fun compareNum(x: String, y: String): Int = when {
            x == y -> 0
            x.length != y.length -> x.length.compareTo(y.length)
            else -> x.compareTo(y)
        }
    }
}
