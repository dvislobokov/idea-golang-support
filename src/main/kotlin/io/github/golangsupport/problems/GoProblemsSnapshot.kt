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
 * version. A project opened again with the same fingerprint shows them at once and runs no full pass; any difference runs the pass as
 * before. Whole-project on purpose: a file's findings depend on other files, and partial reuse would risk showing stale ones. Pure.
 */
object GoProblemsSnapshot {
    const val FORMAT = 1

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
    fun fileLine(path: String, size: Long, modified: Long): String = "file\t$path\t$size\t$modified"

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
