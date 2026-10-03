package io.github.golangsupport.project.impl

import com.intellij.openapi.application.PathManager
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.TestOnly
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * The output of `go list -m -json -e all` kept between sessions, one file per go.mod / go.work location, valid while its key (the stamps
 * of go.mod / go.sum / go.work and the go binary with its version) is the same. Without it every session where the module cache lacks some
 * go.mod ran the command again and bumped the model once more (seen live: six runs in a row for one project).
 *
 * The directory is under the system directory of the IDE (`PathManager.getSystemPath()/go-psi/go-list`): go-psi does not know the plugin's
 * data directory (that one is for programs the plugin builds and runs, this is a cache like the indexes), and a test can point it elsewhere.
 * The raw output is stored, not the graph: [GoListModuleGraph.parse] makes the graph and reads the main go.mod files as on the first run.
 */
@ApiStatus.Internal
object GoListDiskCache {
    private const val HEADER = "go-list-cache 1"

    @Volatile private var directoryOverride: Path? = null

    fun directory(): Path = directoryOverride ?: Path.of(PathManager.getSystemPath(), "go-psi", "go-list")

    @TestOnly
    fun setDirectoryForTests(directory: Path?) {
        directoryOverride = directory
    }

    /** The file of [location]: a hash of its paths, so that two modules never share one. */
    fun fileOf(location: GoModuleGraphBuilder.Location): Path {
        val digest = MessageDigest.getInstance("SHA-1").digest("${location.goMod}|${location.goWork}".toByteArray())
        return directory().resolve(digest.take(10).joinToString("") { "%02x".format(it) } + ".json")
    }

    fun encode(key: String, output: String): String = "$HEADER\n$key\n$output"

    /** The output stored under [key], null when the text is of another key or of another format. */
    fun decode(text: String, key: String): String? {
        val first = text.indexOf('\n').takeIf { it >= 0 } ?: return null
        val second = text.indexOf('\n', first + 1).takeIf { it >= 0 } ?: return null
        if (text.substring(0, first) != HEADER || text.substring(first + 1, second) != key) return null
        return text.substring(second + 1)
    }

    fun read(location: GoModuleGraphBuilder.Location, key: String): String? =
        runCatching { fileOf(location).takeIf { Files.isRegularFile(it) }?.let { decode(Files.readString(it), key) } }.getOrNull()

    fun write(location: GoModuleGraphBuilder.Location, key: String, output: String) {
        runCatching {
            val file = fileOf(location)
            Files.createDirectories(file.parent)
            Files.writeString(file, encode(key, output))
        }
    }
}
