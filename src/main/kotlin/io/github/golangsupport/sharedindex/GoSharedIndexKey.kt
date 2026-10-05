package io.github.golangsupport.sharedindex

import java.security.MessageDigest

/**
 * What a shared index chunk of `$GOROOT/src` is for: the Go release, the host it was installed for and a hash of `$GOROOT/VERSION`.
 * The platform matches indexed files by content hash anyway, so a wrong chunk only misses; the key just keeps one directory per GOROOT
 * release and lets a CDN serve the right file. Development trees (no `goX.Y` in VERSION) get no key: their sources change under them.
 */
data class GoSharedIndexKey(val goVersion: String, val goos: String, val goarch: String, val hash: String) {
    /** The directory and file name part: `go1.27.1-windows-amd64-0123456789ab`. */
    val id: String get() = "$goVersion-$goos-$goarch-$hash"

    /** `go 1.27` for the go.mod of the throwaway project the chunk is dumped from. */
    val goDirective: String get() = goVersion.removePrefix("go").let { v -> RELEASE.matchEntire(v)?.let { "${it.groupValues[1]}.${it.groupValues[2]}" } ?: v }

    companion object {
        private val VERSION = Regex("go\\d+(\\.\\d+){0,2}((rc|beta)\\d+)?")
        private val RELEASE = Regex("(\\d+)\\.(\\d+).*")
        private val TOOL_DIR = Regex("([a-z0-9]+)_([a-z0-9]+)")
        private const val HASH_LENGTH = 12

        /** The release of the first line of `$GOROOT/VERSION` (`go1.27.1`); null for `devel ...` and anything else. */
        fun versionOf(versionFile: String): String? = versionFile.lineSequence().firstOrNull()?.trim()?.takeIf { VERSION.matches(it) }

        /**
         * GOOS / GOARCH of the installation from the names under `$GOROOT/pkg/tool` (`windows_amd64`): the [preferredGoos] /
         * [preferredGoarch] pair when present (cross-compiled tools add more), else the first one in name order.
         */
        fun hostOf(toolDirs: Collection<String>, preferredGoos: String?, preferredGoarch: String?): Pair<String, String>? {
            val pairs = toolDirs.sorted().mapNotNull { TOOL_DIR.matchEntire(it)?.let { m -> m.groupValues[1] to m.groupValues[2] } }
            return pairs.firstOrNull { it.first == preferredGoos && it.second == preferredGoarch } ?: pairs.firstOrNull()
        }

        /** Line endings do not count: the same release unpacked by git on Windows hashes the same. */
        fun hashOf(versionFile: String): String =
            MessageDigest.getInstance("SHA-256").digest(versionFile.replace("\r\n", "\n").toByteArray()).joinToString("") { "%02x".format(it) }.take(HASH_LENGTH)

        /** The key of a GOROOT with [versionFile] as its `VERSION` and [toolDirs] under `pkg/tool`; [goos] / [goarch] when there are none. */
        fun of(versionFile: String, toolDirs: Collection<String>, goos: String?, goarch: String?): GoSharedIndexKey? {
            val version = versionOf(versionFile) ?: return null
            val (os, arch) = hostOf(toolDirs, goos, goarch) ?: (goos?.takeIf { it.isNotBlank() } ?: return null) to (goarch?.takeIf { it.isNotBlank() } ?: return null)
            return GoSharedIndexKey(version, os, arch, hashOf(versionFile))
        }
    }
}
