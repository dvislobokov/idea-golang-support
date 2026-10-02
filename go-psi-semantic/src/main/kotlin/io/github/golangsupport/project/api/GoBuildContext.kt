package io.github.golangsupport.project.api

/**
 * The configuration build constraints are evaluated against (the subset of `go/build.Context`
 * that affects file selection).
 *
 * @property goos target operating system (`GOOS`).
 * @property goarch target architecture (`GOARCH`).
 * @property cgoEnabled whether the `cgo` tag is satisfied (`CGO_ENABLED=1`).
 * @property compiler `gc` (default) or `gccgo`; satisfies the tag of the same name.
 * @property goVersion toolchain version; `goX.Y` release tags up to its minor version are satisfied.
 *   When null, every `go1.N` tag is considered satisfied (newest toolchain).
 * @property buildTags custom tags (`-tags`).
 * @property toolTags tool tags such as `goexperiment.rangefunc` or `amd64.v2`.
 */
data class GoBuildContext @JvmOverloads constructor(
    val goos: String,
    val goarch: String,
    val cgoEnabled: Boolean = true,
    val compiler: String = "gc",
    val goVersion: GoVersion? = null,
    val buildTags: Set<String> = emptySet(),
    val toolTags: Set<String> = emptySet(),
) {
    /**
     * Reports whether [tag] is satisfied, following `go/build.Context.matchTag`: `cgo`, GOOS,
     * GOARCH, the compiler, `linux` on android, `solaris` on illumos, `darwin` on ios, `unix` on
     * Unix systems, `boringcrypto` as `goexperiment.boringcrypto`, custom/tool tags and release tags.
     */
    fun matchTag(tag: String): Boolean {
        if (cgoEnabled && tag == "cgo") return true
        if (tag == goos || tag == goarch || tag == compiler) return true
        if (goos == "android" && tag == "linux") return true
        if (goos == "illumos" && tag == "solaris") return true
        if (goos == "ios" && tag == "darwin") return true
        if (tag == "unix" && goos in GoPlatforms.UNIX_OS) return true
        val name = if (tag == "boringcrypto") "goexperiment.boringcrypto" else tag
        return name in buildTags || name in toolTags || isReleaseTag(name)
    }

    /** `go1` .. `go1.N` where N is the toolchain minor version (`go/build.Context.ReleaseTags`). */
    private fun isReleaseTag(tag: String): Boolean {
        if (!tag.startsWith("go1")) return false
        val minor = when {
            tag == "go1" -> 0
            tag.startsWith("go1.") -> tag.substring(4).takeIf { s -> s.isNotEmpty() && s.all { it.isDigit() } && !(s.length > 1 && s[0] == '0') }
                ?.toIntOrNull() ?: return false
            else -> return false
        }
        val version = goVersion ?: return true
        return version.major > 1 || (version.major == 1 && version.minor >= minor)
    }

    companion object {
        /** linux/amd64 with cgo, the configuration tests pin. */
        @JvmField
        val LINUX_AMD64: GoBuildContext = GoBuildContext("linux", "amd64")
    }
}

/** Known GOOS/GOARCH values, ported from `$GOROOT/src/internal/syslist/syslist.go` (Go 1.27). */
object GoPlatforms {
    @JvmField
    val KNOWN_OS: Set<String> = setOf(
        "aix", "android", "darwin", "dragonfly", "freebsd", "hurd", "illumos", "ios", "js", "linux",
        "nacl", "netbsd", "openbsd", "plan9", "solaris", "wasip1", "windows", "zos",
    )

    /** GOOS values matched by the `unix` build tag (not used for file names). */
    @JvmField
    val UNIX_OS: Set<String> = setOf(
        "aix", "android", "darwin", "dragonfly", "freebsd", "hurd", "illumos", "ios", "linux", "netbsd",
        "openbsd", "solaris",
    )

    @JvmField
    val KNOWN_ARCH: Set<String> = setOf(
        "386", "amd64", "amd64p32", "arm", "armbe", "arm64", "arm64be", "loong64", "mips", "mipsle",
        "mips64", "mips64le", "mips64p32", "mips64p32le", "ppc", "ppc64", "ppc64le", "riscv", "riscv64",
        "s390", "s390x", "sparc", "sparc64", "wasm",
    )
}
