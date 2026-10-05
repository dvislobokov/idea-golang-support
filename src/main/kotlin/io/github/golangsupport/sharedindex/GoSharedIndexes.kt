package io.github.golangsupport.sharedindex

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import io.github.golangsupport.project.api.GoToolchainProvider
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/** Where the GOROOT chunks of this IDE live and which one a project needs. Only loaded through go-shared-indexes.xml. */
object GoSharedIndexes {
    const val LOG_CATEGORY = "index"

    /** Per IDE system directory: a chunk is only usable by the build that dumped it, and system directories are per IDE version. */
    fun root(): Path = Path.of(PathManager.getSystemPath(), "go-plugin", "shared-indexes")

    fun directory(key: GoSharedIndexKey): Path = root().resolve(key.id)

    fun ideBuild(): String = ApplicationInfo.getInstance().build.asString()

    fun pluginVersion(): String = PluginManagerCore.getPlugin(PluginId.getId("io.github.golangsupport"))?.version.orEmpty()

    /** GOROOT of the project's toolchain and its key; null without Go or for a development tree. No `go` process: VERSION and pkg/tool only. */
    fun gorootOf(project: Project): Pair<Path, GoSharedIndexKey>? {
        val toolchain = GoToolchainProvider.getInstance().toolchainFor(project) ?: return null
        val goroot = toolchain.goroot ?: return null
        val versionFile = goroot.resolve("VERSION").takeIf { it.isRegularFile() } ?: return null
        val tools = goroot.resolve("pkg").resolve("tool").takeIf { it.isDirectory() }?.let { dir -> Files.list(dir).use { s -> s.map { it.name }.toList() } }.orEmpty()
        val key = GoSharedIndexKey.of(versionFile.readText(), tools, toolchain.goos, toolchain.goarch) ?: return null
        return goroot to key
    }

    /** The chunk files of [key] present on disk. */
    fun chunks(key: GoSharedIndexKey): List<Path> {
        val dir = directory(key).takeIf { it.isDirectory() } ?: return emptyList()
        val names = Files.list(dir).use { s -> s.filter { it.isRegularFile() }.map { it.name }.toList() }
        return GoSharedIndexLayout.chunks(names).map { dir.resolve(it) }
    }
}
