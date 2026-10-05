package io.github.golangsupport.sharedindex

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Files of the GOROOT shared indexes, local and remote (docs/SHARED-INDEXES.md):
 *
 * - local: `<IDE system dir>/go-plugin/shared-indexes/<key>/` holds the `*.ijx` chunks `dump-shared-index` wrote and [MANIFEST];
 * - remote: `<url>/index.json` lists chunks as `{"chunks": [{"key", "ideBuild", "url", "sha256"}]}`; `url` is absolute or relative to
 *   the directory of `index.json`. A chunk is only usable by the IDE build that produced it (the platform compares index versions).
 */
object GoSharedIndexLayout {
    const val CHUNK_SUFFIX = ".ijx"
    const val MANIFEST = "go-shared-index.json"
    const val REMOTE_INDEX = "index.json"
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /** The chunk files among [names], in name order: what the platform attaches. */
    fun chunks(names: Collection<String>): List<String> = names.filter { it.endsWith(CHUNK_SUFFIX) }.sorted()

    /** What was dumped: written next to the chunks, read by nobody but people and the CDN publisher. */
    data class Manifest(val key: String, val goVersion: String, val ideBuild: String, val pluginVersion: String, val created: String)

    fun manifestJson(manifest: Manifest): String = gson.toJson(manifest)

    fun parseManifest(json: String): Manifest? = runCatching {
        val o = JsonParser.parseString(json).asJsonObject
        Manifest(o.str("key") ?: return null, o.str("goVersion").orEmpty(), o.str("ideBuild").orEmpty(), o.str("pluginVersion").orEmpty(), o.str("created").orEmpty())
    }.getOrNull()

    data class RemoteChunk(val key: String, val ideBuild: String, val url: String, val sha256: String?)

    /** The address of `index.json` for the configured [base]: a `.json` address as is, a directory with [REMOTE_INDEX] appended. */
    fun remoteIndexUrl(base: String): String? {
        val trimmed = base.trim().takeIf { it.isNotEmpty() } ?: return null
        return if (trimmed.endsWith(".json")) trimmed else trimmed.trimEnd('/') + "/" + REMOTE_INDEX
    }

    /** Entries without a key, build or url are skipped; a broken document is no chunks. */
    fun parseRemoteIndex(json: String): List<RemoteChunk> = runCatching {
        JsonParser.parseString(json).asJsonObject.getAsJsonArray("chunks")?.mapNotNull { e ->
            val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            RemoteChunk(o.str("key") ?: return@mapNotNull null, o.str("ideBuild") ?: return@mapNotNull null, o.str("url") ?: return@mapNotNull null, o.str("sha256"))
        }.orEmpty()
    }.getOrDefault(emptyList())

    /** The chunk of [key] for exactly [ideBuild] (`IU-261.26222.65`; the product code may differ, the build number may not). */
    fun select(chunks: List<RemoteChunk>, key: String, ideBuild: String): RemoteChunk? =
        chunks.firstOrNull { it.key == key && it.ideBuild == ideBuild } ?: chunks.firstOrNull { it.key == key && buildNumber(it.ideBuild) == buildNumber(ideBuild) }

    /** [url] of an entry against the address of `index.json` it came from. */
    fun resolve(indexUrl: String, url: String): String =
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(url)) url else indexUrl.substringBeforeLast('/') + "/" + url.trimStart('/')

    /** The file name a downloaded chunk is stored under. */
    fun fileName(url: String): String = url.substringBefore('?').substringAfterLast('/').let { if (it.endsWith(CHUNK_SUFFIX)) it else "$it$CHUNK_SUFFIX" }

    private fun buildNumber(build: String): String = build.substringAfter('-')

    private fun JsonObject.str(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
}
