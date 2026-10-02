package io.github.golangsupport.sdk

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.cli.GoEnvironment
import io.github.golangsupport.project.api.GoToolchainInfo
import io.github.golangsupport.project.api.GoToolchainProvider
import io.github.golangsupport.project.impl.DefaultGoToolchainProvider
import io.github.golangsupport.project.impl.GoProjectModelTracker
import io.github.golangsupport.settings.GoSettings
import java.nio.file.Path

/**
 * The toolchain of the project model, from what the plugin already knows: the `go` of Settings | Tools | Go (or the one it found),
 * its `go env -json` ([GoEnvironment], read once in the background), the build tags of the settings and the GOOS / GOARCH the code is
 * analysed for. Replaces `DefaultGoToolchainProvider` of go-psi (the service is overridden in plugin.xml); its pure detection stays the
 * fallback while `go env` has not answered, and when there is no `go` at all. Never runs a process itself.
 */
class GoIgsToolchainProvider : GoToolchainProvider {
    private val tracker = SimpleModificationTracker()
    private val fallback = DefaultGoToolchainProvider()

    /** What the last answer was made of: when `go env` arrives or the settings change, the caches of the model are told. */
    @Volatile private var lastKey: Key? = null

    private data class Key(val executable: String?, val envKnown: Boolean, val tags: List<String>, val goos: String, val goarch: String)

    override val modificationTracker: ModificationTracker get() = tracker

    override fun toolchainFor(project: Project?): GoToolchainInfo? {
        val settings = GoSettings.getInstance()
        val executable = GoCli.findExecutable()
        val key = Key(executable, GoEnvironment.isKnown(), settings.tagList(), settings.analysisGoos, settings.analysisGoarch)
        if (key != lastKey) {
            lastKey = key
            tracker.incModificationCount()
            for (p in ProjectManager.getInstance().openProjects) if (!p.isDisposed) GoProjectModelTracker.getInstance(p).incModificationCount()
        }
        if (executable == null) return adjust(fallback.toolchainFor(project), settings)
        if (!GoEnvironment.isKnown()) {
            // the answer of `go env` comes later and bumps the trackers through the key above; until then the pure detection, with this go
            GoEnvironment.whenKnown { toolchainFor(project) }
            return adjust(pureFor(executable), settings)
        }
        val env = GoEnvironment.quick().values
        return adjust(DefaultGoToolchainProvider.fromGoEnv(env, Path.of(executable), pureFor(executable)), settings)
    }

    private fun pureFor(executable: String): GoToolchainInfo? = DefaultGoToolchainProvider.detectPure(System.getenv())?.copy(goBinary = Path.of(executable))

    /** The tags of the settings on top of `GOFLAGS`, and the GOOS / GOARCH of the settings when they are set. */
    private fun adjust(info: GoToolchainInfo?, settings: GoSettings): GoToolchainInfo? {
        info ?: return null
        val tags = settings.tagList()
        val goos = settings.analysisGoos
        val goarch = settings.analysisGoarch
        if (tags.isEmpty() && goos.isEmpty() && goarch.isEmpty()) return info
        return info.copy(buildTags = info.buildTags + tags, goos = goos.ifEmpty { info.goos }, goarch = goarch.ifEmpty { info.goarch })
    }
}
