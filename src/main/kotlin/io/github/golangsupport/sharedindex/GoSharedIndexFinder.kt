package io.github.golangsupport.sharedindex

import com.intellij.indexing.shared.local.SharedIndexLocalFinder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.util.io.HttpRequests
import io.github.golangsupport.cli.GoPluginLog
import io.github.golangsupport.settings.GoSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * `com.intellij.sharedIndexLocalFinder`: the platform's on-disk locator (`OnDiskSharedIndexChunkLocator`, a startup activity that runs
 * before the first indexing) asks for chunk files and attaches the compatible ones, so GOROOT files are not indexed again. Without a
 * local chunk and with a download address in Settings | Go, the chunk is fetched in the background and used from the next project open.
 */
class GoSharedIndexFinder : SharedIndexLocalFinder {
    override fun findSharedIndexChunks(project: Project): List<Path> = try {
        val (_, key) = GoSharedIndexes.gorootOf(project) ?: return emptyList()
        val chunks = GoSharedIndexes.chunks(key)
        if (chunks.isNotEmpty()) GoPluginLog.info(GoSharedIndexes.LOG_CATEGORY, "GOROOT shared index ${key.id}: ${chunks.joinToString { it.fileName.toString() }}")
        else GoSharedIndexDownloader.scheduleIfConfigured(key)
        chunks
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: Exception) {
        GoPluginLog.warn(GoSharedIndexes.LOG_CATEGORY, "GOROOT shared index lookup failed", e)
        emptyList()
    }
}

/** Fetches the chunk of a key from `<url>/index.json` (GoSharedIndexLayout) once per IDE session; never on the caller's thread. */
object GoSharedIndexDownloader {
    private val attempted = ConcurrentHashMap.newKeySet<String>()

    fun scheduleIfConfigured(key: GoSharedIndexKey) {
        val indexUrl = GoSharedIndexLayout.remoteIndexUrl(GoSettings.getInstance().sharedIndexUrl) ?: return
        if (!attempted.add(key.id)) return
        ApplicationManager.getApplication().executeOnPooledThread { download(indexUrl, key) }
    }

    private fun download(indexUrl: String, key: GoSharedIndexKey) {
        val log = GoSharedIndexes.LOG_CATEGORY
        try {
            val build = GoSharedIndexes.ideBuild()
            val chunk = GoSharedIndexLayout.select(GoSharedIndexLayout.parseRemoteIndex(HttpRequests.request(indexUrl).readString()), key.id, build)
            if (chunk == null) {
                GoPluginLog.info(log, "No GOROOT shared index for ${key.id} / $build at $indexUrl")
                return
            }
            val url = GoSharedIndexLayout.resolve(indexUrl, chunk.url)
            val dir = Files.createDirectories(GoSharedIndexes.directory(key))
            val partial = Files.createTempFile(dir, "download", ".part")
            try {
                HttpRequests.request(url).saveToFile(partial.toFile(), null)
                val actual = sha256(partial)
                if (chunk.sha256 != null && !chunk.sha256.equals(actual, ignoreCase = true)) {
                    GoPluginLog.warn(log, "GOROOT shared index $url: sha256 $actual, expected ${chunk.sha256}; dropped")
                    return
                }
                Files.move(partial, dir.resolve(GoSharedIndexLayout.fileName(url)), StandardCopyOption.REPLACE_EXISTING)
                GoPluginLog.info(log, "GOROOT shared index ${key.id} downloaded from $url; used from the next project open")
            } finally {
                Files.deleteIfExists(partial)
            }
        } catch (e: Exception) {
            GoPluginLog.warn(log, "GOROOT shared index download from $indexUrl failed", e)
        }
    }

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
