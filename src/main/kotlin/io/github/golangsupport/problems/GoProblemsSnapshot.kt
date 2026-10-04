package io.github.golangsupport.problems

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * The findings of the last complete analysis, kept on disk with a fingerprint of everything they depend on: the analysed files (path,
 * size, time), go.mod / go.sum / go.work / vendor/modules.txt, the toolchain and build settings, the inspection profile, the plugin
 * version. A project opened again with the same fingerprint shows them at once and runs no full pass. When only files differ
 * ([fileChanges]) the findings are shown too and the changed, new and deleted files go to the incremental queue, which takes their packages
 * and importers along as during a session; any other difference (the plugin, the profile, the toolchain, the build settings) changes what
 * every file reports, and runs the full pass. Pure.
 */
object GoProblemsSnapshot {
    const val FORMAT = 1

    /** Paths of files changed or new since the snapshot, and of files gone (deleted, excluded). */
    class FileChanges(val changed: List<String>, val removed: List<String>)

    /** What differs between [then] and [now] when only `file` lines do; null when anything else does (a full pass), or [then] is empty (an old snapshot). */
    fun fileChanges(then: List<String>, now: List<String>): FileChanges? {
        if (then.isEmpty()) return null
        val (filesThen, otherThen) = then.partition { it.startsWith(FILE) }
        val (filesNow, otherNow) = now.partition { it.startsWith(FILE) }
        if (otherThen.toSet() != otherNow.toSet()) return null
        val before = filesThen.toSet()
        val pathsNow = filesNow.mapTo(HashSet(), ::pathOf)
        return FileChanges(filesNow.filter { it !in before }.map(::pathOf).distinct(), filesThen.map(::pathOf).filter { it !in pathsNow }.distinct())
    }

    private const val FILE = "file\t"

    private fun pathOf(line: String): String = line.removePrefix(FILE).substringBeforeLast('\t').substringBeforeLast('\t')

    /** [inputs]: the lines of the fingerprint, so that a mismatch can say what changed. */
    class Snapshot(val format: Int, val fingerprint: String, val files: Map<String, List<GoFinding>>, val inputs: List<String> = emptyList())

    /** What differs between the inputs of a snapshot and now, for the log: removed lines with `-`, new ones with `+`. */
    fun difference(then: List<String>, now: List<String>, limit: Int = 3): List<String> {
        val before = then.toSet()
        val after = now.toSet()
        return (then.filter { it !in after }.map { "-$it" } + now.filter { it !in before }.map { "+$it" }).take(limit)
    }

    /** One line per input; the order does not matter. */
    fun fingerprint(lines: Collection<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (line in lines.sorted()) { digest.update(line.toByteArray()); digest.update(0) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** The line of a file in the fingerprint. */
    fun fileLine(path: String, size: Long, modified: Long): String = "$FILE$path\t$size\t$modified"

    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val type = object : TypeToken<Snapshot>() {}.type

    fun write(target: Path, snapshot: Snapshot) {
        Files.createDirectories(target.parent)
        val temp = target.resolveSibling(target.fileName.toString() + ".tmp")
        Files.writeString(temp, gson.toJson(snapshot, type))
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** The snapshot at [source], or null when missing, unreadable or of another format. */
    @Suppress("SENSELESS_COMPARISON") // Gson fills the fields without Kotlin's checks
    fun read(source: Path): Snapshot? =
        runCatching { gson.fromJson<Snapshot>(Files.readString(source), type) }.getOrNull()?.takeIf { it.format == FORMAT && it.files != null && it.fingerprint != null }
}
