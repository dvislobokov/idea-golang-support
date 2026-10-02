package io.github.golangsupport.project.impl

import org.jetbrains.annotations.ApiStatus
import java.nio.file.Files
import java.nio.file.Path

/**
 * The on-disk layout of `GOMODCACHE` (go.dev/ref/mod#module-cache):
 *
 * - extracted module: `<cache>/<escaped path>@<escaped version>/`
 * - download cache: `<cache>/cache/download/<escaped path>/@v/<version>.mod|.info|.zip`, plus `list`
 *
 * Escaping replaces every upper-case letter with `!` and its lower-case form
 * (`github.com/Azure` -> `github.com/!azure`).
 */
@ApiStatus.Internal
class GoModuleCacheLayout(val root: Path) {

    fun downloadDir(modulePath: String): Path = root.resolve("cache/download").resolve(escape(modulePath)).resolve("@v")

    fun modFile(modulePath: String, version: String): Path = downloadDir(modulePath).resolve(escape(version) + ".mod")

    fun listFile(modulePath: String): Path = downloadDir(modulePath).resolve("list")

    fun extractedDir(modulePath: String, version: String): Path = root.resolve(escape(modulePath) + "@" + escape(version))

    /** Versions listed in `@v/list` (download cache); empty when absent. */
    fun listedVersions(modulePath: String): List<String> {
        val file = listFile(modulePath)
        if (!Files.isRegularFile(file)) return emptyList()
        return Files.readAllLines(file).map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** The go.mod of `path@version`: the download cache `.mod`, else the extracted directory's go.mod. */
    fun readGoMod(modulePath: String, version: String): Pair<Path, String>? {
        for (candidate in listOf(modFile(modulePath, version), extractedDir(modulePath, version).resolve("go.mod"))) {
            if (Files.isRegularFile(candidate)) return candidate to Files.readString(candidate)
        }
        return null
    }

    /**
     * For a path inside an extracted module directory, the module path, version and the path
     * relative to the module root (`/`-separated, empty for the root); null outside the cache.
     */
    fun locate(path: Path): Located? {
        val normalized = path.toAbsolutePath().normalize()
        val cacheRoot = root.toAbsolutePath().normalize()
        if (!normalized.startsWith(cacheRoot) || normalized == cacheRoot) return null
        val rel = cacheRoot.relativize(normalized).joinToString("/") { it.toString() }
        if (rel.startsWith("cache/")) return null
        val segments = rel.split('/')
        for ((i, segment) in segments.withIndex()) {
            val at = segment.indexOf('@')
            if (at < 0) continue
            val modulePath = (segments.subList(0, i) + segment.substring(0, at)).joinToString("/")
            val version = segment.substring(at + 1)
            val rest = segments.subList(i + 1, segments.size).joinToString("/")
            return Located(unescape(modulePath) ?: return null, unescape(version) ?: return null, rest)
        }
        return null
    }

    data class Located(val modulePath: String, val version: String, val relativePath: String)

    companion object {
        /** `module.EscapePath`: upper-case letters become `!` + lower case. */
        @JvmStatic
        fun escape(s: String): String {
            if (s.none { it.isUpperCase() }) return s
            val sb = StringBuilder(s.length + 4)
            for (c in s) {
                if (c in 'A'..'Z') sb.append('!').append(c.lowercaseChar()) else sb.append(c)
            }
            return sb.toString()
        }

        /** Inverse of [escape]; null for invalid escapes (an upper-case letter or `!` not followed by a-z). */
        @JvmStatic
        fun unescape(s: String): String? {
            val sb = StringBuilder(s.length)
            var bang = false
            for (c in s) {
                if (bang) {
                    if (c !in 'a'..'z') return null
                    sb.append(c.uppercaseChar())
                    bang = false
                } else if (c == '!') {
                    bang = true
                } else if (c in 'A'..'Z') {
                    return null
                } else {
                    sb.append(c)
                }
            }
            return if (bang) null else sb.toString()
        }
    }
}
